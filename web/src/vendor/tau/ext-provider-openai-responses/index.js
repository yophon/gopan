// OpenAI Responses API transport over the kernel's Platform seam (P54a) — the protocol is
// hand-written against the streaming Responses API instead of using the openai SDK, which
// assumes Node/browser globals; same route as ../../ext-provider-anthropic.
//
// Message, event, stop-reason and usage mapping mirror pi's packages/ai/src/api/openai-responses.ts
// and openai-responses-shared.ts (v1.4.0). Deviations, each deliberate:
//   - no model registry: config says whether the model reasons and what it takes (pi model.reasoning/input)
//   - stateless only: store false, reasoning replayed from its encrypted content; no
//     previous_response_id, server tools, service tiers or prompt-cache keys
//   - function tools only, declared in the tools field: no grammar/custom tools and no
//     additional_tools, tool_search or configuration_update items (tau folds the transcript first)
//   - failures throw TauError (D14) instead of pi's error stream events
import { applyHeaderChanges, BRANCH_SUMMARY_PREFIX, BRANCH_SUMMARY_SUFFIX, COMPACTION_SUMMARY_PREFIX, COMPACTION_SUMMARY_SUFFIX, describeError, emptyUsage, HttpError, parseToolCallArguments, resolveTranscript, responseHeaders, SseParser, sanitizeSurrogates, shortHash, TauError, transformMessages, withStallTimeout, } from "@yophon/tau-kernel";
/** The `api` field recorded on assistant messages, as in pi. */
export const OPENAI_RESPONSES_API = "openai-responses";
export const OPENAI_DEFAULT_BASE_URL = "https://api.openai.com/v1";
/** pi's OPENAI_RESPONSES_MIN_OUTPUT_TOKENS: the API rejects a smaller max_output_tokens. */
const MIN_OUTPUT_TOKENS = 16;
/** A thinking level as a Responses reasoning effort. OpenAI has no "max": it gets the highest, xhigh. */
export function responsesEffortForLevel(level) {
    return level === "max" ? "xhigh" : level;
}
// ---------------------------------------------------------------------------
// Ids (pi convertResponsesMessages): tool call ids on this wire are "call_id|item_id".
// ---------------------------------------------------------------------------
function normalizeIdPart(part) {
    const sanitized = part.replace(/[^a-zA-Z0-9_-]/g, "_");
    return (sanitized.length > 64 ? sanitized.slice(0, 64) : sanitized).replace(/_+$/, "");
}
/** A tool call id from another model or protocol: the call id cleaned, the item id hashed to fc_* (pi). */
function foreignToolCallId(id) {
    if (!id.includes("|"))
        return normalizeIdPart(id);
    const [callId = "", itemId = ""] = id.split("|");
    const item = `fc_${shortHash(itemId)}`;
    return `${normalizeIdPart(callId)}|${item.length > 64 ? item.slice(0, 64) : item}`;
}
function encodeTextSignature(id, phase) {
    return JSON.stringify({ v: 1, id, ...(phase === "commentary" || phase === "final_answer" ? { phase } : {}) });
}
/** pi parseTextSignature: TextSignatureV1 JSON, or a legacy plain id. */
function parseTextSignature(signature) {
    if (!signature)
        return undefined;
    if (signature.startsWith("{")) {
        try {
            const parsed = JSON.parse(signature);
            if (parsed.v === 1 && typeof parsed.id === "string") {
                return parsed.phase === "commentary" || parsed.phase === "final_answer"
                    ? { id: parsed.id, phase: parsed.phase }
                    : { id: parsed.id };
            }
        }
        catch {
            // Fall through to the legacy plain-string form.
        }
    }
    return { id: signature };
}
function inputImage(image) {
    return { type: "input_image", detail: "auto", image_url: `data:${image.mimeType};base64,${image.data}` };
}
function userItem(content) {
    if (typeof content === "string") {
        return { role: "user", content: [{ type: "input_text", text: sanitizeSurrogates(content) }] };
    }
    const parts = content.map((block) => block.type === "text" ? { type: "input_text", text: sanitizeSurrogates(block.text) } : inputImage(block));
    return parts.length > 0 ? { role: "user", content: parts } : undefined;
}
/** pi convertToolResultOutput: text alone stays a string; images make an input_text/input_image list. */
function toolResultOutput(content) {
    const text = content
        .filter((block) => block.type === "text")
        .map((block) => block.text)
        .join("\n");
    const images = content.filter((block) => block.type === "image");
    const hasText = text.trim().length > 0;
    if (images.length === 0)
        return sanitizeSurrogates(hasText ? text : "(no tool output)");
    return [...(hasText ? [{ type: "input_text", text: sanitizeSurrogates(text) }] : []), ...images.map(inputImage)];
}
function assistantItems(message, target, index) {
    const items = [];
    // Another model of this provider: item ids were paired with its reasoning items, so they go (pi).
    const differentModel = message.provider === target.provider && message.api === OPENAI_RESPONSES_API && message.model !== target.model;
    let textIndex = 0;
    for (const block of message.content) {
        if (block.type === "thinking") {
            // The reasoning item, encrypted content included, replays as it came (same model only:
            // transformMessages dropped other models' thinking).
            if (!block.thinkingSignature)
                continue;
            try {
                items.push(JSON.parse(block.thinkingSignature));
            }
            catch {
                // Not a Responses reasoning item (hand-built history): nothing to replay.
            }
        }
        else if (block.type === "text") {
            const signature = parseTextSignature(block.textSignature);
            const fallbackId = textIndex === 0 ? `msg_tau_${index}` : `msg_tau_${index}_${textIndex}`;
            textIndex++;
            let id = signature?.id ?? fallbackId;
            if (id.length > 64)
                id = `msg_${shortHash(id)}`;
            items.push({
                type: "message",
                role: "assistant",
                content: [{ type: "output_text", text: sanitizeSurrogates(block.text), annotations: [] }],
                status: "completed",
                id,
                ...(signature?.phase ? { phase: signature.phase } : {}),
            });
        }
        else {
            const [callId = "", rawItemId] = block.id.split("|");
            const itemId = differentModel || !rawItemId?.startsWith("fc_") ? undefined : rawItemId;
            items.push({
                type: "function_call",
                ...(itemId !== undefined ? { id: itemId } : {}),
                call_id: callId,
                name: block.name,
                arguments: JSON.stringify(block.arguments ?? {}),
            });
        }
    }
    return items;
}
export function messagesToInput(systemPrompt, messages, target) {
    const input = [];
    // Reasoning models take instructions as the developer role (pi instructionRole).
    if (systemPrompt !== "") {
        input.push({ role: target.reasoning ? "developer" : "system", content: sanitizeSurrogates(systemPrompt) });
    }
    messages.forEach((message, index) => {
        switch (message.role) {
            case "user":
            case "custom": {
                // Custom messages reach the model as user turns, as in pi's convertToLlm.
                const item = userItem(message.content);
                if (item)
                    input.push(item);
                break;
            }
            case "compactionSummary":
                input.push(userItem(COMPACTION_SUMMARY_PREFIX + message.summary + COMPACTION_SUMMARY_SUFFIX));
                break;
            case "branchSummary":
                input.push(userItem(BRANCH_SUMMARY_PREFIX + message.summary + BRANCH_SUMMARY_SUFFIX));
                break;
            case "assistant":
                input.push(...assistantItems(message, target, index));
                break;
            case "toolResult": {
                const [callId] = message.toolCallId.split("|");
                input.push({ type: "function_call_output", call_id: callId, output: toolResultOutput(message.content) });
                break;
            }
            case "system":
                // Folded into the leading instructions before conversion.
                break;
        }
    });
    return input;
}
function toolsToWire(tools) {
    return tools.map((tool) => ({
        type: "function",
        name: tool.name,
        description: tool.description,
        parameters: tool.parameters,
        strict: false,
    }));
}
export function buildRequestBody(config, request) {
    const provider = config.provider ?? "openai";
    const model = request.model ?? config.model;
    const reasoning = config.reasoning !== false;
    const transcript = resolveTranscript(request.messages, false);
    const messages = transformMessages(transcript.messages, {
        api: OPENAI_RESPONSES_API,
        provider,
        model,
        ...(config.input ? { input: config.input } : {}),
        normalizeToolCallId: foreignToolCallId,
    });
    const body = {
        model,
        input: messagesToInput(transcript.systemPrompt, messages, { provider, model, reasoning }),
        stream: true,
        store: false,
    };
    const maxTokens = request.maxTokens ?? config.maxTokens;
    if (maxTokens !== undefined)
        body.max_output_tokens = Math.max(maxTokens, MIN_OUTPUT_TOKENS);
    if (transcript.tools.length > 0)
        body.tools = toolsToWire(transcript.tools);
    if (reasoning) {
        const level = request.thinkingLevel;
        // "none" turns reasoning off (pi's off); without a level the model's default effort applies.
        if (level === "none") {
            body.reasoning = { effort: "none" };
        }
        else {
            body.reasoning = { ...(level !== undefined ? { effort: responsesEffortForLevel(level) } : {}), summary: "auto" };
            // store: false keeps nothing server-side: the encrypted reasoning comes back for replay.
            body.include = ["reasoning.encrypted_content"];
        }
    }
    return body;
}
/** Verbatim pi mapStopReason; an incomplete response keeps its reason so truncation and filtering stay apart. */
function mapStopReason(status, incompleteReason) {
    switch (status) {
        case undefined:
        case "completed":
        case "in_progress":
        case "queued":
            return { stopReason: "stop" };
        case "incomplete":
            if (incompleteReason === "max_output_tokens")
                return { stopReason: "length" };
            return {
                stopReason: "error",
                errorMessage: incompleteReason
                    ? `Response incomplete: ${incompleteReason}`
                    : "Response incomplete without a provider reason",
            };
        case "failed":
        case "cancelled":
            return { stopReason: "error" };
        default:
            throw new TauError("invalid_response", `Unhandled stop reason: ${status}`);
    }
}
function usageOf(wire) {
    const cached = wire.input_tokens_details?.cached_tokens || 0;
    const cacheWrite = wire.input_tokens_details?.cache_write_tokens || 0;
    return {
        ...emptyUsage(),
        // OpenAI counts cached and cache-write tokens inside input_tokens (pi subtracts both).
        input: Math.max(0, (wire.input_tokens || 0) - cached - cacheWrite),
        output: wire.output_tokens || 0,
        cacheRead: cached,
        cacheWrite,
        reasoning: wire.output_tokens_details?.reasoning_tokens || 0,
        totalTokens: wire.total_tokens || 0,
    };
}
function throwIfAborted(signal) {
    if (signal?.aborted)
        throw new TauError("aborted", "Request aborted");
}
/**
 * Create a ChatTransport speaking the OpenAI Responses API. The config object is read per
 * request, so hosts may mutate `model` between turns.
 */
