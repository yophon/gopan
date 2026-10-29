// A side question (P50: split out of agent.ts; P39c, Claude Code's /btw): one request over the
// conversation as the next request would send it, outside the conversation.
import { TauError } from "./errors.js";
import { computeUsageCost, emptyUsage, messageText, toolCallsOf, } from "./messages.js";
import { OPENAI_COMPLETIONS_API } from "./openai.js";
import { withoutHiddenDeclarations } from "./tool-state.js";
/**
 * A side question (P39c, Claude Code's /btw): the conversation as the next request would
 * send it (same prefix, so a warm prompt cache serves it), the newest 20 earlier side
 * exchanges, then the question. Nothing joins the conversation and no tool runs; the
 * request still declares the tools (a changed prefix would miss the cache), so tool
 * calls the model makes anyway are only counted. Works while a run is in flight: it sees
 * the committed messages, not the reply being written. Its usage is logged as a `usage`
 * entry so session totals stay honest.
 */
export async function askAside(host, question, options = {}) {
    const ctx = host.extensionContext();
    let request = [...host.messages];
    if (host.extensions)
        request = await host.extensions.runContext(request, ctx);
    request = withoutHiddenDeclarations(request, host.hiddenDeclarations);
    // Tool calls still running have no result yet: say so (a call without a result is refused).
    const answered = new Set(request.flatMap((message) => (message.role === "toolResult" ? [message.toolCallId] : [])));
    let last;
    for (let i = request.length - 1; i >= 0 && !last; i--) {
        const message = request[i];
        if (message?.role === "assistant")
            last = message;
    }
    const now = Date.now();
    for (const call of last ? toolCallsOf(last) : []) {
        if (answered.has(call.id))
            continue;
        request.push({
            role: "toolResult",
            toolCallId: call.id,
            toolName: call.name,
            content: [{ type: "text", text: "(still running; no result yet)" }],
            isError: false,
            timestamp: now,
        });
    }
    const api = host.config.api ?? OPENAI_COMPLETIONS_API;
    const provider = host.config.provider ?? "openai-compat";
    for (const exchange of (options.exchanges ?? []).slice(-20)) {
        request.push({ role: "user", content: sideQuestion(exchange.question), timestamp: now }, {
            role: "assistant",
            content: [{ type: "text", text: exchange.answer }],
            api,
            provider,
            model: host.currentModel,
            usage: emptyUsage(),
            stopReason: "stop",
            timestamp: now,
        });
    }
    request.push({ role: "user", content: sideQuestion(question), timestamp: now });
    const headers = await host.providerHeaders(ctx);
    const hooks = host.providerEventHooks(ctx, host.currentModel);
    const stream = host.transport({
        messages: request,
        ...(options.signal ? { signal: options.signal } : {}),
        model: host.currentModel,
        ...(host.currentThinkingLevel !== undefined ? { thinkingLevel: host.currentThinkingLevel } : {}),
        ...(headers ? { headers } : {}),
        ...hooks,
    });
    let text = "";
    let message;
    for await (const event of stream) {
        if (event.type === "text_delta") {
            text += event.delta;
            options.onText?.(event.delta);
        }
        else if (event.type === "response_end") {
            message = event.message;
            break;
        }
    }
    if (!message)
        throw new TauError("stream_error", "The side question got no reply");
    const reply = message;
    const pricing = host.pricingFor(reply.model);
    if (pricing)
        reply.usage.cost = computeUsageCost(reply.usage, pricing);
    host.enqueueBackground(async () => {
        await host.session.append({
            type: "usage",
            kind: "side_question",
            provider: reply.provider,
            model: reply.model,
            usage: reply.usage,
        });
        await host.refresh();
    });
    if (reply.stopReason === "error")
        throw new TauError("stream_error", reply.errorMessage ?? "The side question failed");
    if (reply.stopReason === "aborted")
        throw new TauError("aborted", "The side question was cancelled");
    return { text: messageText(reply) || text, toolCalls: toolCallsOf(reply).length, stopReason: reply.stopReason };
}
/** A /btw question as the model reads it (P39c): answer from context, no tools. */
function sideQuestion(question) {
    return `<side-question>This is a side question from the user. Answer it briefly from what is already in this conversation. You cannot run tools for it: do not call any, and say so if the answer needs information you do not have. It does not change the task you are working on.</side-question>\n\n${question}`;
}
