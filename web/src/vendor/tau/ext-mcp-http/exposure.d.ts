import type { JsonSchema, ToolExposure } from "@yophon/tau-kernel";
/**
 * How a server's tools are reached (pi McpExposure):
 * - `direct`: declared to the model.
 * - `codemode`: called from codemode scripts, which find them with searchTools().
 * - `deferred`: loaded with tool_search.
 * - `hidden`: registered but unreachable.
 */
export type McpExposure = "direct" | "codemode" | "deferred" | "hidden";
export interface McpExposureConfig {
    /** Exposure of the server's tools. Default: `direct` (deviation: pi defaults to `codemode`). */
    exposure?: McpExposure;
    /** Per-tool overrides: an exact tool name wins, else the first `*` pattern that matches. */
    toolExposure?: Record<string, McpExposure>;
}
/** The exposure of one MCP tool (pi getMcpToolExposure, with tau's `direct` default). */
export declare function getMcpToolExposure(config: McpExposureConfig, toolName: string): McpExposure;
/**
 * Tool exposure of an MCP exposure (pi toToolExposure): `codemode` and `deferred`
 * both leave tools out of the codemode description (pi 0.99.2) — scripts find
 * them with searchTools(), tool_search loads them.
 */
export declare function toToolExposure(exposure: McpExposure): ToolExposure;
/** Exposures a server's tools can have, known from its config before it connects. */
export declare function configuredExposures(config: McpExposureConfig): Set<McpExposure>;
/**
 * Output schema of every MCP tool (pi createMcpResultSchema, 1.2.0): the
 * CallToolResult scripts receive, with the tool's own output schema as
 * `structuredContent`. Without one, structuredContent is any JSON value.
 */
export declare function createMcpResultSchema(structuredContentSchema: Record<string, unknown> | undefined): JsonSchema;
/** Name of the system prompt section that lists the servers whose tools are not declared (pi). */
export declare const MCP_SERVERS_SECTION = "mcp_servers";
/** Characters of the whole section; descriptions shrink, then the last servers are counted only. */
export declare const MAX_SERVERS_SECTION_CHARS = 4096;
/** What the `mcp_servers` section needs of a server. */
export interface McpServerListing {
    name: string;
    config: McpExposureConfig & {
        description?: string;
    };
    instructions?: string;
}
/**
 * The `mcp_servers` section (pi renderServersSection): every server with codemode
 * or deferred tools, how its tools are reached, and a one-line summary. The model
 * learns of the servers from it, since neither codemode nor tool_search lists
 * them. Undefined when there are no such servers.
 */
export declare function renderServersSection(servers: readonly McpServerListing[]): string | undefined;
/**
 * Whether a call that reaches `reach` (Tool.reachesTools) needs the server: it
 * reaches every tool, or one of its words contains the server's namespace (pi).
 */
export declare function reachesServer(reach: "all" | readonly string[], server: string): boolean;
