import { type Platform } from "@yophon/tau-kernel";
import { type JsonRpcMessage } from "./jsonrpc.js";
import { type McpTransport, type McpTransportSendOptions, TransportEvents } from "./transport.js";
export declare class McpHttpError extends Error {
    readonly status: number;
    constructor(status: number, message: string);
}
/** 404 on a request that carried a session id: the server lost or expired the session. */
export declare class McpSessionExpiredError extends McpHttpError {
    constructor();
}
export interface StreamableHttpTransportOptions {
    url: string;
    platform: Platform;
    /** Extra request headers, e.g. `{ Authorization: "Bearer <token>" }`. */
    headers?: Record<string, string>;
    /**
     * Keep a GET stream open for server notifications (tools/list_changed) once the
     * session is initialized; reopened with backoff when it drops (needs Platform.sleep).
     */
    openGetStream?: boolean;
    /** OAuth (P32b): a bearer token per request, and a refresh after a 401 (or a 403 insufficient_scope) before one retry (pi AuthProvider). */
    auth?: McpAuth;
}
/** Supplies bearer tokens and may refresh them after a 401 (pi's AuthProvider, without its fetch). */
export interface McpAuth {
    token(): Promise<string | undefined>;
    /**
     * Refresh after a 401, or a 403 whose challenge reports `insufficient_scope` (step-up, P48), or
     * throw (for example "sign in again"); the request is retried once when it returns.
     */
    onUnauthorized?(context: {
        response: {
            status: number;
            headers?: {
                get(name: string): string | null | undefined;
            };
        };
        serverUrl: string;
        /** The token the rejected request carried. */
        token?: string;
    }): Promise<void>;
}
export declare class StreamableHttpTransport extends TransportEvents implements McpTransport {
    readonly kind: "http";
    readonly options: Readonly<StreamableHttpTransportOptions>;
    private readonly lifetime;
    private started;
    private closed;
    private sessionIdValue;
    private protocolVersion;
    constructor(options: StreamableHttpTransportOptions);
    get sessionId(): string | undefined;
    start(): Promise<void>;
    setProtocolVersion(version: string): void;
    send(message: JsonRpcMessage, options?: McpTransportSendOptions): Promise<void>;
    private sendOnce;
    /** Keep the GET stream open, reconnecting with backoff when it drops (pi runGetStream). */
    private runGetStream;
    /** Open a GET SSE stream, resuming after `lastEventId`. Resolves undefined when the server answers 405. */
    private openSseStream;
    close(): Promise<void>;
    /** Emit every JSON-RPC message of a JSON body (one message or a batch). */
    private emitJson;
    /**
     * A body without a content type (adapters without response headers): JSON first,
     * then SSE framing. Returns false when the text is JSON, so the caller parses it.
     */
    private emitSse;
    private emitEventData;
    /**
     * Read one SSE stream until it ends, `signal` aborts or `until` holds, delivering its
     * JSON-RPC messages and noting event ids and the retry delay in `cursor`. Events
     * without data prime resumption; other event types are not JSON-RPC (pi).
     */
    private readSse;
    /**
     * Read the SSE reply to one request. When the stream ends or breaks before the response
     * arrives and the server assigned event ids, resume it with GET and Last-Event-ID (pi);
     * otherwise, or once resuming fails, only this request fails. With `onStreamBroken`
     * (MCP 2026-07-28, no resumption) the caller is told instead.
     */
    private consumeResponseStream;
}
