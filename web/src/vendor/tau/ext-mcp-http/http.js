// Streamable HTTP transport over the Platform seam (pi-mcp
// transports/streamable-http.ts, the subset tau uses). Each request is a POST;
// the reply comes back as JSON or as an SSE stream on the same response.
//
// P47 (MCP 2026-07-28): a request that carries the per-request envelope is sent as
// that revision says, whatever the connection: MCP-Protocol-Version from the envelope,
// Mcp-Method, Mcp-Name and the caller's Mcp-Param-* headers, no session id; a 4xx whose
// body is a JSON-RPC error for it is delivered as that error (the probe and the client
// read it), and a broken stream is reported to the caller to reissue, never resumed.
//
// An SSE stream that ends or breaks before its response arrives is resumed with
// GET and Last-Event-ID when the server assigned event ids, as servers may close
// response streams at will (pi; MCP 2025-11-25). The server-to-client GET stream
// (server notifications such as tools/list_changed) is opt-in (`openGetStream`):
// mobile hosts keep no idle connection open. Deviations from pi, written down
// (D7): the GET stream is opt-in, no DELETE on close (the server expires the
// session), no auth provider (headers only, D24). Adapters without response
// headers (some mini-program request APIs) are handled by sniffing the body.
import { AbortHandle, SseParser } from "@yophon/tau-kernel";
import { standardHeaders } from "./headers.js";
import { envelopeVersion, isJsonRpcRequest, isJsonRpcResponse, JSON_RPC_ERROR_CODES, McpConnectionClosedError, parseJsonRpcMessage, toError, } from "./jsonrpc.js";
import { TransportEvents } from "./transport.js";
export class McpHttpError extends Error {
    status;
    constructor(status, message) {
        super(message);
        this.name = "McpHttpError";
        this.status = status;
    }
}
/** 404 on a request that carried a session id: the server lost or expired the session. */
export class McpSessionExpiredError extends McpHttpError {
    constructor() {
        super(404, "MCP session expired");
        this.name = "McpSessionExpiredError";
    }
}
/** 401, or 403 with an `insufficient_scope` bearer challenge: step-up authorization (pi 1.2 needsAuthorization). */
function needsAuthorization(response) {
    if (response.status === 401)
        return true;
    if (response.status !== 403)
        return false;
    return /(?:^|[\s,])error="?insufficient_scope"?/i.test(response.headers?.get("www-authenticate") ?? "");
}
/** Reconnecting a dropped SSE stream (pi's defaults): from 1 s doubling to 30 s, 5 failures in a row. */
const RECONNECT_INITIAL_DELAY_MS = 1_000;
const RECONNECT_MAX_DELAY_MS = 30_000;
const RECONNECT_MAX_RETRIES = 5;
function newCursor() {
    return { lastEventId: undefined, retryMs: undefined, received: false };
}
function contentType(response) {
    return response.headers?.get("content-type")?.split(";", 1)[0]?.trim().toLowerCase();
}
/** 408, 429 and 5xx other than 501 are worth another attempt (pi isTransientStatus). */
function isTransientStatus(status) {
    return status === 408 || status === 429 || (status >= 500 && status !== 501);
}
/**
 * Network failures and transient statuses are retried; auth, session and protocol errors are not (pi).
 * Errors the Platform's fetch or a stream read throw count as network failures.
 */
