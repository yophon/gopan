import type { ImageProcessor } from "./capabilities.js";
import type { ImageContent, TextContent } from "./messages.js";
/**
 * Images a tool returned, converted and shrunk once as they enter the log (P43, pi
 * normalizeToolResultImages). `read` and `@file` process their images already, but tools that
 * produce images themselves (MCP servers, extensions, screenshot tools) hand back arbitrary
 * base64 that would go into every later request; an oversized image makes the provider reject
 * the whole conversation. Runs after the tool_result handlers, so images they add count too.
 * An image the processor cannot handle is kept, as the tool produced it (pi). Returns the
 * original array when nothing changed.
 */
export declare function normalizeToolResultImages(content: (TextContent | ImageContent)[], images: ImageProcessor): Promise<(TextContent | ImageContent)[]>;
