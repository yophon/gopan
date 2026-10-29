import { type AgentMessage, type ChatTransport, type Platform, type ThinkingLevel, type TransportRequest } from "@yophon/tau-kernel";
/** The `api` field recorded on assistant messages, as in pi. */
export declare const OPENAI_RESPONSES_API = "openai-responses";
export declare const OPENAI_DEFAULT_BASE_URL = "https://api.openai.com/v1";
export interface OpenAIResponsesTransportConfig {
    apiKey: string;
    /** Read per request, so hosts may reassign it between turns (CLI /model); a request's model wins. */
    model: string;
    /** Defaults to https://api.openai.com/v1; requests go to {baseUrl}/responses. */
    baseUrl?: string;
    /** Output cap sent as max_output_tokens (at least 16). Absent = the model's own cap. */
    maxTokens?: number;
    /**
     * Whether the model reasons (pi model.reasoning; default true): reasoning models get the
     * developer role, reasoning summaries and encrypted reasoning for replay. False for models
     * that reject the reasoning field (gpt-4.1, gpt-4o).
     */
    reasoning?: boolean;
    /** What the model accepts (pi model.input); without "image", images become placeholder text. Default both. */
    input?: readonly ("text" | "image")[];
    /** Extra request headers, merged after the defaults. */
    headers?: Record<string, string>;
    /** Provider label recorded on assistant messages. Defaults to "openai". */
    provider?: string;
    /** Max silence (ms) for headers/next chunk, as in the kernel client. Default 120000; 0 disables. */
    stallTimeoutMs?: number;
}
/** A thinking level as a Responses reasoning effort. OpenAI has no "max": it gets the highest, xhigh. */
export declare function responsesEffortForLevel(level: ThinkingLevel): string;
type WireItem = Record<string, unknown>;
export declare function messagesToInput(systemPrompt: string, messages: AgentMessage[], target: {
    provider: string;
    model: string;
    reasoning: boolean;
}): WireItem[];
export declare function buildRequestBody(config: OpenAIResponsesTransportConfig, request: TransportRequest): WireItem;
/**
 * Create a ChatTransport speaking the OpenAI Responses API. The config object is read per
 * request, so hosts may mutate `model` between turns.
 */
export declare function createOpenAIResponsesTransport(config: OpenAIResponsesTransportConfig, platform: Platform): ChatTransport;
export {};
