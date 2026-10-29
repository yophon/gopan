import { type NestedToolCallRecord, type NestedToolCalls, type ToolCall, type Usage } from "./messages.js";
import type { TauAbortSignal } from "./platform.js";
import type { Tool, ToolResult, ToolUpdateStream } from "./tools.js";
/**
 * Limits of the nested-call record on a tool result: arguments over the per-call
 * or total size are omitted, calls beyond the count are dropped (they still run),
 * and the record is marked incomplete when any of that happens (pi).
 */
export declare const NESTED_CALL_LIMITS: {
    readonly maxCalls: 256;
    readonly maxArgumentBytesPerCall: number;
    readonly maxArgumentBytesTotal: number;
    readonly maxErrorChars: 500;
};
/** What a nested call returns (pi AgentToolCallOutcome). */
export interface ToolCallOutcome {
    toolCall: ToolCall;
    result: ToolResult;
    isError: boolean;
    durationMs?: number;
}
export interface ExecuteToolOptions {
    /** Defaults to the calling tool's signal. */
    signal?: TauAbortSignal;
    /** Partial output of the nested tool, in addition to tool_execution_update events. */
    onUpdate?: (partialOutput: string, stream?: ToolUpdateStream) => void;
}
/** What the nested calls of one model-issued tool call leave on its tool result message. */
export interface NestedCallSummary {
    /** Becomes `nestedCalls`; undefined when no nested call was made. */
    calls: NestedToolCalls | undefined;
    /** Summed usage of the nested results, added to the message's `usage`. */
    usage: Usage | undefined;
}
/** Collects the nested calls of one model-issued tool call, including calls made by nested tools (pi). */
export declare class NestedCallRecorder {
    private readonly calls;
    private readonly startedAt;
    private complete;
    private argumentBytes;
    private usage;
    /** Record a call as it starts. Returns undefined when the call is dropped from the record. */
    start(toolCall: ToolCall): NestedToolCallRecord | undefined;
    finish(record: NestedToolCallRecord | undefined, isError: boolean, errorText: string): void;
    addUsage(usage: Usage): void;
    get totalUsage(): Usage | undefined;
    /** Copy of the record so far, or undefined when no nested call was made. */
    snapshot(): NestedToolCalls | undefined;
}
export interface NestedToolCallHost {
    /** Tools nested calls resolve against: the callable set at call time. */
    getTools(): ReadonlyMap<string, Tool>;
    /** Whether every nested call runs exclusively, as when the agent executes tool calls sequentially. */
    isSequential(): boolean;
    /** Run the call through the tool pipeline, with events that report `parentToolCallId`. */
    runToolCall(toolCall: ToolCall, tools: ReadonlyMap<string, Tool>, parentToolCallId: string, signal: TauAbortSignal | undefined, onUpdate: ((partialOutput: string, stream?: ToolUpdateStream) => void) | undefined): Promise<ToolResult & {
        durationMs?: number;
    }>;
}
/** Runs nested calls and keeps their record per model-issued call (pi NestedToolCallRunner). */
export declare class NestedToolCallRunner {
    private readonly host;
    /** Scopes by the id of the calling tool call. */
    private readonly scopes;
    /** Serializes nested calls that must not run concurrently. */
    private queueTail;
    constructor(host: NestedToolCallHost);
    /**
     * Run `name` on behalf of the call `callerId`; the nested call gets the id
     * `<callerId>/<n>`. Never rejects for tool failures: they come back as `isError`.
     */
    execute(callerId: string, name: string, args: unknown, options?: ExecuteToolOptions & {
        signal?: TauAbortSignal;
    }): Promise<ToolCallOutcome>;
    /** Remove and return the record of the nested calls a model-issued call made. */
    takeRecord(toolCallId: string): NestedCallSummary | undefined;
    clear(): void;
}
