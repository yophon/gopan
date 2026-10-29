import type { AgentEvent } from "./agent.js";
import type { ImageProcessor } from "./capabilities.js";
import type { ExtensionContext, ExtensionRegistry, ExtensionToolContext, UiCapability } from "./extensions.js";
import type { ToolCall, ToolResultMessage } from "./messages.js";
import type { TauAbortSignal } from "./platform.js";
import type { ApprovalRequest, PermissionMode, ToolPolicy } from "./policy.js";
import { type Tool, type ToolResult, type ToolUpdateStream } from "./tools.js";
/**
 * Tool batch execution, split out of the agent loop (P23b). Structure mirrors
 * pi agent-loop.ts executeToolCalls: a serial preflight per call (tool_call
 * hook, argument validation, policy and approval, tool_execution_start), then
 * sequential or concurrent execution, then tool_result hooks; the agent commits
 * each result through `host.commit` in source order.
 */
/**
 * FIFO lock held for a tool call's whole preflight — tool_call hooks, validation,
 * policy, approval, tool_execution_start — never across execution. One lock serves
 * an agent tree (the root agent's, shared with subagents), so nested calls from a
 * script's Promise.all and parallel subagents never prompt for approval at once
 * (D22-2: hooks and approvals run one at a time).
 */
export declare class PreflightLock {
    private tail;
    run<T>(work: () => Promise<T>): Promise<T>;
}
/** What the runner needs from its agent. */
export interface ToolBatchHost {
    readonly extensions: ExtensionRegistry | undefined;
    readonly policy: ToolPolicy;
    readonly permissionMode: PermissionMode;
    /** The filesystem's working directory, for the accept-edits rule (P26d). */
    readonly cwd?: string;
    readonly onApproval: ((request: ApprovalRequest) => Promise<boolean>) | undefined;
    ui(): UiCapability | undefined;
    readonly preflightLock: PreflightLock;
    /** The context a tool's execute receives: `ctx` plus executeTool/tools bound to this call. */
    toolContext(ctx: ExtensionContext, toolCallId: string, signal: TauAbortSignal | undefined): ExtensionToolContext;
    /** Log one tool result and return its AgentEvent. */
    commit(toolCall: ToolCall, outcome: ToolOutcome, ctx: ExtensionContext, batch: ToolBatch): Promise<AgentEvent>;
    /** Converts and shrinks the images tools return before they are logged (P43, pi); absent: kept as they are. */
    readonly images?: ImageProcessor;
}
/** A tool result plus how long tool.execute took (absent when it never ran). */
export type ToolOutcome = ToolResult & {
    durationMs?: number;
};
/** What a tool batch committed: result messages, their entry ids, and each result's terminate hint. */
export interface ToolBatch {
    messages: ToolResultMessage[];
    entryIds: string[];
    terminate: boolean[];
}
export declare class ToolBatchRunner {
    private readonly host;
    constructor(host: ToolBatchHost);
    /** Sequential batch: the pre-P18 behavior — full hook chain, execute, result, one tool at a time. */
    runSequential(toolCalls: ToolCall[], tools: ReadonlyMap<string, Tool>, ctx: ExtensionContext, signal: TauAbortSignal | undefined, batch: ToolBatch): AsyncGenerator<AgentEvent>;
    /**
     * Parallel batch (P18, structure mirrors pi executeToolCallsParallel):
     * phase 1 — preflight serially in source order (tool_call hook, validation,
     * policy/approval, tool_execution_start; immediate outcomes settle here);
     * phase 2 — execute prepared tools concurrently, tool_execution_end and
     * tool_update stream in completion/arrival order; phase 3 — tool results
     * commit to messages/session in assistant source order (stable wire order).
     * Deviation from pi: no break on abort mid-preflight — every tool call gets
     * a result (existing tau sequential semantics; tools observe the signal and
     * settle fast), keeping the wire's call/result pairing always complete.
     */
    runParallel(toolCalls: ToolCall[], tools: ReadonlyMap<string, Tool>, ctx: ExtensionContext, signal: TauAbortSignal | undefined, batch: ToolBatch): AsyncGenerator<AgentEvent>;
    private executeToolCall;
    /**
     * A call another tool made (ctx.executeTool): the same preflight and execution as
     * a model-issued call, with `parentToolCallId` on its events and approval request.
     */
    runNested(toolCall: ToolCall, tools: ReadonlyMap<string, Tool>, ctx: ExtensionContext, parentToolCallId: string, signal: TauAbortSignal | undefined, onUpdate: ((partialOutput: string, stream?: ToolUpdateStream) => void) | undefined): Promise<ToolOutcome>;
    /**
     * Serial preflight of one tool call: tool_call hook (block/rewrite),
     * argument validation, policy gate, approval, and — for calls that will
     * execute — the tool_execution_start notification. Immediate outcomes
     * (unknown tool, block, invalid args, deny) never see execution hooks.
     */
    private preflightToolCall;
    private preflightLocked;
    /** Execute a prepared tool call: updates stream, tool_execution_end, tool_result hook. */
    private executePreparedToolCall;
    /** Default approval handler over the host UI; no UI means no handler (ask degrades to deny). */
    private uiApprovalHandler;
    /** Wait for an approval decision; an abort while waiting counts as a rejection. */
    private awaitApproval;
}
/**
 * Validate what a tool (or a tool_result handler) returned. A result without a
 * `content` array — e.g. an extension still returning the pre-P23 `{ output }`
 * — becomes an error result the model can see, instead of an empty success.
 */
export declare function normalizeToolOutcome(toolName: string, outcome: ToolOutcome): ToolOutcome;
