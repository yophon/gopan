import { AbortHandle, throwIfAborted } from "./abort.js";
import { describeError, HttpError, TauError } from "./errors.js";
import { parseToolCallArguments } from "./json.js";
import { emptyUsage, } from "./messages.js";
import { collapseSystemMessages, getCurrentTools, getSystemMessageText, renderSystemMessageUpdate, } from "./projection.js";
import { SseParser } from "./sse.js";
import { transformMessages } from "./transform.js";
import { uuidv7 } from "./uuid.js";
// Wire framing for summary messages, verbatim from pi's convertToLlm
// (packages/agent/src/harness/messages.ts). Both summaries enter context as
// user messages wrapped in <summary> tags. Exported so alternative transports
// (P16) frame summary messages identically.
export const COMPACTION_SUMMARY_PREFIX = `The conversation history before this point was compacted into the following summary:

<summary>
`;
export const COMPACTION_SUMMARY_SUFFIX = `
</summary>`;
export const BRANCH_SUMMARY_PREFIX = `The following is a summary of a branch that this conversation came back from:

<summary>
`;
export const BRANCH_SUMMARY_SUFFIX = `</summary>`;
/** The `api` field recorded on assistant messages, as in pi. */
export const OPENAI_COMPLETIONS_API = "openai-completions";
/** Reasoning fields OpenAI-compatible endpoints stream, in pi's precedence order. */
const REASONING_FIELDS = ["reasoning_content", "reasoning", "reasoning_text"];
function isReasoningField(value) {
    return value !== undefined && REASONING_FIELDS.includes(value);
}
/**
 * Removes unpaired UTF-16 surrogates, verbatim from pi utils/sanitize-unicode.ts:
 * they make many providers reject the JSON body. Paired surrogates (emoji) stay.
 */
export function sanitizeSurrogates(text) {
    return text.replace(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/g, "");
}
/** User content on the wire; empty text parts are dropped (pi 0.87.1), undefined when nothing is left. */
function userContentToWire(content) {
    if (typeof content === "string")
        return sanitizeSurrogates(content);
    const parts = content
        .filter((block) => block.type !== "text" || block.text.length > 0)
        .map((block) => block.type === "text"
        ? { type: "text", text: sanitizeSurrogates(block.text) }
        : { type: "image_url", image_url: { url: `data:${block.mimeType};base64,${block.data}` } });
    return parts.length === 0 ? undefined : parts;
}
/**
 * A tool message's text (pi convertMessages): Chat Completions tool messages are
 * text-only, so images travel in a user message after the results (P36d). An
 * image-only result says "(see attached image)"; an empty one "(no tool output)"
 * (pi 0.80.4: some endpoints reject empty tool content).
 */
function toolResultContentToWire(content) {
    const text = content
        .filter((block) => block.type === "text")
        .map((block) => block.text)
        .join("\n");
    // Whitespace-only output counts as none: some providers reject it (pi 1.4).
    if (text.trim() !== "")
        return sanitizeSurrogates(text);
    return content.some((block) => block.type === "image") ? "(see attached image)" : "(no tool output)";
}
/** pi's user message carrying the images of the tool results just before it. */
export const TOOL_RESULT_IMAGES_TEXT = "Attached image(s) from tool result:";
/**
 * Tool call ids for Chat Completions replay, from pi's normalizeToolCallId:
 * Responses-style `call_id|item_id` ids are sanitized and held to 40 characters
 * (keeping calls that share a call_id distinct); OpenAI's own endpoint caps
 * plain ids at 40 too.
 */
