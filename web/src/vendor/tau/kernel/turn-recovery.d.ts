import type { AgentEvent } from "./agent.js";
import type { AgentInternals } from "./agent-internals.js";
import { type CompactionRunHost } from "./compaction-run.js";
import type { TauError } from "./errors.js";
import type { ActivityOutcome, ExtensionContext } from "./extensions.js";
import type { AssistantMessage } from "./messages.js";
import type { TauAbortSignal } from "./platform.js";
/** One run's retry bookkeeping. */
export interface RecoveryState {
    retryAttempt: number;
    /** An overflow was compacted and its turn retried; reset by a request that goes through. */
    overflowRecoveryAttempted: boolean;
}
/** What recovering reads of the agent. */
export type RecoveryHost = CompactionRunHost & Pick<AgentInternals, "platform" | "retrySettings">;
/**
 * Decide what follows a failed request, whose message is already recorded. "again" runs the turn
 * again (it does not count against the turn budget); "aborted" and "error" end the run, agent_end
 * included.
 */
export declare function recoverFromFailure(host: RecoveryHost, input: {
    failed: AssistantMessage;
    failedEntryId: string;
    failure: TauError;
    recovery: RecoveryState;
    run: {
        turnIndex: number;
    };
    signal: TauAbortSignal | undefined;
    ctx: ExtensionContext;
    fallback: {
        fallBack(error: TauError): boolean;
    } | undefined;
    /** End the run early (the agent's endRun: queued input goes back, a retry cycle closes as cancelled). */
    endRun: (retryAttempt: number) => AsyncGenerator<AgentEvent>;
}): AsyncGenerator<AgentEvent, "again" | Exclude<ActivityOutcome, "completed">>;
