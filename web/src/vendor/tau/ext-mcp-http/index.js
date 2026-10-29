import { StreamableHttpTransport } from "./http.js";
import { createMcpServers } from "./servers.js";
export { MCP_MODERN_PROTOCOL_VERSION, MCP_PROTOCOL_VERSION, McpClient, McpSubscriptionError, SUPPORTED_PROTOCOL_VERSIONS, } from "./client.js";
export { ELICITATION_CAPABILITY, elicitationForm, elicitWithUi, MAX_URL_ARGUMENT, parseElicitation, requiredUrlElicitations, URL_ELICITATION_REQUIRED, urlArgumentLength, urlHost, } from "./elicitation.js";
export { configuredExposures, createMcpResultSchema, getMcpToolExposure, MAX_SERVERS_SECTION_CHARS, MCP_SERVERS_SECTION, reachesServer, renderServersSection, toToolExposure, } from "./exposure.js";
export { encodeHeaderValue, mirroredParams, paramHeaders, standardHeaders } from "./headers.js";
export { McpHttpError, McpSessionExpiredError, StreamableHttpTransport, } from "./http.js";
export { isJsonRpcNotification, isJsonRpcRequest, isJsonRpcResponse, JSON_RPC_ERROR_CODES, MCP_ERROR_CODES, MCP_META, McpAbortError, McpConnectionClosedError, McpError, McpProbeExitedError, McpStreamBrokenError, McpTimeoutError, parseJsonRpcMessage, } from "./jsonrpc.js";
export { createMentionProvider, findMentions, MENTION_LIMIT, mentionBlock, mentionItems, mentionValue, } from "./mentions.js";
export { createMcpToolName, MAX_TOOL_NAME_LENGTH, mcpNamespace, sha256Hex } from "./naming.js";
export { mcpPromptCommandName, promptArguments, promptCommandDescription, promptMessageContent, promptUsage, } from "./prompts.js";
export { createMcpResourceTools, isMcpAppResource, LIST_MCP_RESOURCE_TEMPLATES_TOOL, LIST_MCP_RESOURCES_TOOL, READ_MCP_RESOURCE_TOOL, RESOURCE_TOOL_NAMES, } from "./resources.js";
export { convertMcpResult, limitMcpContent, MCP_OUTPUT_MAX_BYTES, toModelContent, } from "./result.js";
export { createMcpServers, declaredToolRisk, progressLine, WAIT_FOR_MCP_SERVERS_TOOL, } from "./servers.js";
export { TransportEvents, } from "./transport.js";
const CLIENT_INFO = { name: "tau-ext-mcp-http", version: "0.4.0" };
export function createHttpMcpExtension(options) {
    const { platform } = options;
    const handle = createMcpServers({
        servers: options.servers,
        platform,
        clientInfo: CLIENT_INFO,
        onStatus: options.onStatus,
        createTransport: (config) => {
            if (!config.url)
                throw new Error(`MCP server "${config.name}" must define url`);
            return new StreamableHttpTransport({
                url: config.url,
                platform,
                headers: config.headers,
                openGetStream: options.serverNotifications === true,
            });
        },
        // The same switch holds a 2026-07-28 server's subscriptions/listen stream open (P47).
        listChanged: () => options.serverNotifications === true,
    });
    return {
        extension: handle.extension,
        connect: async () => {
            await handle.connect();
        },
    };
}