export function normalizeToolCallId(id, provider) {
    if (id.includes("|")) {
        const separatorIndex = id.indexOf("|");
        const callId = id.slice(0, separatorIndex).replace(/[^a-zA-Z0-9_-]/g, "_");
        const itemId = id.slice(separatorIndex + 1).replace(/[^a-zA-Z0-9_-]/g, "_");
        const combinedId = itemId.length > 0 ? `${callId}_${itemId}` : callId;
        if (combinedId.length <= 40)
            return combinedId;
        const hash = shortHash(id).slice(0, 8);
        const prefix = callId.slice(0, Math.max(1, 40 - hash.length - 1));
        return `${prefix}_${hash}`;
    }
    if (provider === "openai")
        return id.length > 40 ? id.slice(0, 40) : id;
    return id;
}
/** Fast non-cryptographic hash, verbatim from pi utils/hash.ts. */
export function shortHash(str) {
    let h1 = 0xdeadbeef;
    let h2 = 0x41c6ce57;
    for (let i = 0; i < str.length; i++) {
        const ch = str.charCodeAt(i);
        h1 = Math.imul(h1 ^ ch, 2654435761);
        h2 = Math.imul(h2 ^ ch, 1597334677);
    }
    h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
    h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
    return (h2 >>> 0).toString(36) + (h1 >>> 0).toString(36);
}
function toWireMessage(message, replayEmptyReasoning) {
    switch (message.role) {
        case "user": {
            const content = userContentToWire(message.content);
            return content === undefined ? undefined : { role: "user", content };
        }
        case "custom": {
            // pi's convertToLlm sends custom messages to the model as user messages.
            const content = userContentToWire(message.content);
            return content === undefined ? undefined : { role: "user", content };
        }
        case "compactionSummary":
            // As in pi's convertToLlm: enters context as a user message wrapped in <summary> tags.
            return {
                role: "user",
                content: sanitizeSurrogates(COMPACTION_SUMMARY_PREFIX + message.summary + COMPACTION_SUMMARY_SUFFIX),
            };
        case "branchSummary":
            return {
                role: "user",
                content: sanitizeSurrogates(BRANCH_SUMMARY_PREFIX + message.summary + BRANCH_SUMMARY_SUFFIX),
            };
        case "assistant": {
            const text = message.content
                .filter((block) => block.type === "text")
                .map((block) => block.text)
                .join("");
            const toolCalls = message.content.filter((block) => block.type === "toolCall");
            const wire = {
                role: "assistant",
                content: text === "" ? null : sanitizeSurrogates(text),
            };
            // Same-model reasoning is replayed under the field it arrived in (pi records the
            // field name in thinkingSignature); transformMessages already turned foreign
            // thinking into text, so any thinking block left here is ours.
            const thinking = message.content.filter((block) => block.type === "thinking" && !block.redacted);
            const reasoningText = thinking.map((block) => (block.type === "thinking" ? block.thinking : "")).join("");
            if (reasoningText !== "") {
                const first = thinking[0];
                const field = first?.type === "thinking" && isReasoningField(first.thinkingSignature)
                    ? first.thinkingSignature
                    : "reasoning_content";
                wire[field] = sanitizeSurrogates(reasoningText);
            }
            else if (replayEmptyReasoning) {
                wire.reasoning_content = "";
            }
            if (toolCalls.length > 0) {
                wire.tool_calls = toolCalls.map((toolCall) => ({
                    id: toolCall.id,
                    type: "function",
                    function: { name: toolCall.name, arguments: JSON.stringify(toolCall.arguments) },
                }));
            }
            return wire;
        }
        case "toolResult":
            return { role: "tool", tool_call_id: message.toolCallId, content: toolResultContentToWire(message.content) };
        case "system":
            // Only reached with midConversationSystemMessages; otherwise the transcript is collapsed first.
            return { role: "system", content: sanitizeSurrogates(renderSystemMessageUpdate(message)) };
    }
}
/**
 * Resolve a transcript for the wire (pi resolveTranscript): without
 * mid-conversation system messages the replayed system state is folded into
 * one leading system message; with them, later system messages stay in place.
 * Tools are always the replayed current set.
 */
