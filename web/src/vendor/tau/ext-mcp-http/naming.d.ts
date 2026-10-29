/** Provider tool names are limited to 64 characters of `[A-Za-z0-9_-]`. */
export declare const MAX_TOOL_NAME_LENGTH = 64;
/** Namespace of a server's tools: `mcp__<server>`, with `-` as `_`. */
export declare function mcpNamespace(server: string): string;
/**
 * `mcp__<server>__<tool>`, sanitized and shortened with a hash suffix when too long.
 * Like Codex, everything but `[A-Za-z0-9_]` becomes `_`, so the name is also the
 * identifier codemode scripts call it by. `isTaken` reports names used by a
 * different MCP tool: sanitizing can map two tools to one name (`a-b` and `a_b`),
 * which then get the hash suffix.
 */
export declare function createMcpToolName(server: string, tool: string, isTaken?: (name: string) => boolean): string;
/** SHA-256 of the UTF-8 encoding of `text`, as lowercase hex (FIPS 180-4). */
export declare function sha256Hex(text: string): string;
