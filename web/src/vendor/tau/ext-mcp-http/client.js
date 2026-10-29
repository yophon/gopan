// MCP client over an McpTransport (pi-mcp client.ts, the subset tau uses):
// initialize handshake with protocol version negotiation, request/response
// correlation, paginated tools/list, resources (P43) and prompts (P44),
// tools/call, notifications, progress (P44: a progress notification re-arms the
// request's timeout), notifications/cancelled both ways (P44), and answers to
// server requests (ping, roots/list, handlers set by the host such as
// elicitation/create). Pure ES: timeouts run on the injected Platform.sleep (no
// timers without it, D15).
//
// P47 (MCP 2026-07-28, as the official SDK 2.x client and Claude Code): with
// protocolNegotiation "auto", connect() first sends server/discover. A DiscoverResult
// means the modern, stateless revision: no handshake, every request carries the
// `_meta` envelope (version, client info, capabilities), results carry `resultType`,
// a broken HTTP stream is reissued once under a new id, and the server sends no
// requests. Anything that is not modern evidence falls back to initialize; on stdio a
// silent server is legacy, on HTTP silence is an outage (SDK).
//
// 47b: an input_required result (tools/call, prompts/get, resources/read) is answered
// through the handlers set for elicitation/create, roots/list and sampling, and the call
// is retried with inputResponses and the echoed requestState (10 rounds at most, SDK);
// listen() opens subscriptions/listen; over HTTP, tool parameters marked x-mcp-header
// are mirrored as Mcp-Param-* headers and tools with invalid marks are left out.
import { AbortHandle } from "@yophon/tau-kernel";
import { mirroredParams, paramHeaders } from "./headers.js";
import { isJsonRpcNotification, isJsonRpcRequest, isJsonRpcResponse, isObject, JSON_RPC_ERROR_CODES, MCP_ERROR_CODES, MCP_META, McpAbortError, McpConnectionClosedError, McpError, McpProbeExitedError, McpStreamBrokenError, McpTimeoutError, toError, } from "./jsonrpc.js";
/** Protocol version tau offers in `initialize` (pi 1.2 LATEST_PROTOCOL_VERSION). */
export const MCP_PROTOCOL_VERSION = "2025-11-25";
/** The stateless revision (P47): no handshake, a `_meta` envelope on every request. */
export const MCP_MODERN_PROTOCOL_VERSION = "2026-07-28";
const MODERN_ERROR_CODES = new Set(Object.values(MCP_ERROR_CODES));
/** The requests a server may answer with input_required (MRTR). */
const INPUT_REQUIRED_METHODS = new Set(["tools/call", "prompts/get", "resources/read"]);
/** What an input_required result may ask the client for. */
const INPUT_REQUEST_METHODS = new Set(["elicitation/create", "sampling/createMessage", "roots/list"]);
/** Rounds of input one call may take (the official SDK's inputRequired.maxRounds default). */
const DEFAULT_MAX_INPUT_ROUNDS = 10;
/** Versions the server may select in `initialize`; any other closes the connection (pi). */
export const SUPPORTED_PROTOCOL_VERSIONS = [
    MCP_PROTOCOL_VERSION,
    "2025-06-18",
    "2025-03-26",
    "2024-11-05",
];
const MAX_LIST_PAGES = 1_000;
/** listen() failed before the acknowledgement: "graceful" (refused) or "remote" (dropped). */
export class McpSubscriptionError extends Error {
    end;
    constructor(end) {
        super(end === "graceful"
            ? "MCP server ended the subscription without acknowledging it"
            : "MCP subscription stream closed before it was acknowledged");
        this.name = "McpSubscriptionError";
        this.end = end;
    }
}
/** A -32022 whose supported versions include the one tau speaks. */
function offersModern(error) {
    if (!(error instanceof McpError) || error.code !== MCP_ERROR_CODES.unsupportedProtocolVersion)
        return false;
    const supported = isObject(error.data) && Array.isArray(error.data.supported) ? error.data.supported : [];
    return supported.includes(MCP_MODERN_PROTOCOL_VERSION);
}
/** How long the probe waits when nothing else bounds it (the official SDK uses the request timeout). */
const DEFAULT_PROBE_TIMEOUT_MS = 10_000;
function invalid(message) {
    return new McpError(JSON_RPC_ERROR_CODES.invalidRequest, message);
}
function validateInitializeResult(value) {
    // Lenient where pi is strict about serverInfo: small servers omit it and work fine.
    if (!isObject(value) || typeof value.protocolVersion !== "string")
        throw invalid("Invalid MCP initialize result");
    return {
        protocolVersion: value.protocolVersion,
        capabilities: isObject(value.capabilities) ? value.capabilities : {},
        ...(isObject(value.serverInfo) && typeof value.serverInfo.name === "string"
            ? { serverInfo: value.serverInfo }
            : {}),
        ...(typeof value.instructions === "string" ? { instructions: value.instructions } : {}),
    };
}
/** One page of a paginated list: the items under `key`, each checked by `isItem` (pi validateListPage). */
function validateListPage(method, key, value, isItem) {
    const items = isObject(value) ? value[key] : undefined;
    if (!isObject(value) || !Array.isArray(items))
        throw invalid(`Invalid MCP ${method} result`);
    for (const item of items) {
        if (!isObject(item) || !isItem(item))
            throw invalid(`Invalid entry in MCP ${method} result`);
    }
    // Some servers end pagination with `null` or `""` instead of omitting the cursor (pi).
    const nextCursor = value.nextCursor === null || value.nextCursor === "" ? undefined : value.nextCursor;
    if (nextCursor !== undefined && typeof nextCursor !== "string")
        throw invalid(`Invalid MCP ${method} cursor`);
    return { items, ...(nextCursor === undefined ? {} : { nextCursor }) };
}
// `name` is required by the spec, but some servers omit it; the URI stands in (pi).
const isResource = (item) => typeof item.uri === "string" && (item.name === undefined || typeof item.name === "string");
const isResourceTemplate = (item) => typeof item.uriTemplate === "string" && (item.name === undefined || typeof item.name === "string");
const toResource = (item) => ({ ...item, name: item.name ?? item.uri });
const toResourceTemplate = (item) => ({ ...item, name: item.name ?? item.uriTemplate });
const pageCursor = (page) => page.nextCursor === undefined ? {} : { nextCursor: page.nextCursor };
function validateReadResourceResult(value) {
    if (!isObject(value) || !Array.isArray(value.contents))
        throw invalid("Invalid MCP resources/read result");
    for (const contents of value.contents) {
        if (!isObject(contents) ||
            typeof contents.uri !== "string" ||
            (typeof contents.text !== "string" && typeof contents.blob !== "string")) {
            throw invalid("Invalid contents in MCP resources/read result");
        }
    }
    // Validated above.
    return value;
}
/**
 * `content` is required by the spec, but servers that only return `structuredContent` omit it (pi).
 * `structuredContent` may be any JSON value (pi 1.2).
 */
