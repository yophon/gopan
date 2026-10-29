// Anthropic Messages API transport over the kernel's Platform seam — the
// protocol is hand-written against the streaming Messages API instead of using
// @anthropic-ai/sdk, which assumes Node/browser globals and cannot run on bare
// engines (QuickJS / JavaScriptCore); same route as ../../ext-mcp-http.
//
// Message, event, stop-reason and usage mapping mirror pi's
// packages/ai/src/api/anthropic-messages.ts + transform-messages.ts (v0.80.3):
// tau's message model IS pi's shape (D13), so the conversion ports nearly
// verbatim. Deviations, each deliberate:
//   - no model registry: config carries model/maxTokens/thinking directly (D2/D3)
//   - no OAuth / Claude-Code stealth naming / beta headers / copilot paths
//   - failures throw TauError (D14) instead of pi's error stream events; the
//     agent turns them into stopReason error/aborted messages
//   - SSE parsing reuses the kernel SseParser instead of pi's inline decoder
import { applyHeaderChanges, BRANCH_SUMMARY_PREFIX, BRANCH_SUMMARY_SUFFIX, COMPACTION_SUMMARY_PREFIX, COMPACTION_SUMMARY_SUFFIX, describeError, emptyUsage, estimateContextTokens, HttpError, parseToolCallArguments, resolveTranscript, responseHeaders, SseParser, TauError, withStallTimeout, } from "@yophon/tau-kernel";
/** The `api` field recorded on assistant messages, as in pi. */
export const ANTHROPIC_MESSAGES_API = "anthropic-messages";
export const ANTHROPIC_DEFAULT_BASE_URL = "https://api.anthropic.com";
/** Protocol lock, as ext-mcp-http locks the MCP version: bump deliberately, never silently. */
const ANTHROPIC_VERSION = "2023-06-01";
/** Anthropic requires max_tokens; applied when neither the request nor the config caps output. */
const DEFAULT_MAX_TOKENS = 8192;
/** Claude 3.7 and every Claude 4 or later model take at least this much output (Opus 4 is the lowest at 32k). */
const MODERN_CLAUDE_MAX_TOKENS = 32_000;
/**
 * The output cap when nothing sets one: 32k for Claude 3.7 and Claude 4 or later, which
 * write that much in one reply (a large file in a tool call), else 8192 — older Claude
 * models and other services on this protocol (DeepSeek's) reject more. tau has no model
 * table to read it from (pi's model.maxTokens); models.json maxTokens overrides it.
 */
