export type JsonRpcId = string | number;
export interface JsonRpcRequest {
    jsonrpc: "2.0";
    id: JsonRpcId;
    method: string;
    params?: unknown;
}
export interface JsonRpcNotification {
    jsonrpc: "2.0";
    method: string;
    params?: unknown;
}
export interface JsonRpcErrorObject {
    code: number;
    message: string;
    data?: unknown;
}
export interface JsonRpcSuccessResponse {
    jsonrpc: "2.0";
    id: JsonRpcId;
    result: unknown;
}
export interface JsonRpcErrorResponse {
    jsonrpc: "2.0";
    id: JsonRpcId;
    error: JsonRpcErrorObject;
}
export type JsonRpcResponse = JsonRpcSuccessResponse | JsonRpcErrorResponse;
export type JsonRpcMessage = JsonRpcRequest | JsonRpcNotification | JsonRpcResponse;
export declare const JSON_RPC_ERROR_CODES: {
    readonly parseError: -32700;
    readonly invalidRequest: -32600;
    readonly methodNotFound: -32601;
    readonly invalidParams: -32602;
    readonly internalError: -32603;
};
/** Error codes MCP 2026-07-28 reserves (-32020 to -32099); any of them marks a modern server. */
export declare const MCP_ERROR_CODES: {
    readonly headerMismatch: -32020;
    readonly missingRequiredClientCapability: -32021;
    readonly unsupportedProtocolVersion: -32022;
};
/** `_meta` keys MCP 2026-07-28 reserves for the per-request envelope and its replies. */
export declare const MCP_META: {
    readonly protocolVersion: "io.modelcontextprotocol/protocolVersion";
    readonly clientInfo: "io.modelcontextprotocol/clientInfo";
    readonly clientCapabilities: "io.modelcontextprotocol/clientCapabilities";
    readonly serverInfo: "io.modelcontextprotocol/serverInfo";
    readonly subscriptionId: "io.modelcontextprotocol/subscriptionId";
};
/** The protocol version a 2026-07-28 request declares in its envelope; undefined for anything else. */
export declare function envelopeVersion(message: unknown): string | undefined;
export declare class McpError extends Error {
    readonly code: number;
    readonly data: unknown;
    constructor(code: number, message: string, data?: unknown);
}
export declare class McpConnectionClosedError extends Error {
    constructor(message?: string);
}
export declare class McpTimeoutError extends Error {
    readonly timeoutMs: number;
    constructor(timeoutMs: number);
}
export declare class McpAbortError extends Error {
    constructor(message?: string);
}
/**
 * A stdio server left in no state to shake hands by the 2026-07-28 probe (it exited, or
 * initialize failed after it): connect again on a fresh process with era "legacy" (P47).
 */
export declare class McpProbeExitedError extends Error {
    constructor(message?: string);
}
/** A 2026-07-28 response stream that broke before its response (reissued once, then this). */
export declare class McpStreamBrokenError extends Error {
    constructor(reason: string);
}
export declare function isObject(value: unknown): value is Record<string, unknown>;
export declare function toError(value: unknown): Error;
export declare function isJsonRpcRequest(message: unknown): message is JsonRpcRequest;
export declare function isJsonRpcNotification(message: unknown): message is JsonRpcNotification;
export declare function isJsonRpcResponse(message: unknown): message is JsonRpcResponse;
export declare function parseJsonRpcMessage(value: unknown): JsonRpcMessage;
