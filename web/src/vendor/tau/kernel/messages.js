/**
 * Message shapes mirror pi's message model exactly (D13): content-block
 * arrays preserving interleaving order, pi's Usage/StopReason, message-level
 * timestamps. Wire-format conversion lives in openai.ts, not here.
 */
export function computeUsageCost(usage, pricing) {
    const input = (usage.input / 1_000_000) * pricing.inputPerMTok;
    const output = (usage.output / 1_000_000) * pricing.outputPerMTok;
    const cacheRead = pricing.cacheReadPerMTok === undefined ? 0 : (usage.cacheRead / 1_000_000) * pricing.cacheReadPerMTok;
    const cacheWrite = pricing.cacheWritePerMTok === undefined ? 0 : (usage.cacheWrite / 1_000_000) * pricing.cacheWritePerMTok;
    return { input, output, cacheRead, cacheWrite, total: input + output + cacheRead + cacheWrite };
}
/** Text projection of user content — what input hooks, before_agent_start and renderers see. */
export function userContentText(content) {
    if (typeof content === "string")
        return content;
    return content
        .filter((block) => block.type === "text")
        .map((block) => block.text)
        .join("\n");
}
export function emptyUsage() {
    return {
        input: 0,
        output: 0,
        cacheRead: 0,
        cacheWrite: 0,
        totalTokens: 0,
        cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
    };
}
/** Text of string or block content: text blocks joined by `separator`, other blocks skipped (pi's contentText). */
export function contentText(content, separator = "") {
    if (typeof content === "string")
        return content;
    const parts = [];
    for (const block of content)
        if (block.type === "text" && block.text !== undefined)
            parts.push(block.text);
    return parts.join(separator);
}
/** Sum of two usages (pi combineUsage). */
export function combineUsage(first, second) {
    return {
        input: first.input + second.input,
        output: first.output + second.output,
        cacheRead: first.cacheRead + second.cacheRead,
        cacheWrite: first.cacheWrite + second.cacheWrite,
        totalTokens: first.totalTokens + second.totalTokens,
        cost: {
            input: first.cost.input + second.cost.input,
            output: first.cost.output + second.cost.output,
            cacheRead: first.cost.cacheRead + second.cost.cacheRead,
            cacheWrite: first.cost.cacheWrite + second.cost.cacheWrite,
            total: first.cost.total + second.cost.total,
        },
    };
}
/** Concatenated text of a message's text blocks (string content returned as-is). */
export function messageText(message) {
    if (message.role === "compactionSummary" || message.role === "branchSummary")
        return message.summary;
    return contentText(message.content);
}
/** Tool calls requested by an assistant message, in content order. */
export function toolCallsOf(message) {
    return message.content.filter((block) => block.type === "toolCall");
}
/** Concatenated thinking text of an assistant message. */
export function thinkingText(message) {
    return message.content
        .filter((block) => block.type === "thinking")
        .map((block) => block.thinking)
        .join("");
}