export function resolveTranscript(messages, midConversationSystemMessages) {
    const transcript = midConversationSystemMessages ? [...messages] : collapseSystemMessages(messages);
    const head = transcript[0]?.role === "system" ? transcript[0] : undefined;
    return {
        systemPrompt: head ? getSystemMessageText(head) : "",
        messages: head ? transcript.slice(1) : transcript,
        tools: getCurrentTools(messages),
    };
}
function toWireMessages(systemPrompt, messages, replayEmptyReasoning = false) {
    const wire = [];
    if (systemPrompt !== "")
        wire.push({ role: "system", content: sanitizeSurrogates(systemPrompt) });
    // Images of a run of tool results go in one user message after the run (pi).
    let images = [];
    const flushImages = () => {
        if (images.length === 0)
            return;
        wire.push({
            role: "user",
            content: userContentToWire([{ type: "text", text: TOOL_RESULT_IMAGES_TEXT }, ...images]),
        });
        images = [];
    };
    for (const message of messages) {
        if (message.role !== "toolResult")
            flushImages();
        // Images the model cannot take were already replaced by transformMessages.
        else
            images.push(...message.content.filter((block) => block.type === "image"));
        const converted = toWireMessage(message, replayEmptyReasoning);
        // A user message left with no content (only empty text parts) is skipped, as in pi.
        if (converted)
            wire.push(converted);
    }
    flushImages();
    return wire;
}
/**
 * Usage of a chunk, verbatim from pi parseChunkUsage: cache reads come from
 * prompt_tokens_details.cached_tokens (OpenAI/OpenRouter), prompt_cache_hit_tokens
 * (DeepSeek) or top-level cached_tokens (Kimi); cache writes are separate;
 * `input` is the uncached remainder and `totalTokens` the sum of the four.
 */
function toUsage(wire) {
    const promptTokens = wire.prompt_tokens || 0;
    const cacheRead = wire.prompt_tokens_details?.cached_tokens ?? wire.prompt_cache_hit_tokens ?? wire.cached_tokens ?? 0;
    const cacheWrite = wire.prompt_tokens_details?.cache_write_tokens || 0;
    const input = Math.max(0, promptTokens - cacheRead - cacheWrite);
    const output = wire.completion_tokens || 0;
    return {
        input,
        output,
        cacheRead,
        cacheWrite,
        reasoning: wire.completion_tokens_details?.reasoning_tokens || 0,
        totalTokens: input + output + cacheRead + cacheWrite,
        cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
    };
}
/** finish_reason to StopReason, verbatim from pi mapStopReason: anything unknown is an error. */
function mapStopReason(reason) {
    switch (reason) {
        case "stop":
        case "end":
            return { stopReason: "stop" };
        case "length":
            return { stopReason: "length" };
        case "function_call":
        case "tool_calls":
            return { stopReason: "toolUse" };
        default:
            return { stopReason: "error", errorMessage: `Provider finish_reason: ${reason}` };
    }
}
/**
 * Merge request-level header changes onto a header set (pi ProviderHeaders): a
 * string adds or replaces, null deletes; names compare case-insensitively.
 */
