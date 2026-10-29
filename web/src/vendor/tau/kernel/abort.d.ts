import type { TauAbortSignal } from "./platform.js";
/** Stop with TauError("aborted") once the signal fired (the agent: "Aborted"; a request: "Request aborted"). */
export declare function throwIfAborted(signal: TauAbortSignal | undefined, message?: string): void;
/**
 * Pure-ES abort handle over the structural TauAbortSignal. The kernel cannot
 * assume AbortController exists (bare engines), so it grows its own minimal
 * one: `signal` satisfies TauAbortSignal, `abort()` fires it, `follow()`
 * bridges an upstream host signal in (one-way propagation).
 */
export declare class AbortHandle {
    readonly signal: TauAbortSignal;
    private aborted;
    private reason;
    private readonly listeners;
    constructor();
    get isAborted(): boolean;
    abort(reason?: unknown): void;
    /** Propagate aborts from an upstream (host) signal into this handle. */
    follow(upstream: TauAbortSignal | undefined): void;
}
