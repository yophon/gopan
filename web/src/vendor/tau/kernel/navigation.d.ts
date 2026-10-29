import type { AgentEvent } from "./agent.js";
import type { AgentInternals } from "./agent-internals.js";
import type { TauAbortSignal } from "./platform.js";
/** What a navigation reads from the agent (live: getters and forwarding methods). */
export type NavigationHost = Pick<AgentInternals, "branchSummaryReserveTokens" | "config" | "effectiveCompactionSettings" | "enqueueWrite" | "extensionContext" | "extensions" | "flushWrites" | "priced" | "refresh" | "restoreToolsFromTranscript" | "session" | "summarizationRetry" | "summaryMaxTokens" | "summaryTransport">;
/** Agent.navigateTo's work, inside the operation it holds (abort cancels through `signal`). */
export declare function runNavigation(host: NavigationHost, entryId: string, signal: TauAbortSignal, options: {
    summarize?: boolean;
    customInstructions?: string;
    replaceInstructions?: boolean;
    label?: string;
    onEvent?: (event: AgentEvent) => void;
} | undefined): Promise<{
    cancelled: boolean;
    editorText?: string;
}>;
