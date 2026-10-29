import { collapseSystemMessages, getCurrentTools, getSystemMessageText } from "./projection.js";
const TOOL_CALL_BLOCK = /```tool_call\s*\n([\s\S]*?)```/g;
const UNCLOSED_TOOL_CALL = /```tool_call\s*\n[\s\S]*$/;
/** The system prompt section that declares the tools and how to call them. */
export function toolsAsPrompt(tools) {
    if (tools.length === 0)
        return "";
    return [
        "You can call the following tools. To call one, write a fenced code block with the language `tool_call`",
        'containing a single JSON object: {"name": "<tool name>", "arguments": {<arguments>}}. Write nothing else',
        "for the call. Several tool_call blocks in one reply run in parallel. When no tool is needed, answer normally.",
        "",
        "Tools:",
        ...tools.map((tool) => `- ${tool.name}: ${tool.description}\n  Arguments (JSON Schema): ${JSON.stringify(tool.parameters)}`),
    ].join("\n");
}
/** The tool calls written in a reply, and the reply without them. */
export function parseToolCallBlocks(text) {
    const calls = [];
    for (const match of text.matchAll(TOOL_CALL_BLOCK)) {
        try {
            const parsed = JSON.parse(match[1]?.trim() ?? "");
            if (typeof parsed?.name !== "string")
                continue;
            const args = parsed.arguments;
            calls.push({
                name: parsed.name,
                arguments: args !== null && typeof args === "object" && !Array.isArray(args) ? args : {},
            });
        }
        catch {
            // A block that does not parse is dropped.
        }
    }
    return { calls, text: text.replace(TOOL_CALL_BLOCK, "").replace(UNCLOSED_TOOL_CALL, "").trim() };
}
const callBlock = (call) => `\`\`\`tool_call\n${JSON.stringify({ name: call.name, arguments: call.arguments })}\n\`\`\``;
/** The transcript as a model without tool calling reads it: one system prompt, calls and results as text. */
function plainTranscript(messages) {
    const tools = getCurrentTools(messages);
    const collapsed = collapseSystemMessages(messages);
    const head = collapsed[0]?.role === "system" ? collapsed[0] : undefined;
    const prompt = [head ? getSystemMessageText(head) : "", toolsAsPrompt(tools)]
        .filter((part) => part !== "")
        .join("\n\n");
    const rest = (head ? collapsed.slice(1) : collapsed).map((message) => {
        if (message.role === "assistant") {
            return {
                ...message,
                content: message.content.map((block) => block.type === "toolCall" ? { type: "text", text: callBlock(block) } : block),
            };
        }
        if (message.role === "toolResult") {
            const text = message.content.flatMap((block) => (block.type === "text" ? [block.text] : [])).join("\n");
            const images = message.content.filter((block) => block.type === "image");
            return {
                role: "user",
                content: [
                    { type: "text", text: `Result of ${message.toolName}${message.isError ? " (error)" : ""}:\n${text}` },
                    ...images,
                ],
                timestamp: message.timestamp,
            };
        }
        return message;
    });
    const system = { role: "system", content: prompt, timestamp: head?.timestamp ?? 0 };
    return prompt === "" ? rest : [system, ...rest];
}
let shimCalls = 0;
/** Wrap a transport for a model without native tool calling. */
export function withPromptToolShim(inner) {
    const transport = (request) => (async function* () {
        let text = "";
        for await (const event of inner({ ...request, messages: plainTranscript(request.messages) })) {
            if (event.type === "text_delta") {
                text += event.delta;
                continue;
            }
            if (event.type !== "response_end") {
                if (event.type === "reasoning_delta")
                    yield event;
                continue;
            }
            const parsed = parseToolCallBlocks(text);
            if (parsed.text !== "")
                yield { type: "text_delta", delta: parsed.text };
            const toolCalls = parsed.calls.map((call) => ({
                type: "toolCall",
                id: `shim_call_${++shimCalls}`,
                name: call.name,
                arguments: call.arguments,
            }));
            for (const toolCall of toolCalls)
                yield { type: "tool_call", toolCall };
            const message = {
                ...event.message,
                content: [
                    ...event.message.content.filter((block) => block.type === "thinking"),
                    ...(parsed.text !== "" ? [{ type: "text", text: parsed.text }] : []),
                    ...toolCalls,
                ],
                stopReason: toolCalls.length > 0 && event.message.stopReason === "stop" ? "toolUse" : event.message.stopReason,
            };
            yield { type: "response_end", message };
            return;
        }
    })();
    transport.supportsHeaders = inner.supportsHeaders;
    transport.supportsProviderEvents = inner.supportsProviderEvents;
    return transport;
}
