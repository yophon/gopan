import type { JsonObject, ToolDefinition } from "./messages.js";
import type { ChatTransport } from "./openai.js";
/** The system prompt section that declares the tools and how to call them. */
export declare function toolsAsPrompt(tools: readonly ToolDefinition[]): string;
/** The tool calls written in a reply, and the reply without them. */
export declare function parseToolCallBlocks(text: string): {
    calls: Array<{
        name: string;
        arguments: JsonObject;
    }>;
    text: string;
};
/** Wrap a transport for a model without native tool calling. */
export declare function withPromptToolShim(inner: ChatTransport): ChatTransport;
