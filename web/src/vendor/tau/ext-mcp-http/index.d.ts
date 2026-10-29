import type { Extension, Platform } from "@yophon/tau-kernel";
import { type McpServerConfig, type McpServerStatus } from "./servers.js";
export { type CallToolResult, type InitializeResult, MCP_MODERN_PROTOCOL_VERSION, MCP_PROTOCOL_VERSION, McpClient, type McpClientOptions, type McpConnectOptions, type McpContentBlock, type McpGetPromptResult, type McpProgress, type McpPrompt, type McpPromptArgument, type McpProtocolEra, type McpProtocolNegotiation, type McpReadResourceResult, type McpRequestHandler, type McpRequestOptions, type McpResource, type McpResourceContents, type McpResourceTemplate, type McpRoot, type McpSubscription, type McpSubscriptionEnd, McpSubscriptionError, type McpSubscriptionFilter, type McpToolInfo, SUPPORTED_PROTOCOL_VERSIONS, } from "./client.js";
export { ELICITATION_CAPABILITY, elicitationForm, elicitWithUi, MAX_URL_ARGUMENT, type McpElicitation, type McpElicitHandler, type McpElicitResult, parseElicitation, requiredUrlElicitations, URL_ELICITATION_REQUIRED, urlArgumentLength, urlHost, } from "./elicitation.js";
export { configuredExposures, createMcpResultSchema, getMcpToolExposure, MAX_SERVERS_SECTION_CHARS, MCP_SERVERS_SECTION, type McpExposure, type McpExposureConfig, type McpServerListing, reachesServer, renderServersSection, toToolExposure, } from "./exposure.js";
export { encodeHeaderValue, type McpParamHeader, mirroredParams, paramHeaders, standardHeaders } from "./headers.js";
export { type McpAuth, McpHttpError, McpSessionExpiredError, StreamableHttpTransport, type StreamableHttpTransportOptions, } from "./http.js";
export { isJsonRpcNotification, isJsonRpcRequest, isJsonRpcResponse, JSON_RPC_ERROR_CODES, type JsonRpcId, type JsonRpcMessage, type JsonRpcNotification, type JsonRpcRequest, type JsonRpcResponse, MCP_ERROR_CODES, MCP_META, McpAbortError, McpConnectionClosedError, McpError, McpProbeExitedError, McpStreamBrokenError, McpTimeoutError, parseJsonRpcMessage, } from "./jsonrpc.js";
export { createMentionProvider, findMentions, MENTION_LIMIT, type MentionResource, mentionBlock, mentionItems, mentionValue, } from "./mentions.js";
export { createMcpToolName, MAX_TOOL_NAME_LENGTH, mcpNamespace, sha256Hex } from "./naming.js";
export { mcpPromptCommandName, promptArguments, promptCommandDescription, promptMessageContent, promptUsage, } from "./prompts.js";
export { createMcpResourceTools, isMcpAppResource, LIST_MCP_RESOURCE_TEMPLATES_TOOL, LIST_MCP_RESOURCES_TOOL, type McpResourceServer, type McpResourceToolsOptions, READ_MCP_RESOURCE_TOOL, RESOURCE_TOOL_NAMES, } from "./resources.js";
export { convertMcpResult, limitMcpContent, MCP_OUTPUT_MAX_BYTES, type McpContentOptions, type McpModelContent, toModelContent, } from "./result.js";
export { createMcpServers, declaredToolRisk, type McpServerConfig, type McpServerStatus, type McpServersHandle, type McpServersOptions, progressLine, WAIT_FOR_MCP_SERVERS_TOOL, } from "./servers.js";
export { type McpTransport, type McpTransportCloseListener, type McpTransportErrorListener, type McpTransportMessageListener, type McpTransportSendOptions, TransportEvents, } from "./transport.js";
export interface HttpMcpServerConfig extends Pick<McpServerConfig, "name" | "headers" | "timeoutMs" | "riskFromAnnotations" | "exposure" | "toolExposure" | "description"> {
    /** Streamable HTTP endpoint URL. */
    url: string;
}
export type HttpMcpStatus = McpServerStatus;
export interface HttpMcpOptions {
    servers: HttpMcpServerConfig[];
    /**
     * All HTTP goes through this seam. Injected explicitly (not read from
     * ExtensionContext) because connect() must be callable outside any event —
     * reconnect buttons, host entry startup — where no context exists.
     */
    platform: Platform;
    onStatus?: (status: HttpMcpStatus) => void;
    /**
     * Keep a GET stream open per server for notifications (tools/list_changed).
     * Off by default: mobile hosts keep no idle connection; tools are listed at connect.
     */
    serverNotifications?: boolean;
}
export interface HttpMcpHandle {
    extension: Extension;
    /**
     * Idempotent connect/reconnect: initializes every not-yet-connected server,
     * registers its tools, and never rejects on server failure (offline is
     * reported via onStatus). Call after ExtensionRegistry.load; session_start
     * also triggers it on hosts that emit that event.
     */
    connect: () => Promise<void>;
}
export declare function createHttpMcpExtension(options: HttpMcpOptions): HttpMcpHandle;
