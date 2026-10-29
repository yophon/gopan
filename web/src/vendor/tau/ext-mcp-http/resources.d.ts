import type { Tool, ToolExposure } from "@yophon/tau-kernel";
import type { McpClient, McpReadResourceResult, McpRequestOptions, McpResource, McpResourceTemplate } from "./client.js";
import { type McpContentOptions, READ_MCP_RESOURCE_TOOL } from "./result.js";
export declare const LIST_MCP_RESOURCES_TOOL = "list_mcp_resources";
export declare const LIST_MCP_RESOURCE_TEMPLATES_TOOL = "list_mcp_resource_templates";
export { READ_MCP_RESOURCE_TOOL };
export declare const RESOURCE_TOOL_NAMES: ReadonlySet<string>;
/** A connected server that offers resources. */
export interface McpResourceServer {
    name: string;
    timeoutMs: number;
    resourcesPage(cursor: string | undefined, options: McpRequestOptions): Promise<{
        resources: McpResource[];
        nextCursor?: string;
    }>;
    resourceTemplatesPage(cursor: string | undefined, options: McpRequestOptions): Promise<{
        resourceTemplates: McpResourceTemplate[];
        nextCursor?: string;
    }>;
    allResources(options: McpRequestOptions): Promise<McpResource[]>;
    allResourceTemplates(options: McpRequestOptions): Promise<McpResourceTemplate[]>;
    readResource(uri: string, options: McpRequestOptions): Promise<McpReadResourceResult>;
}
/** MCP App user interfaces, which only hosts that render them can use (pi). */
export declare function isMcpAppResource(item: {
    uri?: unknown;
    uriTemplate?: unknown;
    mimeType?: unknown;
}): boolean;
export interface McpResourceToolsOptions {
    /** How the tools are reached: the widest exposure of the servers they reach. */
    exposure: ToolExposure;
    /** The servers whose resources they reach, at call time. */
    servers: () => readonly McpResourceServer[];
    /** Conversion of read contents (images, binary resources), as for tool results. */
    content: Omit<McpContentOptions, "server" | "readableResources">;
    /** Where the full text of a truncated result went. */
    where: (full: string) => string;
}
/** The three resource tools (pi createMcpResourceToolDefinitions). */
export declare function createMcpResourceTools(options: McpResourceToolsOptions): Tool[];
/** A connected server as the resource tools see it (P62: moved here from servers.ts). */
export declare function mcpResourceServer(name: string, timeoutMs: number, run: <T>(call: (client: McpClient) => Promise<T>) => Promise<T>): McpResourceServer;
