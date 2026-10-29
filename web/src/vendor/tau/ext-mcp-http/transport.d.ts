import type { TauAbortSignal } from "@yophon/tau-kernel";
import { type JsonRpcMessage } from "./jsonrpc.js";
export type McpTransportMessageListener = (message: JsonRpcMessage) => void;
export type McpTransportErrorListener = (error: Error) => void;
export type McpTransportCloseListener = () => void;
export interface McpTransportSendOptions {
    /**
     * Aborted once the sender no longer waits for a reply (the request was answered,
     * timed out, was aborted, or the client closed). A transport must not dispatch the
     * message after that; it does not undo a message already dispatched.
     */
    signal?: TauAbortSignal;
    /** Headers for this message only (MCP 2026-07-28 `Mcp-Param-*`); HTTP transports send them. */
    headers?: Record<string, string>;
    /**
     * Given (2026-07-28 requests), a response stream that ends or breaks before the response
     * calls this instead of being resumed or failing the request: the client reissues it.
     */
    onStreamBroken?: (reason: string) => void;
}
export interface McpTransport {
    /**
     * "http": a 2026-07-28 request is cancelled by closing its stream, and a probe that times
     * out means the server is down. Anything else (stdio) sends notifications/cancelled, and a
     * silent probe means a legacy server.
     */
    readonly kind?: "http" | "stdio";
    start(): Promise<void>;
    send(message: JsonRpcMessage, options?: McpTransportSendOptions): Promise<void>;
    close(): Promise<void>;
    onMessage(listener: McpTransportMessageListener): () => void;
    onError(listener: McpTransportErrorListener): () => void;
    onClose(listener: McpTransportCloseListener): () => void;
    /** The protocol version the server chose in `initialize`. */
    setProtocolVersion?(version: string): void;
}
/** Listener bookkeeping shared by transports. `emitClose` fires at most once per transport. */
export declare abstract class TransportEvents {
    private messageListeners;
    private errorListeners;
    private closeListeners;
    private closeEmitted;
    onMessage(listener: McpTransportMessageListener): () => void;
    onError(listener: McpTransportErrorListener): () => void;
    onClose(listener: McpTransportCloseListener): () => void;
    protected emitMessage(message: JsonRpcMessage): void;
    protected emitError(error: unknown): void;
    protected emitClose(): void;
}
