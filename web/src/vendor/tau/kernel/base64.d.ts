export declare function encodeBase64(bytes: Uint8Array): string;
/** Bytes of base64 text (P43: MCP blobs, tool result images); whitespace and padding are skipped, other characters throw. */
export declare function decodeBase64(text: string): Uint8Array;