function validateCallToolResult(value) {
    if (!isObject(value) || (value.content !== undefined && !Array.isArray(value.content))) {
        throw invalid("Invalid MCP tools/call result");
    }
    // Validated above.
    return (value.content === undefined ? { ...value, content: [] } : value);
}
const isPrompt = (item) => typeof item.name === "string" && (item.arguments === undefined || Array.isArray(item.arguments));
function validateGetPromptResult(value) {
    if (!isObject(value) || !Array.isArray(value.messages))
        throw invalid("Invalid MCP prompts/get result");
    for (const message of value.messages) {
        if (!isObject(message) ||
            (message.role !== "user" && message.role !== "assistant") ||
            !isObject(message.content) ||
            typeof message.content.type !== "string") {
            throw invalid("Invalid message in MCP prompts/get result");
        }
    }
    // Validated above.
    return value;
}
const isJsonRpcIdValue = (value) => typeof value === "string" || typeof value === "number";
export class McpClient {
    options;
    state = "idle";
    transport;
    nextRequestId = 1;
    instructionsValue;
    protocolVersionValue;
    serverCapabilitiesValue;
    eraValue;
    serverInfoValue;
    eraRejectedValue = false;
    /** Requests given up on whose late answers are expected (the probe), dropped quietly. */
    ignoredResponses = new Set();
    /** x-mcp-header parameters by tool, from the last tools/list (2026-07-28 over HTTP). */
    paramHeaderMap = new Map();
    excludedToolsValue = [];
    pending = new Map();
    /** progressToken → request id of the requests that asked for progress. */
    progressRequests = new Map();
    /** Server requests being answered, to abort on notifications/cancelled. */
    incoming = new Map();
    requestHandlers = new Map();
    notificationListeners = new Map();
    errorListeners = new Set();
    closeListeners = new Set();
    disposers = [];
    constructor(options) {
        this.options = Object.freeze({ ...options });
        this.requestHandlers.set("ping", () => ({}));
        const roots = options.roots;
        if (roots)
            this.requestHandlers.set("roots/list", () => ({ roots: roots.map((root) => ({ ...root })) }));
    }
    get connectionState() {
        return this.state;
    }
    /** Server instructions from `initialize`, describing its tools as a group. */
    get instructions() {
        return this.instructionsValue;
    }
    get protocolVersion() {
        return this.protocolVersionValue;
    }
    /** What the server offers (`initialize`): `tools`, `resources`, `prompts`, … */
    get serverCapabilities() {
        return this.serverCapabilitiesValue;
    }
    /** The era connect() settled on (P47). */
    get protocolEra() {
        return this.eraValue;
    }
    /** The server's self-reported name and version (initialize, or the 2026-07-28 result `_meta`). */
    get serverInfo() {
        return this.serverInfoValue;
    }
    /** Tools the last tools/list left out because their x-mcp-header marks are invalid (spec). */
    get excludedTools() {
        return this.excludedToolsValue;
    }
    /** A 2026-07-28 server stopped accepting the revision mid-connection: probe again next time. */
    get eraRejected() {
        return this.eraRejectedValue;
    }
    async connect(transport, options = {}) {
        if (this.state !== "idle")
            throw new Error(`Cannot connect MCP client in ${this.state} state`);
        this.state = "connecting";
        this.transport = transport;
        this.disposers = [
            transport.onMessage((message) => this.handleMessage(message)),
            // Transport errors are reported only; pending requests fail when the transport closes.
            transport.onError((error) => this.emitError(error)),
            transport.onClose(() => this.markClosed(new McpConnectionClosedError())),
        ];
        const { era, ...requestOptions } = options;
        const probe = era === undefined && this.options.protocolNegotiation === "auto";
        try {
            await transport.start();
            if (probe || era === "modern") {
                const modern = await this.discover(transport, requestOptions, era === "modern");
                if (modern) {
                    this.state = "connected";
                    return modern;
                }
            }
            try {
                return await this.initialize(transport, requestOptions);
            }
            catch (error) {
                // The probe may have left a stdio server unable to shake hands: start it again.
                if (probe && transport.kind !== "http" && !(error instanceof McpAbortError)) {
                    throw new McpProbeExitedError(`MCP server did not shake hands after the probe: ${toError(error).message}`);
                }
                throw error;
            }
        }
        catch (error) {
            await this.close().catch(() => { });
            throw error;
        }
    }
    /** The legacy handshake (pi). */
    async initialize(transport, options) {
        const result = validateInitializeResult(await this.requestInternal("initialize", {
            protocolVersion: MCP_PROTOCOL_VERSION,
            capabilities: this.clientCapabilities(),
            clientInfo: { name: this.options.name, version: this.options.version },
        }, options, { connecting: true, envelope: false, cancellable: false }));
        if (!SUPPORTED_PROTOCOL_VERSIONS.includes(result.protocolVersion)) {
            throw new Error(`MCP server selected unsupported protocol version ${result.protocolVersion}`);
        }
        this.eraValue = "legacy";
        this.protocolVersionValue = result.protocolVersion;
        this.serverCapabilitiesValue = result.capabilities;
        this.instructionsValue = result.instructions?.trim() || undefined;
        this.serverInfoValue = result.serverInfo;
        transport.setProtocolVersion?.(result.protocolVersion);
        await this.notifyInternal("notifications/initialized", undefined, true);
        this.state = "connected";
        return result;
    }
    /**
     * server/discover with the 2026-07-28 envelope (P47). Resolves the connection for a modern
     * server and undefined for a legacy one (any reply that is not modern evidence; on stdio
     * also silence). `pinned` (a remembered "modern") turns a legacy answer into an error.
     */
    async discover(transport, options, pinned) {
        const http = transport.kind === "http";
        let value;
        let probeId;
        const timeoutMs = options.timeoutMs ?? this.options.requestTimeoutMs ?? DEFAULT_PROBE_TIMEOUT_MS;
        const ask = () => this.requestInternal("server/discover", undefined, { ...options, timeoutMs }, {
            connecting: true,
            envelope: true,
            cancellable: false,
            onId: (id) => {
                probeId = id;
            },
        });
        try {
            // A -32022 that lists 2026-07-28 after all: retry with it once (spec: pick a mutually supported version).
            value = await ask().catch((error) => (offersModern(error) ? ask() : Promise.reject(error)));
        }
        catch (error) {
            // A slow server may still answer the abandoned probe.
            if (probeId !== undefined)
                this.ignoredResponses.add(probeId);
            if (error instanceof McpAbortError)
                throw error;
            if (error instanceof McpError) {
                if (error.code === MCP_ERROR_CODES.unsupportedProtocolVersion)
                    return this.legacyFromSupported(error.data);
                if (MODERN_ERROR_CODES.has(error.code))
                    throw error;
            }
            else if (error instanceof McpConnectionClosedError) {
                throw http ? error : new McpProbeExitedError();
            }
            else if (error instanceof McpTimeoutError) {
                if (http)
                    throw error;
            }
            else {
                // HTTP: 401/403 are not era evidence (auth answers first), 5xx is an outage; other 4xx is legacy.
                const status = error.status;
                if (typeof status !== "number" || status === 401 || status === 403 || status >= 500)
                    throw error;
            }
            if (pinned)
                throw new Error("MCP server no longer answers server/discover (protocol 2026-07-28)");
            return undefined;
        }
        const supported = isObject(value) && Array.isArray(value.supportedVersions) ? value.supportedVersions : undefined;
        if (!isObject(value) || !supported?.includes(MCP_MODERN_PROTOCOL_VERSION)) {
            if (pinned)
                throw new Error("MCP server no longer offers protocol 2026-07-28");
            return supported ? this.legacyFromSupported({ supported }) : undefined;
        }
        const meta = isObject(value._meta) ? value._meta : {};
        const serverInfo = meta[MCP_META.serverInfo];
        const result = {
            protocolVersion: MCP_MODERN_PROTOCOL_VERSION,
            capabilities: isObject(value.capabilities) ? value.capabilities : {},
            ...(isObject(serverInfo) && typeof serverInfo.name === "string"
                ? { serverInfo: serverInfo }
                : {}),
            ...(typeof value.instructions === "string" ? { instructions: value.instructions } : {}),
        };
        this.eraValue = "modern";
        this.protocolVersionValue = MCP_MODERN_PROTOCOL_VERSION;
        this.serverCapabilitiesValue = result.capabilities;
        this.instructionsValue = result.instructions?.trim() || undefined;
        this.serverInfoValue = result.serverInfo;
        return result;
    }
    /** A modern server without 2026-07-28: shake hands when it lists a version tau speaks there. */
    legacyFromSupported(data) {
        const supported = isObject(data) && Array.isArray(data.supported) ? data.supported.map(String) : [];
        if (supported.some((version) => SUPPORTED_PROTOCOL_VERSIONS.includes(version)))
            return undefined;
        throw new Error(`MCP server supports protocol ${supported.join(", ") || "(none listed)"}, none of which tau speaks`);
    }
    /** Capabilities declared in initialize and in every 2026-07-28 envelope. */
    clientCapabilities() {
        return { ...this.options.capabilities, ...(this.options.roots ? { roots: {} } : {}) };
    }
    request(method, params, options = {}) {
        return this.send(method, params, options, {});
    }
    send(method, params, options, flags) {
        if (this.eraValue !== "modern")
            return this.requestInternal(method, params, options, flags);
        return this.requestModern(method, params, options, flags);
    }
    /**
     * A 2026-07-28 request: reissued once under a new id when its HTTP stream broke (spec),
     * `resultType` checked (absent means complete). A -32022 means the server dropped the
     * revision: the connection closes and the next one probes again.
     */
    async requestModern(method, params, options, flags) {
        const maxRounds = this.options.maxInputRounds ?? DEFAULT_MAX_INPUT_ROUNDS;
        let current = params;
        for (let rounds = 0;; rounds++) {
            const result = await this.sendOnceModern(method, current, options, flags);
            const resultType = isObject(result) ? result.resultType : undefined;
            if (resultType === undefined || resultType === "complete")
                return result;
            if (resultType !== "input_required" || !INPUT_REQUIRED_METHODS.has(method) || !isObject(result)) {
                throw invalid(`Invalid MCP ${method} result: resultType ${JSON.stringify(resultType)}`);
            }
            const requests = isObject(result.inputRequests) ? result.inputRequests : undefined;
            const state = typeof result.requestState === "string" ? result.requestState : undefined;
            if (!requests && state === undefined)
                throw invalid(`Invalid MCP ${method} result: input_required asks for nothing`);
            if (rounds >= maxRounds)
                throw new Error(`MCP ${method} still needed input after ${maxRounds} rounds`);
            const inputResponses = requests ? await this.fulfilInputs(requests, options.signal) : undefined;
            if (options.signal?.aborted)
                throw new McpAbortError();
            // The retry: the original params, the answers, the server's state byte for byte (spec).
            const base = Object.fromEntries(Object.entries(params ?? {}).filter(([key]) => key !== "inputResponses" && key !== "requestState"));
            current = {
                ...base,
                ...(inputResponses ? { inputResponses } : {}),
                ...(state !== undefined ? { requestState: state } : {}),
            };
        }
    }
    /** Answer each input request through the handler set for its method (MRTR). */
    async fulfilInputs(requests, signal) {
        const responses = {};
        for (const [key, request] of Object.entries(requests)) {
            if (!isObject(request) || typeof request.method !== "string")
                throw invalid("Invalid MCP input request");
            const handler = INPUT_REQUEST_METHODS.has(request.method) ? this.requestHandlers.get(request.method) : undefined;
            if (!handler)
                throw new Error(`MCP server asked for ${request.method}, which this client does not offer`);
            responses[key] = (await handler(request.params, { signal: signal ?? new AbortHandle().signal })) ?? {};
        }
        return responses;
    }
    /**
     * One 2026-07-28 round trip: reissued once under a new id when its HTTP stream broke
     * (spec). A -32022 means the server dropped the revision: the connection closes and the
     * next one probes again.
     */
    async sendOnceModern(method, params, options, flags) {
        for (let attempt = 0;; attempt++) {
            try {
                return await this.requestInternal(method, params, options, { ...flags, reportBrokenStream: true });
            }
            catch (error) {
                if (error instanceof McpStreamBrokenError && attempt === 0 && !options.signal?.aborted)
                    continue;
                // Rejected, yet 2026-07-28 is among the versions the server lists: retry once (spec).
                if (offersModern(error) && attempt === 0)
                    continue;
                if (error instanceof McpError && error.code === MCP_ERROR_CODES.unsupportedProtocolVersion) {
                    this.eraRejectedValue = true;
                    void this.close().catch(() => { });
                }
                throw error;
            }
        }
    }
    /**
     * subscriptions/listen (2026-07-28): resolves once the server acknowledges the stream.
     * Its notifications reach onNotification listeners like unsolicited 2025 ones.
     */
    async listen(filter) {
        if (this.eraValue !== "modern")
            throw new Error("subscriptions/listen needs MCP 2026-07-28");
        const local = new AbortHandle();
        let listenId;
        let acknowledge = () => { };
        const acknowledged = new Promise((resolve) => {
            acknowledge = resolve;
        });
        const offAck = this.onNotification("notifications/subscriptions/acknowledged", (params) => {
            const meta = isObject(params) && isObject(params._meta) ? params._meta : undefined;
            if (listenId === undefined || meta?.[MCP_META.subscriptionId] !== listenId)
                return;
            acknowledge(isObject(params) && isObject(params.notifications) ? params.notifications : {});
        });
        const response = this.requestInternal("subscriptions/listen", { notifications: filter }, { signal: local.signal, timeoutMs: 0 }, {
            reportBrokenStream: true,
            listen: true,
            onId: (id) => {
                listenId = id;
            },
        });
        const closed = response.then(() => "graceful", () => (local.isAborted ? "local" : "remote"));
        void closed.finally(offAck);
        const honored = await Promise.race([
            acknowledged,
            closed.then((end) => {
                throw new McpSubscriptionError(end);
            }),
        ]);
        return { honored, closed, close: () => local.abort("closed") };
    }
    notify(method, params) {
        return this.notifyInternal(method, params, false);
    }
    /** Answer server requests of `method` (pi); returns a disposer. */
    setRequestHandler(method, handler) {
        this.requestHandlers.set(method, handler);
        return () => {
            if (this.requestHandlers.get(method) === handler)
                this.requestHandlers.delete(method);
        };
    }
    onNotification(method, listener) {
        const listeners = this.notificationListeners.get(method) ?? new Set();
        this.notificationListeners.set(method, listeners);
        listeners.add(listener);
        return () => {
            listeners.delete(listener);
            if (listeners.size === 0)
                this.notificationListeners.delete(method);
        };
    }
    onError(listener) {
        this.errorListeners.add(listener);
        return () => this.errorListeners.delete(listener);
    }
    /** Called once when the connection closes, whether the transport dropped or `close()` was called. */
    onClose(listener) {
        this.closeListeners.add(listener);
        return () => this.closeListeners.delete(listener);
    }
    /** Every tool, following `nextCursor` through all pages. */
    async listTools(options = {}) {
        const tools = await this.listAllTools(options);
        if (this.eraValue !== "modern" || this.transport?.kind !== "http")
            return tools;
        // x-mcp-header (SEP-2243): mirror valid marks; a tool with an invalid one is left out.
        const headers = new Map();
        const excluded = [];
        const kept = tools.filter((tool) => {
            const params = paramHeaders(tool.inputSchema);
            if (Array.isArray(params)) {
                if (params.length > 0)
                    headers.set(tool.name, params);
                return true;
            }
            excluded.push({ name: tool.name, reason: params.error });
            return false;
        });
        this.paramHeaderMap = headers;
        this.excludedToolsValue = excluded;
        return kept;
    }
    async listAllTools(options) {
        const tools = [];
        const cursors = new Set();
        let cursor;
        for (let page = 0; page < MAX_LIST_PAGES; page++) {
            const value = await this.request("tools/list", cursor === undefined ? undefined : { cursor }, options);
            const items = isObject(value) ? value.tools : undefined;
            if (!isObject(value) || !Array.isArray(items))
                throw invalid("Invalid MCP tools/list result");
            for (const item of items) {
                if (!isObject(item) || typeof item.name !== "string")
                    throw invalid("Invalid entry in MCP tools/list result");
                // Servers that omit inputSchema get the empty object schema tools require.
                tools.push({
                    // isTool checked the item.
                    ...item,
                    inputSchema: isObject(item.inputSchema) ? item.inputSchema : { type: "object", properties: {} },
                });
            }
            // Some servers end pagination with `null` or `""` instead of omitting the cursor (pi).
            const next = value.nextCursor === null || value.nextCursor === "" ? undefined : value.nextCursor;
            if (next === undefined)
                return tools;
            if (typeof next !== "string")
                throw invalid("Invalid MCP tools/list cursor");
            if (cursors.has(next))
                throw new Error(`MCP tools/list returned duplicate cursor: ${next}`);
            cursors.add(next);
            cursor = next;
        }
        throw new Error(`MCP tools/list exceeded ${MAX_LIST_PAGES} pages`);
    }
    /** Every resource, following `nextCursor` through all pages (pi). */
    async listResources(options = {}) {
        return (await this.listAll("resources/list", "resources", isResource, options)).map(toResource);
    }
    /** One page of resources, starting at `cursor` (pi). */
    async listResourcesPage(cursor, options = {}) {
        const page = await this.listPage("resources/list", "resources", isResource, cursor, options);
        return { resources: page.items.map(toResource), ...pageCursor(page) };
    }
    /** Every resource template, following `nextCursor` through all pages (pi). */
    async listResourceTemplates(options = {}) {
        const templates = await this.listAll("resources/templates/list", "resourceTemplates", isResourceTemplate, options);
        return templates.map(toResourceTemplate);
    }
    /** One page of resource templates, starting at `cursor` (pi). */
    async listResourceTemplatesPage(cursor, options = {}) {
        const page = await this.listPage("resources/templates/list", "resourceTemplates", isResourceTemplate, cursor, options);
        return { resourceTemplates: page.items.map(toResourceTemplate), ...pageCursor(page) };
    }
    async readResource(uri, options = {}) {
        return validateReadResourceResult(await this.request("resources/read", { uri }, options));
    }
    /** Every prompt, following `nextCursor` through all pages (P44). */
    async listPrompts(options = {}) {
        // isPrompt checked every item.
        return (await this.listAll("prompts/list", "prompts", isPrompt, options));
    }
    async getPrompt(name, args, options = {}) {
        return validateGetPromptResult(await this.request("prompts/get", { name, ...(args === undefined ? {} : { arguments: args }) }, options));
    }
    async listPage(method, key, isItem, cursor, options) {
        return validateListPage(method, key, await this.request(method, cursor === undefined ? undefined : { cursor }, options), isItem);
    }
    /** Every item of a paginated list method (pi listAll). */
    async listAll(method, key, isItem, options) {
        const items = [];
        const cursors = new Set();
        let cursor;
        for (let pageNumber = 0; pageNumber < MAX_LIST_PAGES; pageNumber++) {
            const page = await this.listPage(method, key, isItem, cursor, options);
            items.push(...page.items);
            if (page.nextCursor === undefined)
                return items;
            if (cursors.has(page.nextCursor))
                throw new Error(`MCP ${method} returned duplicate cursor: ${page.nextCursor}`);
            cursors.add(page.nextCursor);
            cursor = page.nextCursor;
        }
        throw new Error(`MCP ${method} exceeded ${MAX_LIST_PAGES} pages`);
    }
    async callTool(name, args, options = {}) {
        const params = { name, ...(args === undefined ? {} : { arguments: args }) };
        const mirrored = () => {
            const marks = this.paramHeaderMap.get(name);
            return marks ? { headers: mirroredParams(marks, args) } : {};
        };
        try {
            return validateCallToolResult(await this.send("tools/call", params, options, mirrored()));
        }
        catch (error) {
            // The headers disagreed with the server: its tool list may have changed (spec), so list and retry once.
            if (!(error instanceof McpError) || error.code !== MCP_ERROR_CODES.headerMismatch || this.eraValue !== "modern") {
                throw error;
            }
            await this.listTools({ ...(options.signal ? { signal: options.signal } : {}) });
            return validateCallToolResult(await this.send("tools/call", params, options, mirrored()));
        }
    }
    async close() {
        const transport = this.transport;
        this.transport = undefined;
        for (const dispose of this.disposers.splice(0))
            dispose();
        this.markClosed(new McpConnectionClosedError());
        await transport?.close();
    }
    requestInternal(method, params, options, flags) {
        let transport;
        try {
            transport = this.requireTransport(flags.connecting === true);
        }
        catch (error) {
            return Promise.reject(error);
        }
        if (options.signal?.aborted)
            return Promise.reject(new McpAbortError());
        const id = this.nextRequestId++;
        flags.onId?.(id);
        const envelope = flags.envelope ?? this.eraValue === "modern";
        // The request id doubles as the progress token (pi).
        const progressToken = options.onProgress ? id : undefined;
        const meta = {
            ...(isObject(params?._meta) ? params._meta : {}),
            ...(progressToken === undefined ? {} : { progressToken }),
            ...(envelope
                ? {
                    [MCP_META.protocolVersion]: MCP_MODERN_PROTOCOL_VERSION,
                    [MCP_META.clientInfo]: { name: this.options.name, version: this.options.version },
                    [MCP_META.clientCapabilities]: this.clientCapabilities(),
                }
                : {}),
        };
        const requestParams = progressToken === undefined && !envelope ? params : { ...params, _meta: meta };
        const message = {
            jsonrpc: "2.0",
            id,
            method,
            ...(requestParams === undefined ? {} : { params: requestParams }),
        };
        // 2026-07-28 over HTTP cancels by closing the request's stream, which settling does.
        const cancellable = flags.cancellable ?? (method !== "initialize" && !(envelope && transport.kind === "http"));
        const sendOptions = {
            ...(flags.headers ? { headers: flags.headers } : {}),
            ...(flags.reportBrokenStream
                ? { onStreamBroken: (reason) => this.failPending(id, new McpStreamBrokenError(reason)) }
                : {}),
        };
        return new Promise((resolve, reject) => {
            const entry = {
                resolve,
                reject,
                signal: options.signal,
                onAbort: () => this.cancelPending(id, new McpAbortError(), cancellable, String(options.signal?.reason ?? "Aborted")),
                settle: new AbortHandle(),
                timeoutMs: options.timeoutMs ?? this.options.requestTimeoutMs,
                timer: undefined,
                cancellable,
                onProgress: options.onProgress,
                progressToken,
                listen: flags.listen === true,
            };
            this.pending.set(id, entry);
            if (progressToken !== undefined)
                this.progressRequests.set(progressToken, id);
            options.signal?.addEventListener("abort", entry.onAbort);
            this.armTimeout(id, entry);
            transport
                .send(message, { signal: entry.settle.signal, ...sendOptions })
                .catch((error) => this.failPending(id, error));
        });
    }
    /** (Re)start the request's timeout (pi armTimeout); a timeout cancels the request on the server too. */
    armTimeout(id, entry) {
        entry.timer?.abort("re-armed");
        entry.timer = undefined;
        const timeoutMs = entry.timeoutMs;
        const sleep = this.options.platform?.sleep;
        if (timeoutMs === undefined || !Number.isFinite(timeoutMs) || timeoutMs <= 0 || !sleep)
            return;
        const timer = new AbortHandle();
        entry.timer = timer;
        entry.settle.signal.addEventListener("abort", () => timer.abort("settled"));
        sleep(timeoutMs, timer.signal).then(() => this.cancelPending(id, new McpTimeoutError(timeoutMs), entry.cancellable, "Request timed out"), () => { });
    }
    /** Reject the request and, when `notifyServer`, tell the server with notifications/cancelled (pi). */
    cancelPending(id, error, notifyServer, reason) {
        if (!this.pending.has(id))
            return;
        this.failPending(id, error);
        if (!notifyServer || !this.transport || this.state === "closed")
            return;
        this.transport
            .send({
            jsonrpc: "2.0",
            method: "notifications/cancelled",
            params: { requestId: id, ...(reason ? { reason } : {}) },
        })
            .catch((sendError) => this.emitError(sendError));
    }
    async notifyInternal(method, params, allowConnecting) {
        await this.requireTransport(allowConnecting).send({
            jsonrpc: "2.0",
            method,
            ...(params === undefined ? {} : { params }),
        });
    }
    requireTransport(allowConnecting) {
        if (this.transport && (this.state === "connected" || (allowConnecting && this.state === "connecting"))) {
            return this.transport;
        }
        throw new McpConnectionClosedError(`MCP client is ${this.state}`);
    }
    handleMessage(message) {
        if (isJsonRpcResponse(message))
            this.handleResponse(message);
        else if (isJsonRpcRequest(message))
            void this.handleRequest(message);
        else if (isJsonRpcNotification(message))
            this.handleNotification(message.method, message.params);
        else
            this.emitError(invalid("Received invalid JSON-RPC message"));
    }
    handleResponse(message) {
        const entry = this.pending.get(message.id);
        if (!entry) {
            if (this.ignoredResponses.delete(message.id))
                return;
            this.emitError(new Error(`Received response for unknown MCP request ${String(message.id)}`));
            return;
        }
        this.removePending(message.id, entry);
        if ("error" in message)
            entry.reject(new McpError(message.error.code, message.error.message, message.error.data));
        else
            entry.resolve(message.result);
    }
    /** Answer a server request through its handler, or method-not-found so the server never waits forever. */
    async handleRequest(message) {
        const transport = this.transport;
        if (!transport)
            return;
        // A 2026-07-28 server sends no requests (input comes through input_required results).
        const handler = this.eraValue === "modern" ? undefined : this.requestHandlers.get(message.method);
        let reply;
        if (!handler) {
            reply = {
                jsonrpc: "2.0",
                id: message.id,
                error: { code: JSON_RPC_ERROR_CODES.methodNotFound, message: `Method not found: ${message.method}` },
            };
        }
        else {
            const cancel = new AbortHandle();
            this.incoming.set(message.id, cancel);
            try {
                reply = {
                    jsonrpc: "2.0",
                    id: message.id,
                    result: (await handler(message.params, { signal: cancel.signal })) ?? {},
                };
            }
            catch (error) {
                // Handlers throw McpError for protocol errors such as invalid params (pi).
                reply = {
                    jsonrpc: "2.0",
                    id: message.id,
                    error: error instanceof McpError
                        ? { code: error.code, message: error.message, ...(error.data !== undefined ? { data: error.data } : {}) }
                        : { code: JSON_RPC_ERROR_CODES.internalError, message: toError(error).message },
                };
            }
            finally {
                this.incoming.delete(message.id);
            }
            // The receiver of a cancellation does not answer the cancelled request (MCP).
            if (cancel.isAborted)
                return;
        }
        await transport.send(reply).catch((error) => this.emitError(error));
    }
    handleNotification(method, params) {
        if (method === "notifications/progress")
            this.handleProgress(params);
        else if (method === "notifications/cancelled" && isObject(params) && isJsonRpcIdValue(params.requestId)) {
            this.incoming.get(params.requestId)?.abort(params.reason ?? "cancelled");
            // A 2026-07-28 server tears a subscription down this way: it ended on purpose.
            const entry = this.pending.get(params.requestId);
            if (entry?.listen) {
                this.removePending(params.requestId, entry);
                entry.resolve(undefined);
            }
        }
        for (const listener of this.notificationListeners.get(method) ?? []) {
            try {
                listener(params);
            }
            catch (error) {
                this.emitError(error);
            }
        }
    }
    /** Progress for a request that asked for it: re-arm its timeout, then report (pi). */
    handleProgress(params) {
        if (!isObject(params) || !isJsonRpcIdValue(params.progressToken) || typeof params.progress !== "number")
            return;
        const id = this.progressRequests.get(params.progressToken);
        const entry = id === undefined ? undefined : this.pending.get(id);
        if (id === undefined || !entry)
            return;
        this.armTimeout(id, entry);
        try {
            // notifications/progress params as the server sent them (progressToken matched above).
            entry.onProgress?.(params);
        }
        catch (error) {
            this.emitError(error);
        }
    }
    failPending(id, error) {
        const entry = this.pending.get(id);
        if (!entry)
            return;
        this.removePending(id, entry);
        entry.reject(error);
    }
    removePending(id, entry) {
        this.pending.delete(id);
        if (entry.progressToken !== undefined)
            this.progressRequests.delete(entry.progressToken);
        entry.signal?.removeEventListener("abort", entry.onAbort);
        entry.settle.abort("settled");
    }
    /** Idempotent: rejects in-flight requests and flips the state. */
    markClosed(error) {
        const wasClosed = this.state === "closed";
        this.state = "closed";
        for (const [id, entry] of this.pending) {
            this.removePending(id, entry);
            entry.reject(error);
        }
        // Nobody can receive the answers any more: close the dialogs waiting on them.
        for (const cancel of this.incoming.values())
            cancel.abort("closed");
        if (wasClosed)
            return;
        for (const listener of [...this.closeListeners]) {
            try {
                listener();
            }
            catch (listenerError) {
                this.emitError(listenerError);
            }
        }
    }
    emitError(error) {
        const normalized = toError(error);
        for (const listener of this.errorListeners)
            listener(normalized);
    }
}
