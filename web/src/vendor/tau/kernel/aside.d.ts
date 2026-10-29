import type { AgentInternals } from "./agent-internals.js";
import { type AssistantMessage } from "./messages.js";
import type { TauAbortSignal } from "./platform.js";
/** What a side question reads from the agent (live: getters and forwarding methods). */
export type AsideHost = Pick<AgentInternals, "config" | "currentModel" | "currentThinkingLevel" | "enqueueBackground" | "extensionContext" | "extensions" | "hiddenDeclarations" | "messages" | "pricingFor" | "providerEventHooks" | "providerHeaders" | "refresh" | "session" | "transport">;
/**
 * A side question (P39c, Claude Code's /btw): the conversation as the next request would
 * send it (same prefix, so a warm prompt cache serves it), the newest 20 earlier side
 * exchanges, then the question. Nothing joins the conversation and no tool runs; the
 * request still declares the tools (a changed prefix would miss the cache), so tool
 * calls the model makes anyway are only counted. Works while a run is in flight: it sees
 * the committed messages, not the reply being written. Its usage is logged as a `usage`
 * entry so session totals stay honest.
 */
export declare function askAside(host: AsideHost, question: string, options?: {
    exchanges?: ReadonlyArray<{
        question: string;
        answer: string;
    }>;
    signal?: TauAbortSignal;
    onText?: (delta: string) => void;
}): Promise<{
    text: string;
    toolCalls: number;
    stopReason: AssistantMessage["stopReason"];
}>;
