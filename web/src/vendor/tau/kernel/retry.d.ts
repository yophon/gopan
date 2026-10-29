import type { AssistantMessage } from "./messages.js";
import type { TauAbortSignal } from "./platform.js";
/** Retry policy knobs (defaults mirror pi's settings-manager: enabled, 3 attempts, 2s base). */
export interface RetrySettings {
    enabled: boolean;
    maxRetries: number;
    baseDelayMs: number;
    /** Caps each computed delay (pi RetryPolicy.maxAgentDelayMs, default 60s). */
    maxAgentDelayMs?: number;
}
export declare const DEFAULT_RETRY_SETTINGS: RetrySettings;
export declare const DEFAULT_MAX_AGENT_RETRY_DELAY_MS = 60000;
/**
 * Backoff for a 1-indexed attempt, verbatim from pi: base·2^(attempt−1), held to a
 * safe integer and capped. Without the cap 2000·2^21 already exceeds what a timer
 * accepts and fires almost at once — the busy retry D15 forbids.
 */
export declare function retryDelayMs(settings: Pick<RetrySettings, "baseDelayMs" | "maxAgentDelayMs">, attempt: number): number;
/** Callbacks around each retry of a one-off call (pi RetryCallbacks). */
export interface RetryCallbacks {
    onRetryScheduled?: (attempt: number, maxAttempts: number, delayMs: number, errorMessage: string) => Promise<void> | void;
    onRetryAttemptStart?: () => Promise<void> | void;
    onRetryFinished?: (success: boolean, attempt: number, finalError?: string) => Promise<void> | void;
}
/**
 * Bounded retry of a single assistant call (pi retryAssistantCall), used by the
 * summarization calls. Only transient errors are retried; aborts and
 * deterministic errors return at once. The backoff runs on the host's sleep:
 * without one, retry is off (D15) — never a timer-free busy loop.
 */
export declare function retryAssistantCall(produce: () => Promise<AssistantMessage>, settings: RetrySettings | undefined, sleep: ((ms: number, signal?: TauAbortSignal) => Promise<void>) | undefined, signal: TauAbortSignal | undefined, callbacks?: RetryCallbacks): Promise<AssistantMessage>;
/** True when the error text looks like context overflow (excluded from retry, as in pi). */
export declare function isContextOverflowError(message: AssistantMessage): boolean;
/**
 * Classifies whether a failed assistant message looks like a transient provider
 * or transport error. Pure classification, as in pi: the caller applies budget,
 * backoff, and reporting.
 */
export declare function isRetryableAssistantError(message: AssistantMessage): boolean;
