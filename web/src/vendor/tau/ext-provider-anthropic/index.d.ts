import { type AgentMessage, type ChatTransport, type Platform, type TransportRequest } from "@yophon/tau-kernel";
/** The `api` field recorded on assistant messages, as in pi. */
export declare const ANTHROPIC_MESSAGES_API = "anthropic-messages";
export declare const ANTHROPIC_DEFAULT_BASE_URL = "https://api.anthropic.com";
/**
 * The output cap when nothing sets one: 32k for Claude 3.7 and Claude 4 or later, which
 * write that much in one reply (a large file in a tool call), else 8192 — older Claude
 * models and other services on this protocol (DeepSeek's) reject more. tau has no model
 * table to read it from (pi's model.maxTokens); models.json maxTokens overrides it.
 */
export declare function defaultMaxTokens(model: string): number;
/** Whether a model id (an OpenRouter-style `anthropic/` prefix and a dated suffix allowed) can turn thinking off. */
export declare function acceptsDisabledThinking(model: string): boolean;
/** Anthropic `output_config.effort` levels. */
export type AnthropicEffort = "low" | "medium" | "high" | "xhigh" | "max";
/** How a host maps its thinking level onto the wire: adaptive + effort (current models) or budget tokens (pre-4.6). */
export type AnthropicThinkingMode = "adaptive" | "budget";
/**
 * Map a shared thinking level (tau ThinkingLevel strings) onto transport config,
 * mirroring pi v0.80.3 anthropic-messages (forceAdaptiveThinking +
 * mapThinkingLevelToEffort). `undefined` clears the override. In adaptive mode
 * "none" sends `{type: "disabled"}` without effort on the models that accept it (Opus 5,
 * Haiku 5.5; pi 1.3), and no thinking with the lowest effort on the others (Opus 5.5,
 * Sonnet 5.5, Fable reject disabled thinking).
 */
export declare function anthropicThinkingForLevel(level: string | undefined, mode?: AnthropicThinkingMode, model?: string): Pick<AnthropicTransportConfig, "thinking" | "effort">;
export interface AnthropicTransportConfig {
    apiKey: string;
    /** Read per request, so hosts may reassign it between turns (CLI /model). */
    model: string;
    /** Defaults to https://api.anthropic.com. */
    baseUrl?: string;
    /** Output cap sent as max_tokens (an Anthropic-required field). Defaults to 8192. */
    maxTokens?: number;
    /**
     * Extended thinking: a token budget for budget-based models (pre-4.6), or
     * "adaptive" for current models (Claude 4.6+; 4.7+/5.x reject budget_tokens
     * with a 400). Omitted = thinking not requested (5.x still think adaptively).
     */
    thinking?: {
        budgetTokens: number;
    } | "adaptive" | "disabled";
    /**
     * Reasoning effort, sent as `output_config.effort` (GA, no beta). The depth
     * control for adaptive thinking and for models whose thinking is always on.
     */
    effort?: AnthropicEffort;
    /**
     * `thinking.display` for adaptive thinking. "summarized" (default, pi's
     * forceAdaptiveThinking) streams readable summaries; "omitted" returns empty
     * thinking text (the API default on 4.7+/5.x — a long silent pause).
     */
    thinkingDisplay?: "summarized" | "omitted";
    /**
     * "auto" (default) marks system, the last tool, and the last user message
     * with ephemeral cache_control — pi's placement. "off" sends no markers.
     */
    cacheControl?: "auto" | "off";
    /** Extra request headers, merged after the defaults. */
    headers?: Record<string, string>;
    /** Provider label recorded on assistant messages. Defaults to "anthropic". */
    provider?: string;
    /** Max silence (ms) for headers/next chunk, as in the kernel client. Default 120000; 0 disables. */
    stallTimeoutMs?: number;
    /**
     * How a request's thinking level maps onto the wire (P23d): adaptive + effort
     * (default, current models) or budget tokens (pre-4.6). Requests without a
     * thinking level use `thinking`/`effort` above.
     */
    thinkingMode?: AnthropicThinkingMode;
    /**
     * The model's context window. When set, max_tokens is held to the room left
     * after the estimated input minus 4096 tokens (pi clampMaxTokensToContext);
     * absent = no clamp.
     */
    contextWindow?: number;
}
/**
 * Port of pi's transformMessages, minus the vision downgrade (no model
 * registry; every Claude model tau targets accepts images): normalize
 * cross-model thinking/tool ids in a first pass, then drop errored/aborted
 * assistant turns and synthesize results for orphaned tool calls.
 */
export declare function transformMessagesForAnthropic(messages: AgentMessage[], target: {
    provider: string;
    model: string;
}): AgentMessage[];
interface WireCacheControl {
    type: "ephemeral";
}
type WireBlock = Record<string, unknown> & {
    type: string;
    cache_control?: WireCacheControl;
};
interface WireMessage {
    role: "user" | "assistant";
    content: string | WireBlock[];
}
export declare function messagesToWire(messages: AgentMessage[]): WireMessage[];
export declare function buildRequestBody(config: AnthropicTransportConfig, request: TransportRequest): Record<string, unknown>;
/**
 * Create a ChatTransport speaking the Anthropic Messages API. The config object
 * is read per request (never captured field-by-field), so hosts may mutate
 * `model` or `thinking` between turns.
 */
export declare function createAnthropicTransport(config: AnthropicTransportConfig, platform: Platform): ChatTransport;
export {};
