import type { ThinkingLevel } from "./extensions.js";
import { type AgentMessage, type AssistantMessage, type ToolCall, type ToolDefinition } from "./messages.js";
import type { Platform, PlatformResponse, TauAbortSignal } from "./platform.js";
export declare const COMPACTION_SUMMARY_PREFIX = "The conversation history before this point was compacted into the following summary:\n\n<summary>\n";
export declare const COMPACTION_SUMMARY_SUFFIX = "\n</summary>";
export declare const BRANCH_SUMMARY_PREFIX = "The following is a summary of a branch that this conversation came back from:\n\n<summary>\n";
export declare const BRANCH_SUMMARY_SUFFIX = "</summary>";
export interface OpenAICompatConfig {
    /** Base URL up to and including the version segment, e.g. "https://api.openai.com/v1". */
    baseUrl: string;
    /** BYOK API key, sent as a Bearer token. Omit for keyless local endpoints. */
    apiKey?: string;
    model: string;
    /** Model context window in tokens; enables automatic compaction (tau has no model database). */
    contextWindow?: number;
    /**
     * The model's output limit, when the host knows it (models.json `maxTokens`, a protocol's
     * default): summary requests use it, as pi uses model.maxTokens (pi 1.4). Not sent on turns.
     */
    maxOutputTokens?: number;
    /** Provider label recorded on assistant messages (pi's `provider` field). Defaults to "openai-compat". */
    provider?: string;
    /**
     * API label recorded on assistant messages (pi's `api` field). Defaults to
     * "openai-completions"; hosts assembling a non-OpenAI transport (P16) set it
     * so mid-stream partial messages carry the right lineage.
     */
    api?: string;
    /** Extra request headers, merged after the defaults. */
    headers?: Record<string, string>;
    /** Extra top-level body fields (temperature, provider-specific knobs, …). */
    extraBody?: Record<string, unknown>;
    /**
     * Send later system messages in place (pi supportsMidConvoSystemMessages).
     * Default false: the replayed prompt and tools are folded into the leading
     * system message, so the wire format matches a plain system prompt.
     */
    midConversationSystemMessages?: boolean;
    /** Set false for providers that reject stream_options. Defaults to true. */
    includeUsage?: boolean;
    /**
     * Send `reasoning_content: ""` on replayed assistant turns that carry no
     * reasoning. DeepSeek thinking models reject a multi-turn tool conversation
     * whose assistant messages lack the field (HTTP 400). Default: on when
     * `baseUrl` points at deepseek.com (pi v0.80.3 behavior), off elsewhere.
     */
    replayEmptyReasoning?: boolean;
    /**
     * Max silence (ms) tolerated waiting for response headers or the next stream
     * chunk before failing with a timeout — the defense against servers that hang
     * without erroring. Default 120000; 0 disables. Requires Platform.sleep
     * (absent on bare engines → no stall protection, silently).
     */
    stallTimeoutMs?: number;
    /**
     * Whether the endpoint ends every stream with a finish_reason (pi
     * compat.supportsFinishReason). Default true: a stream that ends without one
     * is an error ("Stream ended without finish_reason", retried), and
     * content_filter or unknown reasons are errors too (P21 ruling 8). false
     * infers stop/toolUse for endpoints that omit it.
     */
    supportsFinishReason?: boolean;
    /**
     * Body field for the output cap of requests that set one (summaries) — pi
     * compat.maxTokensField. Default "max_tokens" (deviation: pi defaults to
     * max_completion_tokens and sniffs URLs, which tau does not, P16). OpenAI's
     * reasoning models need "max_completion_tokens".
     */
    maxTokensField?: "max_tokens" | "max_completion_tokens";
    /**
     * What the model accepts (pi Model.input, declared by the host — tau has no
     * model registry). Without "image", image blocks are replaced by pi's
     * placeholder text instead of being sent. Absent = images are sent.
     */
    input?: ("text" | "image")[];
}
/** The `api` field recorded on assistant messages, as in pi. */
export declare const OPENAI_COMPLETIONS_API = "openai-completions";
export type { JsonSchema, ToolDefinition } from "./messages.js";
export type ChatStreamEvent = {
    type: "text_delta";
    delta: string;
} | {
    type: "reasoning_delta";
    delta: string;
}
/**
 * A tool call's arguments so far, while they stream (P38b, pi's toolcall_delta): hosts
 * show the call before it is complete. `index` tells calls of one response apart.
 */
 | {
    type: "tool_call_delta";
    index: number;
    id?: string;
    name: string;
    argumentsText: string;
} | {
    type: "tool_call";
    toolCall: ToolCall;
} | {
    type: "response_end";
    message: AssistantMessage;
};
/**
 * Removes unpaired UTF-16 surrogates, verbatim from pi utils/sanitize-unicode.ts:
 * they make many providers reject the JSON body. Paired surrogates (emoji) stay.
 */
export declare function sanitizeSurrogates(text: string): string;
/** pi's user message carrying the images of the tool results just before it. */
export declare const TOOL_RESULT_IMAGES_TEXT = "Attached image(s) from tool result:";
/**
 * Tool call ids for Chat Completions replay, from pi's normalizeToolCallId:
 * Responses-style `call_id|item_id` ids are sanitized and held to 40 characters
 * (keeping calls that share a call_id distinct); OpenAI's own endpoint caps
 * plain ids at 40 too.
 */