export function applyHeaderChanges(headers, changes) {
    if (!changes)
        return headers;
    const result = { ...headers };
    for (const [name, value] of Object.entries(changes)) {
        for (const existing of Object.keys(result)) {
            if (existing.toLowerCase() === name.toLowerCase())
                delete result[existing];
        }
        if (value !== null)
            result[name] = value;
    }
    return result;
}
function authorityOf(baseUrl) {
    const match = /^[a-z][a-z0-9+.-]*:\/\/([^/?#]+)/i.exec(baseUrl);
    return match ? match[1] : "";
}
const DEFAULT_STALL_TIMEOUT_MS = 120_000;
/**
 * Race a pending step against the stall clock. Losers are always cleaned up:
 * the sleep is aborted when the step wins, and a timed-out step's eventual
 * rejection is marked handled so it cannot become an unhandled rejection.
 * Exported for alternative transports (P16) so they share the same watchdog.
 */
export async function withStallTimeout(step, platform, stallTimeoutMs, what) {
    const timeoutMs = stallTimeoutMs ?? DEFAULT_STALL_TIMEOUT_MS;
    const sleep = platform.sleep;
    if (!sleep || timeoutMs <= 0)
        return step;
    const stallClock = new AbortHandle();
    // Mark handled: the losing branch's rejection must never surface on its own.
    const guarded = step.then((value) => ({ ok: true, value }), (error) => ({ ok: false, error }));
    const clock = sleep(timeoutMs, stallClock.signal).then(() => "timeout", () => "cancelled");
    const winner = await Promise.race([guarded, clock]);
    if (winner === "timeout") {
        // Wording matters: "timed out" keeps this classifiable by the pi retry patterns.
        throw new TauError("timeout", `${what} timed out: no data for ${timeoutMs}ms`);
    }
    stallClock.abort();
    if (winner === "cancelled") {
        // Unreachable: the clock only reports cancelled after stallClock.abort() above.
        throw new TauError("timeout", `${what} stall clock cancelled unexpectedly`);
    }
    if (!winner.ok)
        throw winner.error;
    return winner.value;
}
/**
 * Stream a chat completion from any OpenAI-compatible endpoint. Yields text and
 * reasoning deltas as they arrive, complete tool calls once assembled, and a
 * final `response_end` carrying the full assistant message (pi shape: ordered
 * content blocks, Usage, StopReason).
 */
export async function* streamChatCompletion(platform, config, messages, options) {
    const model = options?.model ?? config.model;
    throwIfAborted(options?.signal, "Request aborted");
    // pi v0.80.3 stamps the message at request start, on success and failure alike.
    const startedAt = Date.now();
    const url = `${config.baseUrl.replace(/\/+$/, "")}/chat/completions`;
    const api = config.api ?? OPENAI_COMPLETIONS_API;
    const provider = config.provider ?? "openai-compat";
    const replayEmptyReasoning = config.replayEmptyReasoning ?? /(^|[./])deepseek\.com(?=[:/]|$)/i.test(authorityOf(config.baseUrl));
    const transcript = resolveTranscript(messages, config.midConversationSystemMessages);
    const body = {
        model,
        messages: toWireMessages(transcript.systemPrompt, transformMessages(transcript.messages, {
            api,
            provider,
            model,
            ...(config.input ? { input: config.input } : {}),
            normalizeToolCallId: (id) => normalizeToolCallId(id, provider),
        }), replayEmptyReasoning),
        stream: true,
        ...(options?.maxTokens !== undefined ? { [config.maxTokensField ?? "max_tokens"]: options.maxTokens } : {}),
        // The thinking level travels on the request (P23d); extraBody still overrides it.
        ...(options?.thinkingLevel !== undefined ? { reasoning_effort: options.thinkingLevel } : {}),
        ...config.extraBody,
    };
    if (config.includeUsage !== false)
        body.stream_options = { include_usage: true };
    if (transcript.tools.length > 0) {
        body.tools = transcript.tools.map((tool) => ({
            type: "function",
            function: { name: tool.name, description: tool.description, parameters: tool.parameters },
        }));
    }
    let headers = { "content-type": "application/json" };
    if (config.apiKey)
        headers.authorization = `Bearer ${config.apiKey}`;
    headers = applyHeaderChanges(headers, config.headers);
    // before_provider_headers changes go last, after the configured headers (pi).
    headers = applyHeaderChanges(headers, options?.headers);
    // before_provider_request (P40d, pi) may replace the body.
    const payload = options?.onPayload ? await options.onPayload(body) : body;
    let response;
    try {
        response = await withStallTimeout(platform.fetch(url, {
            method: "POST",
            headers,
            body: JSON.stringify(payload),
            signal: options?.signal,
        }), platform, config.stallTimeoutMs, "Waiting for response headers");
    }
    catch (cause) {
        // An aborted fetch surfaces as the platform's own abort rejection; report it as ours.
        throwIfAborted(options?.signal, "Request aborted");
        if (cause instanceof TauError && cause.code === "timeout")
            throw cause;
        throw new TauError("network_error", `Network request failed: ${describeError(cause)}`, cause);
    }
    await options?.onResponse?.({ status: response.status, headers: responseHeaders(response) });
    if (!response.ok) {
        throw new HttpError(response.status, await response.text());
    }
    if (!response.body) {
        throw new TauError("stream_error", "Response has no body stream");
    }
    const reader = response.body.getReader();
    const decoder = platform.createUtf8Decoder();
    const parser = new SseParser();
    // Content blocks accumulate in arrival order; raw tool-call fragments are
    // attributed as in pi's ensureToolCallBlock (index first, then id, else a new
    // call; id and name are set once) and parsed once complete.
    const blocks = [];
    const rawToolCalls = [];
    const rawByIndex = new Map();
    const rawById = new Map();
    let finishReason;
    let mappedStop;
    let usage;
    let responseId;
    let responseModel;
    let reasoningField;
    const pendingEvents = [];
    let done = false;
    const appendText = (delta) => {
        const last = blocks.at(-1);
        if (last?.type === "text")
            last.text += delta;
        else
            blocks.push({ type: "text", text: delta });
    };
    const appendThinking = (delta, field) => {
        const last = blocks.at(-1);
        if (last?.type === "thinking")
            last.thinking += delta;
        else
            blocks.push({ type: "thinking", thinking: delta, thinkingSignature: field });
    };
    const processData = (data) => {
        if (data === "" || data === "[DONE]") {
            if (data === "[DONE]")
                done = true;
            return;
        }
        let chunk;
        try {
            chunk = JSON.parse(data);
        }
        catch (cause) {
            throw new TauError("invalid_response", `Failed to parse stream chunk: ${data.slice(0, 200)}`, cause);
        }
        options?.onStreamEvent?.(chunk);
        if (chunk.error) {
            throw new TauError("stream_error", chunk.error.message ?? "Provider returned an error in the stream");
        }
        if (chunk.usage)
            usage = toUsage(chunk.usage);
        if (typeof chunk.id === "string" && chunk.id !== "")
            responseId ??= chunk.id;
        if (typeof chunk.model === "string" && chunk.model !== "")
            responseModel ??= chunk.model;
        const choice = chunk.choices?.[0];
        if (!choice)
            return;
        // Some providers (Moonshot) put usage on the choice instead of the chunk (pi).
        if (!chunk.usage && choice.usage)
            usage = toUsage(choice.usage);
        if (choice.finish_reason) {
            finishReason = choice.finish_reason;
            mappedStop = mapStopReason(choice.finish_reason);
        }
        const delta = choice.delta;
        if (!delta)
            return;
        // Reasoning before content: proxies such as LiteLLM put both in one delta, and the
        // thinking must come before the answer (pi 1.3).
        // First non-empty reasoning field wins (`??` used to pick an empty
        // reasoning_content over a populated reasoning field).
        const field = REASONING_FIELDS.find((name) => typeof delta[name] === "string" && delta[name] !== "");
        if (field) {
            const reasoningDelta = delta[field];
            reasoningField ??= field;
            appendThinking(reasoningDelta, reasoningField);
            pendingEvents.push({ type: "reasoning_delta", delta: reasoningDelta });
        }
        if (typeof delta.content === "string" && delta.content !== "") {
            appendText(delta.content);
            pendingEvents.push({ type: "text_delta", delta: delta.content });
        }
        for (const wireToolCall of delta.tool_calls ?? []) {
            const index = typeof wireToolCall.index === "number" ? wireToolCall.index : undefined;
            const id = wireToolCall.id || undefined;
            const name = wireToolCall.function?.name || undefined;
            let entry = index !== undefined ? rawByIndex.get(index) : undefined;
            if (!entry && id)
                entry = rawById.get(id);
            // tau deviation: a bare arguments fragment (no index, id or name) continues the
            // latest call. pi opens a nameless call for it and the arguments are lost.
            if (!entry && index === undefined && !id && !name)
                entry = rawToolCalls.at(-1);
            if (!entry) {
                entry = { id: "", name: "", argumentsText: "" };
                rawToolCalls.push(entry);
            }
            if (index !== undefined && !rawByIndex.has(index))
                rawByIndex.set(index, entry);
            if (id && !entry.id) {
                entry.id = id;
                rawById.set(id, entry);
            }
            if (name && !entry.name)
                entry.name = name;
            if (wireToolCall.function?.arguments)
                entry.argumentsText += wireToolCall.function.arguments;
            if (entry.name !== "") {
                pendingEvents.push({
                    type: "tool_call_delta",
                    index: rawToolCalls.indexOf(entry),
                    ...(entry.id ? { id: entry.id } : {}),
                    name: entry.name,
                    argumentsText: entry.argumentsText,
                });
            }
        }
    };
    try {
        while (!done) {
            throwIfAborted(options?.signal, "Request aborted");
            let readResult;
            try {
                readResult = await withStallTimeout(reader.read(), platform, config.stallTimeoutMs, "Response stream");
            }
            catch (cause) {
                // Reads interrupted by abort reject with the platform's own error; report it as ours.
                throwIfAborted(options?.signal, "Request aborted");
                if (cause instanceof TauError && cause.code === "timeout")
                    throw cause;
                throw new TauError("stream_error", `Stream read failed: ${describeError(cause)}`, cause);
            }
            const { done: readerDone, value } = readResult;
            if (readerDone)
                break;
            if (!value)
                continue;
            for (const event of parser.push(decoder.decode(value))) {
                processData(event.data);
                if (done)
                    break;
            }
            yield* pendingEvents.splice(0);
        }
        const tail = decoder.flush();
        if (!done && tail.length > 0) {
            for (const event of parser.push(tail))
                processData(event.data);
        }
        if (!done) {
            for (const event of parser.flush())
                processData(event.data);
        }
        yield* pendingEvents.splice(0);
    }
    finally {
        try {
            await reader.cancel();
        }
        catch {
            // The stream is already finished or errored; nothing to cancel.
        }
    }
    throwIfAborted(options?.signal, "Request aborted");
    // Strict end-of-stream semantics (pi): an error finish_reason fails the request,
    // and so does a stream that ended without any — unless the endpoint is declared
    // not to send one. Thrown as stream errors, so the agent records an error message
    // with the partial content and the retry patterns apply ("ended without").
    if (mappedStop?.stopReason === "error") {
        throw new TauError("stream_error", mappedStop.errorMessage ?? "Provider returned an error stop reason");
    }
    if (mappedStop === undefined && config.supportsFinishReason !== false) {
        throw new TauError("stream_error", "Stream ended without finish_reason");
    }
    const seenIds = new Set();
    const toolCalls = rawToolCalls
        .filter((raw) => raw.name !== "")
        .map((raw) => {
        // Malformed arguments fail validation with the text the model sent (pi 1.3).
        const parsed = parseToolCallArguments(raw.argumentsText);
        // A missing or repeated id gets a fresh one: `call_<index>` repeated across turns,
        // and protocols such as Anthropic's require unique tool_use ids.
        let id = raw.id;
        if (id === "" || seenIds.has(id))
            id = `call_${uuidv7(platform.randomBytes).replace(/-/g, "").slice(-12)}`;
        seenIds.add(id);
        return {
            type: "toolCall",
            id,
            name: raw.name,
            arguments: parsed.arguments,
            ...(parsed.argumentsError !== undefined ? { argumentsError: parsed.argumentsError } : {}),
        };
    });
    for (const toolCall of toolCalls) {
        blocks.push(toolCall);
        yield { type: "tool_call", toolCall };
    }
    const message = {
        role: "assistant",
        content: blocks,
        api,
        provider,
        model,
        usage: usage ?? emptyUsage(),
        stopReason: mappedStop?.stopReason ?? (toolCalls.length > 0 ? "toolUse" : "stop"),
        ...(finishReason !== undefined ? { rawStopReason: finishReason } : {}),
        ...(responseId !== undefined ? { responseId } : {}),
        ...(responseModel !== undefined ? { responseModel } : {}),
        timestamp: startedAt,
    };
    yield { type: "response_end", message };
}
/** A response's headers as a plain object (P40d), when the platform can list them; else empty. */
export function responseHeaders(response) {
    const out = {};
    const headers = response.headers;
    headers?.forEach?.((value, key) => {
        out[key] = value;
    });
    return out;
}
/** The default transport: the OpenAI-compatible client, verbatim. */
export function createOpenAICompatTransport(platform, config) {
    const transport = (request) => streamChatCompletion(platform, config, request.messages, {
        signal: request.signal,
        maxTokens: request.maxTokens,
        headers: request.headers,
        thinkingLevel: request.thinkingLevel,
        model: request.model,
        ...(request.onPayload ? { onPayload: request.onPayload } : {}),
        ...(request.onResponse ? { onResponse: request.onResponse } : {}),
        ...(request.onStreamEvent ? { onStreamEvent: request.onStreamEvent } : {}),
    });
    transport.supportsHeaders = true;
    transport.supportsProviderEvents = true;
    return transport;
}
