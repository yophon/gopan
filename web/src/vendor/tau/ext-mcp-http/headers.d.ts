/** A header value as the spec sends it: plain when safe, otherwise the Base64 sentinel. */
export declare function encodeHeaderValue(value: string): string;
/** Mcp-Method, and Mcp-Name for tools/call, prompts/get and resources/read. */
export declare function standardHeaders(method: string, params: unknown): Record<string, string>;
/** A parameter mirrored into a header: the chain of `properties` keys leading to it. */
export interface McpParamHeader {
    path: string[];
    name: string;
}
/**
 * The `x-mcp-header` parameters of a tool's input schema, or why the tool is invalid
 * (spec: the client excludes such a tool). Annotations must sit on string, integer or
 * boolean properties reachable from the root through `properties` keys alone, and their
 * names must be RFC 9110 tokens, unique regardless of case.
 */
export declare function paramHeaders(schema: unknown): McpParamHeader[] | {
    error: string;
};
/** The Mcp-Param-{Name} headers for one call; a missing or null value sends no header. */
export declare function mirroredParams(params: readonly McpParamHeader[], args: unknown): Record<string, string>;
