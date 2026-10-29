// JSON-RPC 2.0 message shapes and MCP client errors (pi-mcp protocol/jsonrpc.ts).
export const JSON_RPC_ERROR_CODES = {
    parseError: -32700,
    invalidRequest: -32600,
    methodNotFound: -32601,
    invalidParams: -32602,
    internalError: -32603,
};
/** Error codes MCP 2026-07-28 reserves (-32020 to -32099); any of them marks a modern server. */
export const MCP_ERROR_CODES = {
    headerMismatch: -32020,
    missingRequiredClientCapability: -32021,
    unsupportedProtocolVersion: -32022,
};
/** `_meta` keys MCP 2026-07-28 reserves for the per-request envelope and its replies. */
export const MCP_META = {
    protocolVersion: "io.modelcontextprotocol/protocolVersion",
    clientInfo: "io.modelcontextprotocol/clientInfo",
    clientCapabilities: "io.modelcontextprotocol/clientCapabilities",
    serverInfo: "io.modelcontextprotocol/serverInfo",
    subscriptionId: "io.modelcontextprotocol/subscriptionId",
};
/** The protocol version a 2026-07-28 request declares in its envelope; undefined for anything else. */
export function envelopeVersion(message) {
    if (!isJsonRpcRequest(message) || !isObject(message.params) || !isObject(message.params._meta))
        return undefined;
    const version = message.params._meta[MCP_META.protocolVersion];
    return typeof version === "string" ? version : undefined;
}
export class McpError extends Error {
    code;
    data;
    constructor(code, message, data) {
        super(message);
        this.name = "McpError";
        this.code = code;
        this.data = data;
    }
}
export class McpConnectionClosedError extends Error {
    constructor(message = "MCP connection closed") {
        super(message);
        this.name = "McpConnectionClosedError";
    }
}
export class McpTimeoutError extends Error {
    timeoutMs;
    constructor(timeoutMs) {
        super(`MCP request timed out after ${timeoutMs}ms`);
        this.name = "McpTimeoutError";
        this.timeoutMs = timeoutMs;
    }
}
export class McpAbortError extends Error {
    constructor(message = "MCP request aborted") {
        super(message);
        this.name = "AbortError";
    }
}
/**
 * A stdio server left in no state to shake hands by the 2026-07-28 probe (it exited, or
 * initialize failed after it): connect again on a fresh process with era "legacy" (P47).
 */
export class McpProbeExitedError extends Error {
    constructor(message = "MCP server exited when asked for protocol 2026-07-28") {
        super(message);
        this.name = "McpProbeExitedError";
    }
}
/** A 2026-07-28 response stream that broke before its response (reissued once, then this). */
export class McpStreamBrokenError extends Error {
    constructor(reason) {
        super(`MCP response stream failed: ${reason}`);
        this.name = "McpStreamBrokenError";
    }
}
export function isObject(value) {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}
export function toError(value) {
    return value instanceof Error ? value : new Error(String(value));
}
function isJsonRpcId(value) {
    return typeof value === "string" || (typeof value === "number" && Number.isFinite(value));
}
export function isJsonRpcRequest(message) {
    return (isObject(message) && message.jsonrpc === "2.0" && isJsonRpcId(message.id) && typeof message.method === "string");
}
export function isJsonRpcNotification(message) {
    return isObject(message) && message.jsonrpc === "2.0" && !("id" in message) && typeof message.method === "string";
}
export function isJsonRpcResponse(message) {
    if (!isObject(message) || message.jsonrpc !== "2.0" || !isJsonRpcId(message.id))
        return false;
    if ("result" in message)
        return !("error" in message);
    if (!("error" in message) || !isObject(message.error))
        return false;
    return typeof message.error.code === "number" && typeof message.error.message === "string";
}
export function parseJsonRpcMessage(value) {
    if (isJsonRpcRequest(value) || isJsonRpcNotification(value) || isJsonRpcResponse(value))
        return value;
    throw new McpError(JSON_RPC_ERROR_CODES.invalidRequest, "Invalid JSON-RPC message");
}