export declare function normalizeToolCallId(id: string, provider: string): string;
/** Fast non-cryptographic hash, verbatim from pi utils/hash.ts. */
export declare function shortHash(str: string): string;
/**
 * Resolve a transcript for the wire (pi resolveTranscript): without
 * mid-conversation system messages the replayed system state is folded into
 * one leading system message; with them, later system messages stay in place.
 * Tools are always the replayed current set.
 */
export declare function resolveTranscript(messages: readonly AgentMessage[], midConversationSystemMessages: boolean | undefined): {
    systemPrompt: string;
    messages: AgentMessage[];
    tools: ToolDefinition[];
};
/**
 * Merge request-level header changes onto a header set (pi ProviderHeaders): a
 * string adds or replaces, null deletes; names compare case-insensitively.
 */
export declare function applyHeaderChanges(headers: Record<string, string>, changes: Record<string, string | null> | undefined): Record<string, string>;
/**
 * Race a pending step against the stall clock. Losers are always cleaned up:
 * the sleep is aborted when the step wins, and a timed-out step's eventual
 * rejection is marked handled so it cannot become an unhandled rejection.
 * Exported for alternative transports (P16) so they share the same watchdog.
 */
export declare function withStallTimeout<T>(step: Promise<T>, platform: Platform, stallTimeoutMs: number | undefined, what: string): Promise<T>;
/**
 * Stream a chat completion from any OpenAI-compatible endpoint. Yields text and
 * reasoning deltas as they arrive, complete tool calls once assembled, and a
 * final `response_end` carrying the full assistant message (pi shape: ordered
 * content blocks, Usage, StopReason).
 */
export declare function streamChatCompletion(platform: Platform, config: OpenAICompatConfig, messages: readonly AgentMessage[], options?: {
    signal?: TauAbortSignal;
    maxTokens?: number;
    headers?: Record<string, string | null>;
    thinkingLevel?: ThinkingLevel;
    /** Model of this request; defaults to config.model (P23d, Agent.setModel). */
    model?: string;
} & Pick<TransportRequest, "onPayload" | "onResponse" | "onStreamEvent">): AsyncGenerator<ChatStreamEvent>;
/**
 * One LLM request (P22, pi TranscriptContext). `messages` is the transcript
 * the agent rebuilt from the session log, system messages included: the
 * leading one carries the prompt and tool declarations, later ones patch them.
 * Transports fold it into their wire shape (resolveTranscript /
 * collapseSystemMessages); there are no separate prompt or tool fields.
 */
export interface TransportRequest {
    messages: AgentMessage[];
    signal?: TauAbortSignal;
    /** Cap on generated tokens (summarization requests set it); maps to the protocol's own knob. */
    maxTokens?: number;
    /**
     * Header changes from before_provider_headers (P23d, pi ProviderHeaders): a
     * string adds or replaces, null deletes, case-insensitively, after the
     * transport's configured headers. Only sent to transports declaring
     * `supportsHeaders`.
     */
    headers?: Record<string, string | null>;
    /** "none" asks not to write a prompt cache (pi cacheRetention; summaries set it). Transports without cache writes ignore it. */
    cacheRetention?: "none";
    /** Thinking level of this request (P23d, pi reasoning): OpenAI-compatible → reasoning_effort, Anthropic → effort/budget. */
    thinkingLevel?: ThinkingLevel;
    /** Model of this request (P23d, Agent.setModel); transports fall back to their configured model. */
    model?: string;
    /**
     * P40d (pi before_provider_request): the request body just before it is sent; a value
     * returned replaces it. Only sent to transports declaring `supportsProviderEvents`.
     */
    onPayload?: (payload: unknown) => Promise<unknown>;
    /** P40d (pi after_provider_response): the status and headers, before the stream is read. */
    onResponse?: (response: {
        status: number;
        headers: Record<string, string>;
    }) => Promise<void>;
    /** P40d (pi provider_stream_event): each parsed stream event, before it is converted. */
    onStreamEvent?: (data: unknown) => void;
}
/**
 * Streams one LLM response. Yields the same event union as
 * streamChatCompletion; failures must be normalized to TauError (D14) so the
 * agent converts them into stopReason error/aborted messages.
 */
export type ChatTransport = ((request: TransportRequest) => AsyncGenerator<ChatStreamEvent>) & {
    /** The transport applies TransportRequest.headers; required before before_provider_headers handlers may run (P23d). */
    supportsHeaders?: boolean;
    /** The transport calls onPayload / onResponse / onStreamEvent (P40d); required before those handlers may run. */
    supportsProviderEvents?: boolean;
};
/** A response's headers as a plain object (P40d), when the platform can list them; else empty. */
export declare function responseHeaders(response: PlatformResponse): Record<string, string>;
/** The default transport: the OpenAI-compatible client, verbatim. */
export declare function createOpenAICompatTransport(platform: Platform, config: OpenAICompatConfig): ChatTransport;
