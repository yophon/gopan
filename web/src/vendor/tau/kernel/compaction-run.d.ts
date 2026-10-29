import type { AgentEvent } from "./agent.js";
import type { AgentInternals } from "./agent-internals.js";
import { type CompactionResult } from "./compaction.js";
import type { CompactionReason } from "./extensions.js";
import type { TauAbortSignal } from "./platform.js";
export type CompactionOutcome = {
    kind: "none";
} | {
    kind: "done";
    result: CompactionResult;
} | {
    kind: "failed";
    errorMessage?: string;
    aborted: boolean;
    cancelledByExtension: boolean;
    error: Error;
};
/** What a compaction reads of the agent. */
export type CompactionRunHost = Pick<AgentInternals, "effectiveCompactionSettings" | "enqueueWrite" | "extensionContext" | "extensions" | "flushWrites" | "messages" | "priced" | "refresh" | "session" | "summarizationRetry" | "summaryMaxTokens" | "summaryTransport">;
/**
 * The compaction itself: never throws. Aborts are checked around every asynchronous step, and a
 * failure — abort, extension cancel, summary error — is reported as session_compact_failed and
 * leaves the log untouched.
 */
export declare function runCompactionOperation(host: CompactionRunHost, customInstructions: string | undefined, reason: CompactionReason, signal: TauAbortSignal | undefined, onEvent?: (event: AgentEvent) => void, 
/** P40d (pi): an overflow recovery runs the turn again after this compaction. */
willRetry?: boolean): Promise<CompactionOutcome>;
/** Run a compaction, yielding its progress events (summary retries) live; returns its outcome. */
export declare function compactWithEvents(host: CompactionRunHost, customInstructions: string | undefined, reason: CompactionReason, signal: TauAbortSignal | undefined, willRetry?: boolean): AsyncGenerator<AgentEvent, CompactionOutcome>;
