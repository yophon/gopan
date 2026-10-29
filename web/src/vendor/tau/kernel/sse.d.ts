export interface SseEvent {
    event?: string;
    data: string;
}
export interface SseParserOptions {
    maxBufferLength?: number;
    /** Called for every `id` field, including events without data (resumption priming events; pi). */
    onId?(id: string): void;
    /** Called for every valid `retry` field, in milliseconds (pi). */
    onRetry?(delayMs: number): void;
}
/**
 * Incremental server-sent-events parser. Feed it decoded text chunks in any
 * split; it emits complete events. Handles CRLF, multi-line data fields, and
 * ignores comments and unknown fields; `id` and `retry` go to optional callbacks
 * (MCP stream resumption, P43).
 */
export declare class SseParser {
    private buffer;
    private dataLines;
    private eventName;
    private readonly maxBufferLength;
    private readonly options;
    constructor(options?: SseParserOptions);
    push(text: string): SseEvent[];
    /** Emit any event still buffered when the stream ends without a trailing blank line. */
    flush(): SseEvent[];
    private handleLine;
    private dispatch;
}
