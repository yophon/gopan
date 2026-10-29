import { type AgentMessage, type ChatTransport, type Platform, type ThinkingLevel, type TransportRequest } from "@yophon/tau-kernel";
/** The `api` field recorded on assistant messages, as in pi. */
export declare const GOOGLE_GENERATIVE_AI_API = "google-generative-ai";
export declare const GOOGLE_DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
export interface GoogleTransportConfig {
    apiKey: string;
    /** Read per request, so hosts may reassign it between turns (CLI /model); a request's model wins. */
    model: string;
    /** Defaults to https://generativelanguage.googleapis.com/v1beta (the version is part of it). */
    baseUrl?: string;
    /** Output cap sent as generationConfig.maxOutputTokens. Absent = the model's own cap. */
    maxTokens?: number;
    /** Whether the model thinks (pi model.reasoning; default true): thinking levels and thought summaries apply. */
    reasoning?: boolean;
    /** What the model accepts (pi model.input); without "image", images become placeholder text. Default both. */
    input?: readonly ("text" | "image")[];
    /** Extra request headers, merged after the defaults. */
    headers?: Record<string, string>;
    /** Provider label recorded on assistant messages. Defaults to "google". */
    provider?: string;
    /** Max silence (ms) for headers/next chunk, as in the kernel client. Default 120000; 0 disables. */
    stallTimeoutMs?: number;
}
/** Gemini 3+ (and models served through the Gemini API that need them) pair calls and results by id. */
export declare function requiresToolCallId(model: string): boolean;
/** Models with the discrete thinkingLevel control instead of a token budget (pi usesGoogleThinkingLevel). */
export declare function usesThinkingLevel(model: string): boolean;
/**
 * A thinking level as Gemini's thinkingConfig. xhigh and max ask for the highest (high). "none"
 * turns thinking off where the model can (budget 0); 2.5 Pro and the level models cannot, so they
 * get their lowest setting — flash models MINIMAL, the others LOW (pi clamps "off" the same way).
 */
export declare function googleThinkingConfig(model: string, level: ThinkingLevel): Record<string, unknown>;
type WirePart = Record<string, unknown>;
interface WireContent {
    role: "user" | "model";
    parts: WirePart[];
}
export declare function messagesToContents(messages: AgentMessage[], model: string): WireContent[];
export declare function buildRequestBody(config: GoogleTransportConfig, request: TransportRequest): Record<string, unknown>;
/**
 * Create a ChatTransport speaking the Gemini API. The config object is read per request, so
 * hosts may mutate `model` between turns.
 */
export declare function createGoogleTransport(config: GoogleTransportConfig, platform: Platform): ChatTransport;
export {};
