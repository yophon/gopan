import { type ImageContent, type Platform, type TextContent } from "@yophon/tau-kernel";
import type { CallToolResult, McpContentBlock } from "./client.js";
/** Model-facing text of an MCP result beyond this is cut in the middle (pi MCP_OUTPUT_MAX_BYTES). */
export declare const MCP_OUTPUT_MAX_BYTES: number;
export declare const READ_MCP_RESOURCE_TOOL = "read_mcp_resource";
export type McpModelContent = TextContent | ImageContent;
export interface McpContentOptions {
    /** The server the content came from (for the read_mcp_resource hint). */
    server: string;
    /** The server offers resources: resource links say how to read them (pi). */
    readableResources?: boolean;
    /** Pass images to the model (default true, pi). False keeps the placeholder text tau used before P43 (the gateway). */
    images?: boolean;
    /** Save a binary resource (base64) where the model can read it and return its path; throws when it cannot. Absent: not saved. */
    saveBinary?: (base64: string, extension: string) => string;
    /** Decodes text-typed blobs; without it they are treated as other binary resources. */
    createUtf8Decoder?: Platform["createUtf8Decoder"];
}
/** Model-facing content of a server's content blocks, before the output limit (pi toModelContent). */
export declare function toModelContent(blocks: readonly McpContentBlock[], options: McpContentOptions): McpModelContent[];
export declare function textOf(content: readonly McpModelContent[]): string;
/**
 * Model-facing content of a tool result (pi convertMcpResult). Without content blocks the
 * structured content stands in as JSON, as servers should, but do not always, mirror it as text.
 */
export declare function convertMcpResult(result: CallToolResult, tool: string, options: McpContentOptions): McpModelContent[];
/**
 * The output limit (pi limitMcpContent): text over MCP_OUTPUT_MAX_BYTES is cut in the
 * middle with its full text saved by `where`; images are kept. Empty content says so.
 */
export declare function limitMcpContent(content: McpModelContent[], where: (full: string) => string): McpModelContent[];