export function defaultMaxTokens(model) {
    const id = model.toLowerCase();
    if (/claude-3-7/.test(id))
        return MODERN_CLAUDE_MAX_TOKENS;
    const family = /claude-(?:opus|sonnet|haiku)-(\d+)/.exec(id) ?? /claude-(\d+)(?:-\d+)?-(?:opus|sonnet|haiku)/.exec(id);
    return family && Number(family[1]) >= 4 ? MODERN_CLAUDE_MAX_TOKENS : DEFAULT_MAX_TOKENS;
}
/** Claude models that accept `thinking: {type: "disabled"}` (pi 1.3 generate-models). */
const DISABLED_THINKING_MODELS = new Set(["claude-opus-5", "claude-haiku-5-5", "claude-haiku-5.5"]);
/** Whether a model id (an OpenRouter-style `anthropic/` prefix and a dated suffix allowed) can turn thinking off. */
export function acceptsDisabledThinking(model) {
    const id = model
        .toLowerCase()
        .replace(/^~?anthropic\//, "")
        .replace(/-\d{8}$/, "");
    return DISABLED_THINKING_MODELS.has(id);
}
/** pi's floor for output tokens when clamping a thinking budget into max_tokens. */
const MIN_OUTPUT_TOKENS = 1024;
/** pi's default thinking budgets (simple-options.ts) for budget-mode models; xhigh clamps to high. */
const THINKING_BUDGETS = { minimal: 1024, low: 2048, medium: 8192, high: 16384, xhigh: 16384, max: 16384 };
/**
 * Map a shared thinking level (tau ThinkingLevel strings) onto transport config,
 * mirroring pi v0.80.3 anthropic-messages (forceAdaptiveThinking +
 * mapThinkingLevelToEffort). `undefined` clears the override. In adaptive mode
 * "none" sends `{type: "disabled"}` without effort on the models that accept it (Opus 5,
 * Haiku 5.5; pi 1.3), and no thinking with the lowest effort on the others (Opus 5.5,
 * Sonnet 5.5, Fable reject disabled thinking).
 */
export function anthropicThinkingForLevel(level, mode = "adaptive", model) {
    if (level === undefined)
        return { thinking: undefined, effort: undefined };
    if (mode === "budget") {
        const budget = THINKING_BUDGETS[level];
        return { thinking: budget === undefined ? undefined : { budgetTokens: budget }, effort: undefined };
    }
    if (level === "none") {
        return model !== undefined && acceptsDisabledThinking(model)
            ? { thinking: "disabled", effort: undefined }
            : { thinking: undefined, effort: "low" };
    }
    const effort = level === "minimal" || level === "low"
        ? "low"
        : level === "medium"
            ? "medium"
            : level === "xhigh"
                ? "xhigh"
                : level === "max"
                    ? "max"
                    : "high";
    return { thinking: "adaptive", effort };
}
/** pi's safety margin between estimated input and the context window when clamping max_tokens. */
const CONTEXT_SAFETY_TOKENS = 4096;
// ---------------------------------------------------------------------------
// Message transformation (pi transform-messages.ts): drop unfinished turns,
// synthesize missing tool results, normalize cross-model content.
// ---------------------------------------------------------------------------
/** Anthropic requires tool ids matching ^[a-zA-Z0-9_-]+$ (max 64); as in pi. */
function normalizeToolCallId(id) {
    return id.replace(/[^a-zA-Z0-9_-]/g, "_").slice(0, 64);
}
/** Lone UTF-16 surrogate halves (typically from truncation) become U+FFFD, as pi sanitizes. */
function sanitizeSurrogates(text) {
    if (!/[\uD800-\uDFFF]/.test(text))
        return text;
    let out = "";
    for (let i = 0; i < text.length; i++) {
        const code = text.charCodeAt(i);
        if (code >= 0xd800 && code <= 0xdbff) {
            const next = i + 1 < text.length ? text.charCodeAt(i + 1) : 0;
            if (next >= 0xdc00 && next <= 0xdfff) {
                out += text[i] + text[i + 1];
                i++;
            }
            else {
                out += "�";
            }
        }
        else if (code >= 0xdc00 && code <= 0xdfff) {
            out += "�";
        }
        else {
            out += text[i];
        }
    }
    return out;
}
/**
 * Port of pi's transformMessages, minus the vision downgrade (no model
 * registry; every Claude model tau targets accepts images): normalize
 * cross-model thinking/tool ids in a first pass, then drop errored/aborted
 * assistant turns and synthesize results for orphaned tool calls.
 */
export function transformMessagesForAnthropic(messages, target) {
    const toolCallIdMap = new Map();
    const transformed = messages.map((msg) => {
        if (msg.role === "toolResult") {
            const normalizedId = toolCallIdMap.get(msg.toolCallId);
            if (normalizedId && normalizedId !== msg.toolCallId) {
                return { ...msg, toolCallId: normalizedId };
            }
            return msg;
        }
        if (msg.role !== "assistant")
            return msg;
        const isSameModel = msg.provider === target.provider && msg.api === ANTHROPIC_MESSAGES_API && msg.model === target.model;
        const content = msg.content.flatMap((block) => {
            if (block.type === "thinking") {
                // Thinking of another model is dropped: sent as text, the new model took it for part
                // of its reply (pi 1.3). Redacted and signed blocks of this model are kept for replay.
                if (!isSameModel)
                    return [];
                if (block.redacted || block.thinkingSignature)
                    return [block];
                if (!block.thinking || block.thinking.trim() === "")
                    return [];
                return [block];
            }
            if (block.type === "toolCall" && !isSameModel) {
                const normalizedId = normalizeToolCallId(block.id);
                if (normalizedId !== block.id) {
                    toolCallIdMap.set(block.id, normalizedId);
                    return [{ ...block, id: normalizedId }];
                }
            }
            return [block];
        });
        return { ...msg, content };
    });
    // Second pass: skip unfinished turns, close every tool call with a result.
    const result = [];
    let pendingToolCalls = [];
    let existingToolResultIds = new Set();
    const insertSyntheticToolResults = () => {
        for (const toolCall of pendingToolCalls) {
            if (!existingToolResultIds.has(toolCall.id)) {
                result.push({
                    role: "toolResult",
                    toolCallId: toolCall.id,
                    toolName: toolCall.name,
                    content: [{ type: "text", text: "No result provided" }],
                    isError: true,
                    timestamp: Date.now(),
                });
            }
        }
        pendingToolCalls = [];
        existingToolResultIds = new Set();
    };
    for (const msg of transformed) {
        if (msg.role === "assistant") {
            insertSyntheticToolResults();
            // Errored/aborted turns are incomplete (dangling tool calls, unsigned
            // thinking) and must not be replayed; the model retries from the last
            // valid state, as in pi.
            if (msg.stopReason === "error" || msg.stopReason === "aborted")
                continue;
            const toolCalls = msg.content.filter((block) => block.type === "toolCall");
            if (toolCalls.length > 0) {
                pendingToolCalls = toolCalls;
                existingToolResultIds = new Set();
            }
            result.push(msg);
        }
        else if (msg.role === "toolResult") {
            existingToolResultIds.add(msg.toolCallId);
            result.push(msg);
        }
        else {
            // user/custom/summary all reach the wire as user turns, interrupting any
            // pending tool flow.
            insertSyntheticToolResults();
            result.push(msg);
        }
    }
    insertSyntheticToolResults();
    return result;
}
function textBlock(text) {
    return { type: "text", text: sanitizeSurrogates(text) };
}
function imageBlock(image) {
    return { type: "image", source: { type: "base64", media_type: image.mimeType, data: image.data } };
}
function userContentToWire(content) {
    if (typeof content === "string") {
        return content.trim().length > 0 ? sanitizeSurrogates(content) : undefined;
    }
    const blocks = content
        .map((block) => (block.type === "text" ? textBlock(block.text) : imageBlock(block)))
        .filter((block) => block.type !== "text" || String(block.text).trim().length > 0);
    return blocks.length > 0 ? blocks : undefined;
}
/** pi's convertContentBlocks: text-only results stay a string; images force a block array. */
const ERROR_TOOL_IMAGE_PLACEHOLDER = "(image attached after the tool results)";
/** The content with each run of images replaced by one note (pi replaceImagesWithPlaceholder). */
function withoutImages(content, note) {
    const result = [];
    let previousWasImage = false;
    for (const block of content) {
        if (block.type === "image") {
            if (!previousWasImage)
                result.push({ type: "text", text: note });
            previousWasImage = true;
        }
        else {
            result.push(block);
            previousWasImage = false;
        }
    }
    return result;
}
function toolResultContentToWire(content) {
    const hasImages = content.some((block) => block.type === "image");
    if (!hasImages) {
        return sanitizeSurrogates(content.map((block) => block.text).join("\n"));
    }
    const blocks = content.map((block) => (block.type === "text" ? textBlock(block.text) : imageBlock(block)));
    if (!blocks.some((block) => block.type === "text")) {
        blocks.unshift(textBlock("(see attached image)"));
    }
    return blocks;
}
function assistantContentToWire(content) {
    const blocks = [];
    for (const block of content) {
        if (block.type === "text") {
            if (block.text.trim().length === 0)
                continue;
            blocks.push(textBlock(block.text));
        }
        else if (block.type === "thinking") {
            if (block.redacted) {
                blocks.push({ type: "redacted_thinking", data: block.thinkingSignature ?? "" });
                continue;
            }
            const signed = block.thinkingSignature !== undefined && block.thinkingSignature.trim().length > 0;
            // A signed block must be replayed even when its text is empty: 4.7+/5.x default
            // to display "omitted", so every block arrives empty-but-signed, and dropping
            // them threw away all reasoning (and breaks thinking + tool_use turns).
            if (signed) {
                blocks.push({
                    type: "thinking",
                    thinking: sanitizeSurrogates(block.thinking),
                    signature: block.thinkingSignature,
                });
                continue;
            }
            if (block.thinking.trim().length === 0)
                continue;
            // Unsigned thinking (aborted stream, foreign transport) degrades to
            // text — Anthropic rejects thinking blocks without a signature.
            blocks.push(textBlock(block.thinking));
        }
        else {
            blocks.push({ type: "tool_use", id: block.id, name: block.name, input: block.arguments ?? {} });
        }
    }
    return blocks;
}
export function messagesToWire(messages) {
    const wire = [];
    for (let i = 0; i < messages.length; i++) {
        const msg = messages[i];
        switch (msg.role) {
            case "user":
            case "custom": {
                // Custom messages reach the model as user turns, as in pi's convertToLlm.
                const content = userContentToWire(msg.content);
                if (content !== undefined)
                    wire.push({ role: "user", content });
                break;
            }
            case "system":
                // Collapsed into the top-level system field before conversion (P22).
                break;
            case "compactionSummary":
                wire.push({ role: "user", content: COMPACTION_SUMMARY_PREFIX + msg.summary + COMPACTION_SUMMARY_SUFFIX });
                break;
            case "branchSummary":
                wire.push({ role: "user", content: BRANCH_SUMMARY_PREFIX + msg.summary + BRANCH_SUMMARY_SUFFIX });
                break;
            case "assistant": {
                const blocks = assistantContentToWire(msg.content);
                if (blocks.length > 0)
                    wire.push({ role: "assistant", content: blocks });
                break;
            }
            case "toolResult": {
                // Fold consecutive tool results into one user turn, as in pi (required
                // by some Anthropic-compatible endpoints).
                const results = [];
                // Anthropic rejects images in an error result: they follow the turn's results (pi 1.3).
                const afterResults = [];
                let j = i;
                while (j < messages.length) {
                    const candidate = messages[j];
                    if (candidate.role !== "toolResult")
                        break;
                    const images = candidate.isError ? candidate.content.filter((block) => block.type === "image") : [];
                    results.push({
                        type: "tool_result",
                        tool_use_id: candidate.toolCallId,
                        content: toolResultContentToWire(images.length > 0 ? withoutImages(candidate.content, ERROR_TOOL_IMAGE_PLACEHOLDER) : candidate.content),
                        is_error: candidate.isError,
                    });
                    if (images.length > 0) {
                        afterResults.push(textBlock(`Images from the error result of tool call ${candidate.toolCallId} (${candidate.toolName}):`), ...images.map((image) => imageBlock(image)));
                    }
                    j++;
                }
                i = j - 1;
                wire.push({ role: "user", content: [...results, ...afterResults] });
                break;
            }
        }
    }
    return wire;
}
function toolsToWire(tools, cacheControl) {
    return tools.map((tool, index) => {
        const schema = tool.parameters;
        return {
            name: tool.name,
            description: tool.description,
            input_schema: {
                type: "object",
                properties: schema.properties ?? {},
                required: schema.required ?? [],
            },
            ...(cacheControl && index === tools.length - 1 ? { cache_control: cacheControl } : {}),
        };
    });
}
/** pi's placement: the last block of the last user message caches the conversation history. */
function markLastUserMessage(wire, cacheControl) {
    const last = wire[wire.length - 1];
    if (last?.role !== "user")
        return;
    if (typeof last.content === "string") {
        last.content = [{ ...textBlock(last.content), cache_control: cacheControl }];
        return;
    }
    const lastBlock = last.content[last.content.length - 1];
    if (lastBlock)
        lastBlock.cache_control = cacheControl;
}
export function buildRequestBody(config, request) {
    const provider = config.provider ?? "anthropic";
    // The Messages API has no mid-conversation system role: the transcript's replayed
    // prompt and tools fold into the top-level system and tools fields (P22).
    const transcript = resolveTranscript(request.messages, false);
    const model = request.model ?? config.model;
    const transformed = transformMessagesForAnthropic(transcript.messages, { provider, model });
    // A one-off request (a summary) asks for no cache write: its prefix is never reused (pi 0.82.0).
    const cacheControl = (config.cacheControl ?? "auto") === "auto" && request.cacheRetention !== "none" ? { type: "ephemeral" } : undefined;
    const wireMessages = messagesToWire(transformed);
    if (cacheControl)
        markLastUserMessage(wireMessages, cacheControl);
    // The request's thinking level, when it has one, decides thinking and effort (P23d).
    const thinkingConfig = request.thinkingLevel !== undefined
        ? anthropicThinkingForLevel(request.thinkingLevel, config.thinkingMode ?? "adaptive", model)
        : { thinking: config.thinking, effort: config.effort };
    let maxTokens = request.maxTokens ?? config.maxTokens ?? defaultMaxTokens(model);
    const budgetThinking = typeof thinkingConfig.thinking === "object" ? thinkingConfig.thinking : undefined;
    if (budgetThinking && request.maxTokens !== undefined) {
        // pi adjustMaxTokensForThinking: an explicit output cap (summaries) gets the thinking
        // budget on top, so reasoning cannot eat the answer; config.maxTokens bounds it.
        maxTokens = Math.min(request.maxTokens + budgetThinking.budgetTokens, config.maxTokens ?? Number.POSITIVE_INFINITY);
    }
    if (config.contextWindow !== undefined && config.contextWindow > 0) {
        const available = config.contextWindow - estimateContextTokens(transcript.messages).tokens - CONTEXT_SAFETY_TOKENS;
        maxTokens = Math.min(maxTokens, Math.max(1, available));
    }
    const body = {
        model,
        messages: wireMessages,
        max_tokens: maxTokens,
        stream: true,
    };
    if (transcript.systemPrompt !== "") {
        body.system = [
            {
                type: "text",
                text: sanitizeSurrogates(transcript.systemPrompt),
                ...(cacheControl ? { cache_control: cacheControl } : {}),
            },
        ];
    }
    if (transcript.tools.length > 0) {
        body.tools = toolsToWire(transcript.tools, cacheControl);
    }
    if (thinkingConfig.thinking === "disabled") {
        body.thinking = { type: "disabled" };
    }
    else if (thinkingConfig.thinking === "adaptive") {
        body.thinking = { type: "adaptive", display: config.thinkingDisplay ?? "summarized" };
    }
    else if (budgetThinking) {
        // pi's clamp: the budget must leave room for output inside max_tokens;
        // when it cannot (tiny caps), thinking is dropped entirely.
        const budget = Math.min(budgetThinking.budgetTokens, Math.max(0, maxTokens - MIN_OUTPUT_TOKENS));
        if (budget > 0)
            body.thinking = { type: "enabled", budget_tokens: budget };
    }
    if (thinkingConfig.effort)
        body.output_config = { effort: thinkingConfig.effort };
    return body;
}
const ANTHROPIC_MESSAGE_EVENTS = new Set([
    "message_start",
    "message_delta",
    "message_stop",
    "content_block_start",
    "content_block_delta",
    "content_block_stop",
]);
/** Verbatim pi mapping; unknown values fail loudly so new API enums surface as errors. */
function mapStopReason(reason, stopDetails) {
    switch (reason) {
        case "end_turn":
            return { stopReason: "stop" };
        case "max_tokens":
            return { stopReason: "length" };
        case "tool_use":
            return { stopReason: "toolUse" };
        case "refusal":
            return {
                stopReason: "error",
                errorMessage: stopDetails?.explanation || "The model refused to complete the request",
            };
        case "pause_turn": // Stop is good enough -> resubmit
            return { stopReason: "stop" };
        case "stop_sequence":
            return { stopReason: "stop" };
        case "sensitive":
            return { stopReason: "error", errorMessage: "The response was flagged by safety filters" };
        default:
            throw new TauError("invalid_response", `Unhandled stop reason: ${reason}`);
    }
}
function throwIfAborted(signal) {
    if (signal?.aborted)
        throw new TauError("aborted", "Request aborted");
}
/**
 * Create a ChatTransport speaking the Anthropic Messages API. The config object
 * is read per request (never captured field-by-field), so hosts may mutate
 * `model` or `thinking` between turns.
 */
export function createAnthropicTransport(config, platform) {
    const transport = async function* streamAnthropicMessages(request) {
        throwIfAborted(request.signal);
        // pi stamps the message at request start.
        const startedAt = Date.now();
        const baseUrl = (config.baseUrl ?? ANTHROPIC_DEFAULT_BASE_URL).replace(/\/+$/, "");
        // before_provider_headers changes go after the configured headers (P23d, pi).
        const headers = applyHeaderChanges(applyHeaderChanges({
            "content-type": "application/json",
            accept: "application/json",
            "anthropic-version": ANTHROPIC_VERSION,
            "x-api-key": config.apiKey,
        }, config.headers), request.headers);
        const body = buildRequestBody(config, request);
        // before_provider_request (P40d, pi) may replace the body.
        const payload = request.onPayload ? await request.onPayload(body) : body;
        let response;
        try {
            response = await withStallTimeout(platform.fetch(`${baseUrl}/v1/messages`, {
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
        if (!response.ok) {
            throw new HttpError(response.status, await response.text());
        }
        if (!response.body) {
            throw new TauError("stream_error", "Response has no body stream");
        }
        const reader = response.body.getReader();
        const decoder = platform.createUtf8Decoder();
        const parser = new SseParser();
        const content = [];
        const blocksByIndex = new Map();
        const usage = emptyUsage();
        // Object-held so closure writes are visible to the post-loop checks
        // (TypeScript does not track closure assignments to a plain let).
        const finalState = { stopReason: "stop" };
        let sawMessageStart = false;
        let done = false;
        const pendingEvents = [];
        const applyUsage = (wire) => {
            if (!wire)
                return;
            // Only overwrite what the event carries (pi: proxies omit fields in message_delta).
            if (wire.input_tokens != null)
                usage.input = wire.input_tokens;
            if (wire.output_tokens != null)
                usage.output = wire.output_tokens;
            if (wire.cache_read_input_tokens != null)
                usage.cacheRead = wire.cache_read_input_tokens;
            if (wire.cache_creation_input_tokens != null)
                usage.cacheWrite = wire.cache_creation_input_tokens;
            // Anthropic sends no total; computed from components, as in pi.
            usage.totalTokens = usage.input + usage.output + usage.cacheRead + usage.cacheWrite;
        };
        const handleEvent = (eventName, data) => {
            if (eventName === "error") {
                let message = data;
                try {
                    const parsed = JSON.parse(data);
                    message = parsed.error?.message ?? data;
                }
                catch {
                    // Keep the raw payload when it is not JSON.
                }
                throw new TauError("stream_error", message);
            }
            if (!eventName || !ANTHROPIC_MESSAGE_EVENTS.has(eventName))
                return; // ping etc.
            let event;
            try {
                event = JSON.parse(data);
            }
            catch (cause) {
                throw new TauError("invalid_response", `Failed to parse stream event: ${data.slice(0, 200)}`, cause);
            }
            request.onStreamEvent?.(event);
            switch (event.type) {
                case "message_start": {
                    sawMessageStart = true;
                    if (event.message?.id)
                        finalState.responseId = event.message.id;
                    applyUsage(event.message?.usage);
                    break;
                }
                case "content_block_start": {
                    const index = event.index ?? 0;
                    const block = event.content_block;
                    if (block?.type === "text") {
                        const item = { type: "text", text: "" };
                        content.push(item);
                        blocksByIndex.set(index, item);
                    }
                    else if (block?.type === "thinking") {
                        const item = { type: "thinking", thinking: "", thinkingSignature: "" };
                        content.push(item);
                        blocksByIndex.set(index, item);
                    }
                    else if (block?.type === "redacted_thinking") {
                        const item = {
                            type: "thinking",
                            thinking: "[Reasoning redacted]",
                            thinkingSignature: block.data ?? "",
                            redacted: true,
                        };
                        content.push(item);
                        blocksByIndex.set(index, item);
                    }
                    else if (block?.type === "tool_use") {
                        const item = {
                            type: "toolCall",
                            id: block.id ?? `call_${index}`,
                            name: block.name ?? "",
                            arguments: block.input !== null && typeof block.input === "object" && !Array.isArray(block.input)
                                ? block.input
                                : {},
                            partialJson: "",
                        };
                        content.push(item);
                        blocksByIndex.set(index, item);
                    }
                    // Unknown block types are tolerated and skipped (forward compatibility).
                    break;
                }
                case "content_block_delta": {
                    const block = blocksByIndex.get(event.index ?? 0);
                    const delta = event.delta;
                    if (!block || !delta)
                        break;
                    if (delta.type === "text_delta" && block.type === "text" && typeof delta.text === "string") {
                        block.text += delta.text;
                        pendingEvents.push({ type: "text_delta", delta: delta.text });
                    }
                    else if (delta.type === "thinking_delta" &&
                        block.type === "thinking" &&
                        typeof delta.thinking === "string") {
                        block.thinking += delta.thinking;
                        pendingEvents.push({ type: "reasoning_delta", delta: delta.thinking });
                    }
                    else if (delta.type === "input_json_delta" && block.type === "toolCall") {
                        block.partialJson = (block.partialJson ?? "") + (delta.partial_json ?? "");
                        pendingEvents.push({
                            type: "tool_call_delta",
                            index: event.index ?? 0,
                            id: block.id,
                            name: block.name,
                            argumentsText: block.partialJson,
                        });
                    }
                    else if (delta.type === "signature_delta" && block.type === "thinking") {
                        block.thinkingSignature = (block.thinkingSignature ?? "") + (delta.signature ?? "");
                    }
                    break;
                }
                case "content_block_stop": {
                    const block = blocksByIndex.get(event.index ?? 0);
                    if (block?.type === "toolCall") {
                        // Arguments parse once, when the block completes (accumulate-then-parse).
                        if (block.partialJson && block.partialJson !== "") {
                            // Malformed arguments fail validation with the text the model sent (pi 1.3).
                            const parsed = parseToolCallArguments(block.partialJson);
                            block.arguments = parsed.arguments;
                            if (parsed.argumentsError !== undefined)
                                block.argumentsError = parsed.argumentsError;
                        }
                        delete block.partialJson;
                        pendingEvents.push({ type: "tool_call", toolCall: block });
                    }
                    break;
                }
                case "message_delta": {
                    if (event.delta?.stop_reason) {
                        const mapped = mapStopReason(event.delta.stop_reason, event.delta.stop_details);
                        finalState.stopReason = mapped.stopReason;
                        finalState.rawStopReason = event.delta.stop_reason;
                        if (mapped.errorMessage)
                            finalState.errorMessage = mapped.errorMessage;
                    }
                    applyUsage(event.usage);
                    break;
                }
                case "message_stop": {
                    done = true;
                    break;
                }
            }
        };
        try {
            while (!done) {
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
                    handleEvent(sse.event, sse.data);
                    if (done)
                        break;
                }
                yield* pendingEvents.splice(0);
            }
            if (!done) {
                const tail = decoder.flush();
                if (tail.length > 0) {
                    for (const sse of parser.push(tail))
                        handleEvent(sse.event, sse.data);
                }
                for (const sse of parser.flush())
                    handleEvent(sse.event, sse.data);
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
        if (sawMessageStart && !done) {
            // pi's wording — also matched by the kernel retry classifier.
            throw new TauError("stream_error", "Anthropic stream ended before message_stop");
        }
        if (finalState.stopReason === "error") {
            throw new TauError("stream_error", finalState.errorMessage ?? "The provider reported an error stop reason");
        }
        const message = {
            role: "assistant",
            content: content,
            api: ANTHROPIC_MESSAGES_API,
            provider: config.provider ?? "anthropic",
            model: config.model,
            usage,
            stopReason: finalState.stopReason,
            ...(finalState.rawStopReason !== undefined ? { rawStopReason: finalState.rawStopReason } : {}),
            ...(finalState.responseId !== undefined ? { responseId: finalState.responseId } : {}),
            timestamp: startedAt,
        };
        yield { type: "response_end", message };
    };
    transport.supportsHeaders = true;
    transport.supportsProviderEvents = true;
    return transport;
}
