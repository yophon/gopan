import type { Utf8Decoder } from "./platform.js";
/** UTF-8 byte length of one code point. */
export declare function utf8CodePointLength(codePoint: number): number;
/** UTF-8 byte length of a string (pure ES: no TextEncoder on bare engines). */
export declare function utf8ByteLength(text: string): number;
/**
 * Create an incremental UTF-8 decoder: multi-byte characters split across
 * chunks are held until complete; invalid sequences decode to U+FFFD without
 * derailing the rest of the stream; flush() reports a trailing partial
 * character as U+FFFD (mirroring TextDecoder's non-fatal mode).
 */
export declare function createIncrementalUtf8Decoder(): Utf8Decoder;
