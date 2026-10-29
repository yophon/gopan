import { TauError } from "./errors.js";
/** Stop with TauError("aborted") once the signal fired (the agent: "Aborted"; a request: "Request aborted"). */
export function throwIfAborted(signal, message = "Aborted") {
    if (signal?.aborted)
        throw new TauError("aborted", message);
}
/**
 * Pure-ES abort handle over the structural TauAbortSignal. The kernel cannot
 * assume AbortController exists (bare engines), so it grows its own minimal
 * one: `signal` satisfies TauAbortSignal, `abort()` fires it, `follow()`
 * bridges an upstream host signal in (one-way propagation).
 */
export class AbortHandle {
    signal;
    aborted = false;
    reason;
    listeners = new Set();
    constructor() {
        const self = this;
        this.signal = {
            get aborted() {
                return self.aborted;
            },
            get reason() {
                return self.reason;
            },
            addEventListener: (_type, listener) => {
                if (self.aborted)
                    listener();
                else
                    self.listeners.add(listener);
            },
            removeEventListener: (_type, listener) => {
                self.listeners.delete(listener);
            },
        };
    }
    get isAborted() {
        return this.aborted;
    }
    abort(reason) {
        if (this.aborted)
            return;
        this.aborted = true;
        this.reason = reason;
        for (const listener of [...this.listeners])
            listener();
        this.listeners.clear();
    }
    /** Propagate aborts from an upstream (host) signal into this handle. */
    follow(upstream) {
        if (!upstream)
            return;
        if (upstream.aborted) {
            this.abort(upstream.reason);
            return;
        }
        upstream.addEventListener("abort", () => this.abort(upstream.reason), { once: true });
    }
}