export function createOpenAIResponsesTransport(config, platform) {
    const transport = async function* streamResponses(request) {
        throwIfAborted(request.signal);
        const startedAt = Date.now();
        const baseUrl = (config.baseUrl ?? OPENAI_DEFAULT_BASE_URL).replace(/\/+$/, "");
        const headers = applyHeaderChanges(applyHeaderChanges({
            "content-type": "application/json",
            accept: "application/json",
            authorization: `Bearer ${config.apiKey}`,
        }, config.headers), request.headers);
        const body = buildRequestBody(config, request);
        const payload = request.onPayload ? await request.onPayload(body) : body;
        let response;
        try {
            response = await withStallTimeout(platform.fetch(`${baseUrl}/responses`, {
                method: "POST",
                headers,
                body: JSON.stringify(payload),
                signal: request.signal,
            }), platform, config.stallTimeoutMs, "Waiting for response headers");
        }
        catch (cause) {
            throwIfAborted(request.signal);
            if (cause instanceof TauError && cause.code === "timeout")
                throw cause;
            throw new TauError("network_error", `Network request failed: ${describeError(cause)}`, cause);
        }
        await request.onResponse?.({ status: response.status, headers: responseHeaders(response) });
        if (!response.ok)
            throw new HttpError(response.status, await response.text());
        if (!response.body)
            throw new TauError("stream_error", "Response has no body stream");
        const reader = response.body.getReader();
        const decoder = platform.createUtf8Decoder();
        const parser = new SseParser();
        const content = [];
        const slots = new Map();
        const reasoningById = new Map();
        const final = {
            stopReason: "stop",
        };
        let usage = emptyUsage();
        let terminal = false;
        const pending = [];
        const createSlot = (outputIndex, item) => {
            let slot;
            if (item.type === "reasoning") {
                slot = { type: "thinking", block: { type: "thinking", thinking: "" } };
            }
            else if (item.type === "message") {
                slot = { type: "text", block: { type: "text", text: "" } };
            }
            else if (item.type === "function_call") {
                const block = {
                    type: "toolCall",
                    id: `${item.call_id ?? ""}|${item.id ?? ""}`,
                    name: item.name ?? "",
                    arguments: {},
                    partialJson: item.arguments || "",
                };
                slot = { type: "toolCall", block, index: outputIndex };
            }
            if (!slot)
                return undefined;
            content.push(slot.block);
            slots.set(outputIndex, slot);
            return slot;
        };
        const slotOf = (outputIndex, type) => {
            const slot = slots.get(outputIndex ?? -1);
            return slot?.type === type ? slot : undefined;
        };
        const thinkingDelta = (event, delta) => {
            const slot = slotOf(event.output_index, "thinking");
            if (!slot)
                return;
            slot.block.thinking += delta;
            pending.push({ type: "reasoning_delta", delta });
        };
        const finalize = (wire) => {
            terminal = true;
            // Azure can leave encrypted_content out of output_item.done and send it only here (pi #6409).
            for (const item of wire?.output ?? []) {
                if (item.type !== "reasoning" || !item.encrypted_content || !item.id)
                    continue;
                const block = reasoningById.get(item.id);
                if (!block?.thinkingSignature)
                    continue;
                const stored = JSON.parse(block.thinkingSignature);
                if (!stored.encrypted_content) {
                    block.thinkingSignature = JSON.stringify({ ...stored, encrypted_content: item.encrypted_content });
                }
            }
            if (wire?.id)
                final.responseId = wire.id;
            if (wire?.usage)
                usage = usageOf(wire.usage);
            const reason = typeof wire?.incomplete_details?.reason === "string" ? wire.incomplete_details.reason : undefined;
            final.rawStopReason = reason ? `${wire?.status}.${reason}` : wire?.status;
            const mapped = mapStopReason(wire?.status, reason);
            final.stopReason = mapped.stopReason;
            if (mapped.errorMessage !== undefined)
                final.errorMessage = mapped.errorMessage;
            if (final.stopReason === "stop" && content.some((block) => block.type === "toolCall"))
                final.stopReason = "toolUse";
        };
        const handleEvent = (data) => {
            if (data === "[DONE]")
                return;
            let event;
            try {
                event = JSON.parse(data);
            }
            catch (cause) {
                throw new TauError("invalid_response", `Failed to parse stream event: ${data.slice(0, 200)}`, cause);
            }
            request.onStreamEvent?.(event);
            switch (event.type) {
                case "response.created":
                    if (event.response?.id)
                        final.responseId = event.response.id;
                    break;
                case "response.output_item.added":
                    if (event.item && event.output_index !== undefined)
                        createSlot(event.output_index, event.item);
                    break;
                case "response.reasoning_summary_text.delta":
                case "response.reasoning_text.delta":
                    thinkingDelta(event, event.delta ?? "");
                    break;
                case "response.reasoning_summary_part.done":
                    thinkingDelta(event, "\n\n");
                    break;
                case "response.output_text.delta":
                case "response.refusal.delta": {
                    const slot = slotOf(event.output_index, "text");
                    if (!slot || !event.delta)
                        break;
                    slot.block.text += event.delta;
                    pending.push({ type: "text_delta", delta: event.delta });
                    break;
                }
                case "response.function_call_arguments.delta": {
                    const slot = slotOf(event.output_index, "toolCall");
                    if (!slot || slot.block.partialJson === undefined)
                        break;
                    slot.block.partialJson += event.delta ?? "";
                    pending.push({
                        type: "tool_call_delta",
                        index: slot.index,
                        id: slot.block.id,
                        name: slot.block.name,
                        argumentsText: slot.block.partialJson,
                    });
                    break;
                }
                case "response.function_call_arguments.done": {
                    const slot = slotOf(event.output_index, "toolCall");
                    if (slot && slot.block.partialJson !== undefined && event.arguments !== undefined) {
                        slot.block.partialJson = event.arguments;
                    }
                    break;
                }
                case "response.output_item.done": {
                    const item = event.item;
                    if (!item || event.output_index === undefined)
                        break;
                    const slot = slots.get(event.output_index) ?? createSlot(event.output_index, item);
                    if (item.type === "reasoning" && slot?.type === "thinking") {
                        const summary = item.summary?.map((part) => part.text ?? "").join("\n\n") || "";
                        const text = item.content?.map((part) => part.text ?? "").join("\n\n") || "";
                        slot.block.thinking = summary || text || slot.block.thinking;
                        slot.block.thinkingSignature = JSON.stringify(item);
                        if (item.id)
                            reasoningById.set(item.id, slot.block);
                    }
                    else if (item.type === "message" && slot?.type === "text") {
                        slot.block.text =
                            item.content?.map((part) => (part.type === "output_text" ? part.text : part.refusal) ?? "").join("") ||
                                "";
                        slot.block.textSignature = encodeTextSignature(item.id ?? "", item.phase);
                    }
                    else if (item.type === "function_call" &&
                        slot?.type === "toolCall" &&
                        slot.block.partialJson !== undefined) {
                        // Malformed arguments fail validation with the text the model sent (pi 1.3).
                        const parsed = parseToolCallArguments(item.arguments || slot.block.partialJson);
                        slot.block.arguments = parsed.arguments;
                        if (parsed.argumentsError !== undefined)
                            slot.block.argumentsError = parsed.argumentsError;
                        delete slot.block.partialJson;
                        pending.push({ type: "tool_call", toolCall: slot.block });
                    }
                    slots.delete(event.output_index);
                    break;
                }
                case "response.completed":
                case "response.incomplete":
                    finalize(event.response);
                    break;
                case "error":
                    throw new TauError("stream_error", `Error Code ${event.code ?? "unknown"}: ${event.message ?? "Unknown error"}`);
                case "response.failed": {
                    terminal = true;
                    const error = event.response?.error;
                    const reason = event.response?.incomplete_details?.reason;
                    throw new TauError("stream_error", error
                        ? `${error.code || "unknown"}: ${error.message || "no message"}`
                        : typeof reason === "string"
                            ? `incomplete: ${reason}`
                            : "Unknown error (no error details in response)");
                }
            }
        };
        try {
            while (!terminal) {
                throwIfAborted(request.signal);
                let readResult;
                try {
                    readResult = await withStallTimeout(reader.read(), platform, config.stallTimeoutMs, "Response stream");
                }
                catch (cause) {
                    throwIfAborted(request.signal);
                    if (cause instanceof TauError)
                        throw cause;
                    throw new TauError("stream_error", `Stream read failed: ${describeError(cause)}`, cause);
                }
                if (readResult.done)
                    break;
                if (!readResult.value)
                    continue;
                for (const sse of parser.push(decoder.decode(readResult.value))) {
                    handleEvent(sse.data);
                    if (terminal)
                        break;
                }
                yield* pending.splice(0);
            }
            if (!terminal) {
                const tail = decoder.flush();
                if (tail.length > 0)
                    for (const sse of parser.push(tail))
                        handleEvent(sse.data);
                for (const sse of parser.flush())
                    handleEvent(sse.data);
            }
            yield* pending.splice(0);
        }
        finally {
            try {
                await reader.cancel();
            }
            catch {
                // The stream is already finished or errored; nothing to cancel.
            }
        }
        if (!terminal)
            throw new TauError("stream_error", "OpenAI Responses stream ended before a terminal response event");
        if (final.stopReason === "error") {
            throw new TauError("stream_error", final.errorMessage ?? "The provider reported an error stop reason");
        }
        // The agent runs every call of the final message: a call whose output_item.done never came
        // may be cut off (pi).
        const unfinished = content.find((block) => block.type === "toolCall" && block.partialJson !== undefined);
        if (final.stopReason === "toolUse" && unfinished?.type === "toolCall") {
            throw new TauError("stream_error", `OpenAI Responses stream completed with an unfinished tool call: ${unfinished.name} (${unfinished.id})`);
        }
        const message = {
            role: "assistant",
            content: content,
            api: OPENAI_RESPONSES_API,
            provider: config.provider ?? "openai",
            model: request.model ?? config.model,
            usage,
            stopReason: final.stopReason,
            ...(final.rawStopReason !== undefined ? { rawStopReason: final.rawStopReason } : {}),
            ...(final.responseId !== undefined ? { responseId: final.responseId } : {}),
            timestamp: startedAt,
        };
        yield { type: "response_end", message };
    };
    transport.supportsHeaders = true;
    transport.supportsProviderEvents = true;
    return transport;
}
