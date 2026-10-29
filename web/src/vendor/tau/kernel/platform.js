import { TauError } from "./errors.js";
/**
 * Platform backed by WinterTC-style globals (Node 18+, Deno, Bun, browsers,
 * edge runtimes). Throws with an actionable message when a global is missing,
 * so exotic hosts know exactly what to inject instead.
 */
export function defaultPlatform() {
    const g = globalThis;
    const globalFetch = g.fetch;
    const GlobalTextDecoder = g.TextDecoder;
    if (typeof globalFetch !== "function") {
        throw new TauError("platform_missing", "globalThis.fetch is not available. Provide a Platform with a fetch implementation (e.g. a wx.request or XHR adapter).");
    }
    if (typeof GlobalTextDecoder !== "function") {
        throw new TauError("platform_missing", "globalThis.TextDecoder is not available. Provide a Platform with a createUtf8Decoder implementation.");
    }
    const globalSetTimeout = g.setTimeout;
    const globalClearTimeout = g.clearTimeout;
    const GlobalAbortController = g.AbortController;
    const GlobalAbortSignal = g.AbortSignal;
    // Native fetch rejects structural signals ("Expected signal to be an instance
    // of AbortSignal"), so the seam bridges kernel-made TauAbortSignals into real
    // ones. Kernel-side this is invisible; custom Platform adapters must do the
    // equivalent for their transport (tech debt #7's documented obligation).
    const bridgeSignal = (signal) => {
        if (!signal || !GlobalAbortController)
            return signal;
        if (GlobalAbortSignal && signal instanceof GlobalAbortSignal)
            return signal;
        const controller = new GlobalAbortController();
        if (signal.aborted)
            controller.abort(signal.reason);
        else
            signal.addEventListener("abort", () => controller.abort(signal.reason), { once: true });
        return controller.signal;
    };
    return {
        fetch: (url, init) => globalFetch(url, init && { ...init, signal: bridgeSignal(init.signal) }),
        createUtf8Decoder: () => {
            const decoder = new GlobalTextDecoder("utf-8");
            return {
                decode: (chunk) => decoder.decode(chunk, { stream: true }),
                flush: () => decoder.decode(),
            };
        },
        // Mirrors pi's fillRandomBytes: crypto when present, Math.random fallback.
        randomBytes: (length) => {
            const bytes = new Uint8Array(length);
            if (g.crypto?.getRandomValues) {
                g.crypto.getRandomValues(bytes);
                return bytes;
            }
            for (let i = 0; i < bytes.length; i++)
                bytes[i] = Math.floor(Math.random() * 256);
            return bytes;
        },
        // Timers exist on every WinterTC host; omitted when absent so exotic hosts
        // simply run without retry/stall features instead of crashing.
        sleep: typeof globalSetTimeout === "function"
            ? (ms, signal) => new Promise((resolve, reject) => {
                // Declared before fail(): a signal already aborted at entry runs fail()
                // ahead of the timer, and a const would still be in its TDZ there.
                let handle;
                const fail = () => {
                    if (handle !== undefined)
                        globalClearTimeout?.(handle);
                    reject(new TauError("aborted", "Sleep aborted"));
                };
                if (signal?.aborted) {
                    fail();
                    return;
                }
                handle = globalSetTimeout(() => {
                    signal?.removeEventListener("abort", fail);
                    resolve();
                }, ms);
                signal?.addEventListener("abort", fail, { once: true });
            })
            : undefined,
    };
}
