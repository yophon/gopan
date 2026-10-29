/**
 * Structural subset of AbortSignal. Real AbortSignal instances satisfy this
 * interface; hosts without AbortController can provide their own implementation.
 */
export interface TauAbortSignal {
    readonly aborted: boolean;
    readonly reason?: unknown;
    addEventListener(type: "abort", listener: () => void, options?: {
        once?: boolean;
    }): void;
    removeEventListener(type: "abort", listener: () => void): void;
}
export interface PlatformRequestInit {
    method?: string;
    headers?: Record<string, string>;
    body?: string;
    signal?: TauAbortSignal;
}
export interface PlatformBodyReader {
    read(): Promise<{
        done: boolean;
        value?: Uint8Array;
    }>;
    cancel(reason?: unknown): unknown;
}
/** Structural subset of fetch Headers; native Headers instances satisfy it. */
export interface PlatformResponseHeaders {
    get(name: string): string | null;
}
/**
 * Structural subset of a fetch Response. Hosts without fetch (WeChat
 * mini-programs, React Native without streaming) implement this shape over
 * their native HTTP APIs; `wx.request` with `enableChunked: true` maps onto
 * `body.getReader()` naturally.
 */
export interface PlatformResponse {
    readonly ok: boolean;
    readonly status: number;
    text(): Promise<string>;
    readonly body: {
        getReader(): PlatformBodyReader;
    } | null;
    /**
     * Optional: consumers needing response headers (e.g. MCP-Session-Id) must
     * degrade gracefully when an adapter cannot provide them.
     */
    readonly headers?: PlatformResponseHeaders;
}
export type PlatformFetch = (url: string, init?: PlatformRequestInit) => Promise<PlatformResponse>;
/** Incremental UTF-8 decoder that handles multi-byte characters split across chunks. */
export interface Utf8Decoder {
    decode(chunk: Uint8Array): string;
    flush(): string;
}
/**
 * Everything the kernel needs from the host runtime. Pure-ECMAScript built-ins
 * (JSON, Promise, Date.now, …) are used directly; anything beyond the language
 * standard goes through this seam.
 */
export interface Platform {
    fetch: PlatformFetch;
    createUtf8Decoder(): Utf8Decoder;
    /** Fill-quality randomness for ids (uuidv7). Not for cryptographic use. */
    randomBytes(length: number): Uint8Array;
    /**
     * Interruptible delay; rejects with a TauError("aborted") when the signal
     * fires. Optional: when absent, automatic retry and stall timeouts are
     * disabled — bare-engine hosts pay nothing for features they cannot back.
     */
    sleep?(ms: number, signal?: TauAbortSignal): Promise<void>;
}
/**
 * Platform backed by WinterTC-style globals (Node 18+, Deno, Bun, browsers,
 * edge runtimes). Throws with an actionable message when a global is missing,
 * so exotic hosts know exactly what to inject instead.
 */
export declare function defaultPlatform(): Platform;