function isRetryable(error) {
    if (error instanceof McpHttpError)
        return isTransientStatus(error.status);
    return !(error instanceof McpConnectionClosedError);
}
function reconnectDelay(attempt, serverDelayMs) {
    if (serverDelayMs !== undefined)
        return serverDelayMs;
    return Math.min(RECONNECT_INITIAL_DELAY_MS * 2 ** attempt, RECONNECT_MAX_DELAY_MS);
}
/** The JSON-RPC error a 4xx body carries for request `id`, if it is one. */
function errorReplyFor(body, id) {
    try {
        const reply = JSON.parse(body);
        return isJsonRpcResponse(reply) && "error" in reply && reply.id === id ? reply : undefined;
    }
    catch {
        return undefined;
    }
}
export class StreamableHttpTransport extends TransportEvents {
    kind = "http";
    options;
    lifetime = new AbortHandle();
    started = false;
    closed = false;
    sessionIdValue;
    protocolVersion;
    constructor(options) {
        super();
        this.options = Object.freeze({ ...options, headers: options.headers ? { ...options.headers } : undefined });
    }
    get sessionId() {
        return this.sessionIdValue;
    }
    async start() {
        if (this.started)
            throw new Error("MCP Streamable HTTP transport already started");
        if (this.closed)
            throw new McpConnectionClosedError();
        this.started = true;
    }
    setProtocolVersion(version) {
        this.protocolVersion = version;
    }
    async send(message, options = {}) {
        return this.sendOnce(message, options, false);
    }
    async sendOnce(message, options, retried) {
        const { signal } = options;
        if (!this.started || this.closed)
            throw new McpConnectionClosedError();
        if (signal?.aborted)
            return;
        const isInitialize = isJsonRpcRequest(message) && message.method === "initialize";
        const modern = envelopeVersion(message);
        const headers = {
            "content-type": "application/json",
            accept: "application/json, text/event-stream",
            ...this.options.headers,
        };
        const token = await this.options.auth?.token();
        if (token)
            headers.authorization = `Bearer ${token}`;
        if (modern !== undefined && isJsonRpcRequest(message)) {
            // MCP 2026-07-28: the headers mirror the body; there is no session.
            Object.assign(headers, { "mcp-protocol-version": modern }, standardHeaders(message.method, message.params));
            Object.assign(headers, options.headers);
        }
        else if (!isInitialize) {
            // The initialize request itself carries neither header (MCP spec).
            if (this.protocolVersion !== undefined)
                headers["mcp-protocol-version"] = this.protocolVersion;
            if (this.sessionIdValue !== undefined)
                headers["mcp-session-id"] = this.sessionIdValue;
        }
        const request = new AbortHandle();
        request.follow(signal);
        request.follow(this.lifetime.signal);
        let response;
        try {
            response = await this.options.platform.fetch(this.options.url, {
                method: "POST",
                headers,
                body: JSON.stringify(message),
                signal: request.signal,
            });
        }
        catch (error) {
            request.abort("settled");
            throw error;
        }
        if (!response.ok) {
            // A 2026-07-28 server explains a 4xx in a JSON-RPC error body (version, headers, method).
            const body = modern !== undefined ? await response.text().catch(() => "") : undefined;
            request.abort("settled");
            if (needsAuthorization(response) && !retried && this.options.auth?.onUnauthorized) {
                await this.options.auth.onUnauthorized({
                    response,
                    serverUrl: this.options.url,
                    ...(token ? { token } : {}),
                });
                return this.sendOnce(message, options, true);
            }
            const reply = body !== undefined && isJsonRpcRequest(message) ? errorReplyFor(body, message.id) : undefined;
            if (reply) {
                this.emitMessage(reply);
                return;
            }
            if (response.status === 404 && this.sessionIdValue !== undefined && !isInitialize) {
                throw new McpSessionExpiredError();
            }
            throw new McpHttpError(response.status, `MCP server responded HTTP ${response.status}`);
        }
        const sessionId = response.headers?.get("mcp-session-id");
        if (sessionId)
            this.sessionIdValue = sessionId;
        // Notifications and responses are acknowledged (202) and carry no reply.
        if (!isJsonRpcRequest(message)) {
            request.abort("settled");
            // The server-to-client stream may only open once the session is initialized (pi).
            if ("method" in message && message.method === "notifications/initialized" && this.options.openGetStream) {
                void this.runGetStream();
            }
            return;
        }
        const type = contentType(response);
        if (type === "text/event-stream") {
            // The reply streams in; read it in the background so send() resolves on dispatch (pi).
            void this.consumeResponseStream(response, message.id, request, options.onStreamBroken);
            return;
        }
        try {
            const text = await response.text();
            if (type === "application/json" || !this.emitSse(text))
                this.emitJson(text);
        }
        finally {
            request.abort("settled");
        }
    }
    /** Keep the GET stream open, reconnecting with backoff when it drops (pi runGetStream). */
    async runGetStream() {
        const cursor = newCursor();
        const sleep = this.options.platform.sleep;
        for (let attempt = 0; !this.closed;) {
            try {
                const response = await this.openSseStream(cursor.lastEventId, this.lifetime.signal);
                // The server offers no GET stream.
                if (!response)
                    return;
                const openedAt = Date.now();
                await this.readSse(response, cursor, this.lifetime.signal);
                // A stream that stayed up for a while counts as healthy, even if it was idle (pi).
                if (cursor.received || Date.now() - openedAt > RECONNECT_MAX_DELAY_MS)
                    attempt = 0;
            }
            catch (error) {
                if (this.closed)
                    return;
                if (!isRetryable(error)) {
                    this.emitError(error);
                    return;
                }
            }
            cursor.received = false;
            if (attempt >= RECONNECT_MAX_RETRIES) {
                this.emitError(new Error("MCP server-to-client stream dropped and could not be reopened"));
                return;
            }
            if (!sleep)
                return;
            try {
                await sleep(reconnectDelay(attempt++, cursor.retryMs), this.lifetime.signal);
            }
            catch {
                return;
            }
        }
    }
    /** Open a GET SSE stream, resuming after `lastEventId`. Resolves undefined when the server answers 405. */
    async openSseStream(lastEventId, signal) {
        const headers = { accept: "text/event-stream", ...this.options.headers };
        const token = await this.options.auth?.token();
        if (token)
            headers.authorization = `Bearer ${token}`;
        if (this.protocolVersion !== undefined)
            headers["mcp-protocol-version"] = this.protocolVersion;
        if (this.sessionIdValue !== undefined)
            headers["mcp-session-id"] = this.sessionIdValue;
        if (lastEventId !== undefined)
            headers["last-event-id"] = lastEventId;
        const response = await this.options.platform.fetch(this.options.url, { method: "GET", headers, signal });
        if (response.status === 405)
            return undefined;
        if (!response.ok) {
            if (response.status === 404 && this.sessionIdValue !== undefined)
                throw new McpSessionExpiredError();
            throw new McpHttpError(response.status, `MCP server responded HTTP ${response.status}`);
        }
        const sessionId = response.headers?.get("mcp-session-id");
        if (sessionId)
            this.sessionIdValue = sessionId;
        const type = contentType(response);
        if (type !== "text/event-stream" || !response.body) {
            throw new McpHttpError(response.status, `Unsupported MCP GET response content type: ${type ?? "missing"}`);
        }
        return response;
    }
    async close() {
        if (this.closed)
            return;
        this.closed = true;
        this.lifetime.abort("closed");
        this.emitClose();
    }
    /** Emit every JSON-RPC message of a JSON body (one message or a batch). */
    emitJson(text) {
        const body = JSON.parse(text);
        for (const item of Array.isArray(body) ? body : [body])
            this.emitMessage(parseJsonRpcMessage(item));
    }
    /**
     * A body without a content type (adapters without response headers): JSON first,
     * then SSE framing. Returns false when the text is JSON, so the caller parses it.
     */
    emitSse(text) {
        try {
            JSON.parse(text);
            return false;
        }
        catch {
            // not JSON: SSE framing
        }
        const parser = new SseParser();
        for (const event of [...parser.push(text), ...parser.flush()])
            this.emitEventData(event.data);
        return true;
    }
    emitEventData(data) {
        if (!data.trim())
            return false;
        let message;
        try {
            message = parseJsonRpcMessage(JSON.parse(data));
        }
        catch (error) {
            this.emitError(error);
            return false;
        }
        this.emitMessage(message);
        return true;
    }
    /**
     * Read one SSE stream until it ends, `signal` aborts or `until` holds, delivering its
     * JSON-RPC messages and noting event ids and the retry delay in `cursor`. Events
     * without data prime resumption; other event types are not JSON-RPC (pi).
     */
    async readSse(response, cursor, signal, onData, until) {
        const reader = response.body?.getReader();
        if (!reader)
            throw new Error("MCP SSE response had no readable body");
        const decoder = this.options.platform.createUtf8Decoder();
        const parser = new SseParser({
            onId: (id) => {
                cursor.lastEventId = id;
            },
            onRetry: (delayMs) => {
                cursor.retryMs = delayMs;
            },
        });
        try {
            while (!signal.aborted && !until?.()) {
                const { done, value } = await reader.read();
                const events = done ? parser.flush() : value ? parser.push(decoder.decode(value)) : [];
                for (const event of events) {
                    cursor.received = true;
                    if (event.event !== undefined && event.event !== "message")
                        continue;
                    // Server requests and notifications interleaved on the stream are delivered too.
                    if (this.emitEventData(event.data))
                        onData?.(event.data);
                }
                if (done)
                    return;
            }
        }
        finally {
            // Best-effort, sync or async (as ext-web does): the request's abort right after this can
            // reject a pending cancel, and an unhandled rejection ends `tau -p`.
            void Promise.resolve()
                .then(() => reader.cancel("done"))
                .catch(() => undefined);
        }
    }
    /**
     * Read the SSE reply to one request. When the stream ends or breaks before the response
     * arrives and the server assigned event ids, resume it with GET and Last-Event-ID (pi);
     * otherwise, or once resuming fails, only this request fails. With `onStreamBroken`
     * (MCP 2026-07-28, no resumption) the caller is told instead.
     */
    async consumeResponseStream(response, requestId, request, onStreamBroken) {
        const cursor = newCursor();
        let answered = false;
        const onData = (data) => {
            if (answersRequest(data, requestId))
                answered = true;
        };
        const sleep = this.options.platform.sleep;
        let stream = response;
        let failure;
        // The request was given up on (timeout, abort) or the transport closed: say nothing.
        const stopped = () => request.signal.aborted || this.closed;
        try {
            for (let attempt = 0;;) {
                if (stream) {
                    try {
                        await this.readSse(stream, cursor, request.signal, onData, () => answered);
                        failure = undefined;
                    }
                    catch (error) {
                        failure = error;
                    }
                }
                if (answered || stopped())
                    return;
                if (onStreamBroken)
                    break;
                if (failure !== undefined && !isRetryable(failure))
                    break;
                if (cursor.lastEventId === undefined || attempt >= RECONNECT_MAX_RETRIES || !sleep)
                    break;
                if (cursor.received)
                    attempt = 0;
                cursor.received = false;
                try {
                    await sleep(reconnectDelay(attempt++, cursor.retryMs), request.signal);
                }
                catch {
                    return;
                }
                if (stopped())
                    return;
                try {
                    stream = await this.openSseStream(cursor.lastEventId, request.signal);
                    // No GET stream to resume on.
                    if (!stream)
                        break;
                }
                catch (error) {
                    if (stopped())
                        return;
                    failure = error;
                    if (!isRetryable(error))
                        break;
                    stream = undefined;
                }
            }
        }
        finally {
            request.abort("settled");
        }
        if (this.closed)
            return;
        const reason = failure === undefined ? "stream ended without a response" : toError(failure).message;
        if (onStreamBroken) {
            onStreamBroken(reason);
            return;
        }
        this.emitMessage({
            jsonrpc: "2.0",
            id: requestId,
            error: { code: JSON_RPC_ERROR_CODES.internalError, message: `MCP response stream failed: ${reason}` },
        });
    }
}
function answersRequest(data, requestId) {
    try {
        const message = JSON.parse(data);
        return message.id === requestId && message.method === undefined;
    }
    catch {
        return false;
    }
}
