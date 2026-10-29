import type { ImageProcessor } from "./capabilities.js";
import { type CompactionResult, type CompactionSettings, type ContextUsageEstimate } from "./compaction.js";
import { type CompactionReason, type ExtensionCapabilities, type ExtensionContext, type ExtensionRegistry, type QueuedInputDisposition, type ReadonlySessionManager, type ThinkingLevel, type UiCapability } from "./extensions.js";
import { type FallbackOptions } from "./fallback.js";
import { type AgentMessage, type AssistantMessage, type CustomMessage, type ModelPricing, type ToolCall, type ToolResultMessage, type Usage, type UserContent, type UserMessage } from "./messages.js";
import { type ChatTransport, type OpenAICompatConfig } from "./openai.js";
import { type QueueMode } from "./pending-queue.js";
import { type Platform, type TauAbortSignal } from "./platform.js";
import { type ApprovalRequest, type PermissionMode, type ToolPolicy } from "./policy.js";
import { type RetrySettings } from "./retry.js";
import { SessionRecorder } from "./session.js";
import { PreflightLock } from "./tool-batch.js";
import { type Tool, type ToolExecutionMode, type ToolInfo, type ToolResult, type ToolUpdateStream } from "./tools.js";
/**
 * What holds the agent. One at a time (pi: a run, a manual compaction and a
 * branch summary never overlap); a second entry throws TauError("busy").
 */
export type AgentOperation = "prompt" | "resume" | "compact" | "navigate";
/** What Agent.setModel switches along with the model (P28). */
export interface SetModelOptions {
    /** The new model's transport; omitted = keep the current one. */
    transport?: ChatTransport;
    /** Provider label for messages and the model_change entry (pi `provider`). */
    provider?: string;
    /** Wire API label for messages (pi `api`), e.g. "anthropic-messages". */
    api?: string;
    /** The new model's context window (auto-compaction, context %); present-but-undefined clears it. */
    contextWindow?: number | undefined;
    /** The new model's output limit (summary requests); present-but-undefined clears it. */
    maxOutputTokens?: number | undefined;
}
export interface AgentOptions {
    config: OpenAICompatConfig;
    /**
     * Protocol transport for LLM requests (P16). Defaults to the OpenAI-compatible
     * client over `config`. When injected, the transport owns all protocol details
     * (baseUrl, apiKey, wire format); `config` remains the source of the
     * model/provider/api labels on partial messages, of contextWindow, and of the
     * default-transport fallback. Not hot-swappable mid-session by design —
     * switching protocols mid-conversation would break usage/cache semantics.
     */
    transport?: ChatTransport;
    /** Host platform. Defaults to WinterTC-style globals via defaultPlatform(). */
    platform?: Platform;
    systemPrompt?: string;
    tools?: Tool[];
    /** Pre-loaded extensions (ExtensionRegistry.load). Extension tools override same-name base tools. */
    extensions?: ExtensionRegistry;
    /** Host UI capability, exposed to extensions via ctx.ui. */
    ui?: UiCapability;
    /** Host capabilities exposed to extensions through ctx.capabilities. */
    capabilities?: Omit<ExtensionCapabilities, "platform"> & {
        platform?: Platform;
    };
    /**
     * Seed conversation. Without `session` the messages are appended to the
     * agent's in-memory log. With `session` the log is the session itself and
     * this only serves as the `messages` view until the session's projection is
     * loaded (hosts pass what restoreSession returned).
     */
    initialMessages?: AgentMessage[];
    /**
     * Session the agent logs to. The log is the only source of model context
     * (P22): `messages` is its projection and every request is rebuilt from it.
     * Absent = a private InMemorySessionStore, so there is one code path.
     */
    session?: SessionRecorder;
    /** Optional cap on model turns within one prompt (runaway tool loops); default: none. */
    maxTurnsPerPrompt?: number;
    steeringMode?: QueueMode;
    followUpMode?: QueueMode;
    /** Compaction thresholds; auto-compaction also requires config.contextWindow. */
    compaction?: Partial<CompactionSettings>;
    /** pi's branchSummary.reserveTokens (P40c): tokens kept free when a branch is summarized; default compaction's. */
    branchSummary?: {
        reserveTokens?: number;
    };
    /**
     * P53c (yo-agent's YO_COMPACT_MODEL): summaries — compaction, turn prefixes, branch summaries — go to
     * this model instead of the agent's. Absent: the agent's model.
     */
    summaryModel?: SummaryModel;
    /**
     * P53c (yo-agent): how many times in a row a reply cut off by the output cap (stopReason "length",
     * no tool calls) is continued with a request to go on. 0 (the default, pi) ends the run there.
     */
    continueAfterLength?: number;
    /** P54c (yo-agent, Claude Code's --fallback-model): routes a run's requests fall back to (fallback.ts). */
    fallback?: FallbackOptions;
    /** Auto-retry policy for failed LLM requests (pi defaults: enabled, 3 attempts, 2s base). Requires Platform.sleep. */
    retry?: Partial<RetrySettings>;
    /**
     * Host-supplied unit prices; fills usage.cost on assistant messages and
     * summaries. A function prices each model separately — undefined for a model
     * means its cost stays zero ("unknown", D19-3), e.g. after a model switch.
     */
    pricing?: ModelPricing | ((model: string) => ModelPricing | undefined);
    /** Initial thinking level sent with every request (P23d); undefined = the endpoint's default. */
    thinkingLevel?: ThinkingLevel;
    /**
     * Permission mode gating tool execution (P15). Kernel default:
     * "autonomous" — library consumers keep their behavior and opt into
     * stricter modes; interactive hosts should pass "supervised".
     */
    permissionMode?: PermissionMode;
    /** Tool policy consulted before every tool execution. Defaults to createDefaultPolicy(). */
    policy?: ToolPolicy;
    /**
     * Handler for policy "ask" decisions. Defaults to ui.confirm; when both
     * are absent, "ask" degrades to deny (D10 headless semantics).
     */
    onApproval?: (request: ApprovalRequest) => Promise<boolean>;
    /**
     * Tool batch execution mode (P18, mirrors pi AgentLoopConfig.toolExecution).
     * Default "parallel" — pi's default: preflight (hooks/policy/approval) stays
     * sequential in source order, then allowed tools execute concurrently. Any
     * tool marked executionMode:"sequential" in a batch downgrades that whole
     * batch to sequential (pi dispatch semantics).
     */
    toolExecution?: ToolExecutionMode;
    /**
     * Lock serializing tool-call preflight (hooks, policy, approval). Subagents get
     * their parent's, so one agent tree never asks for two approvals at once (D22-2).
     * Hosts running several agents against one approval UI may share one too.
     */
    preflightLock?: PreflightLock;
    /**
     * Before every LLM request, rebuild the projection from a JSON round trip of
     * the session log and require it to equal the conversation about to be sent
     * (P22: model-visible means logged). A mismatch is a kernel bug and throws
     * TauError("invalid_state"). Default true.
     */
    verifyTranscript?: boolean;
    /**
     * Converts and shrinks the images tools return before they are logged (P43, pi
     * normalizeToolResultImages): MCP and extension tools hand back arbitrary images.
     * Absent: images are logged as the tools return them. Subagents inherit it.
     */
    imageProcessor?: ImageProcessor;
    /**
     * Whether the host trusts project-local resources (backs ctx.isProjectTrusted,
     * pi). Only an explicit true counts (D20-3); subagents inherit it.
     */
    isProjectTrusted?: () => boolean;
    /**
     * Called after each turn's assistant message and tool results are final,
     * before turn_end (pi AgentLoopConfig.finishTurn). Returning { action: "end" }
     * ends the run after turn_end without draining the queues — e.g. a "done"
     * tool or a soft turn budget; messages already queued still get a
     * continuation run, as in pi. pi's "continue" is reserved (see pi-parity).
     * Ignored when the turn errored or was aborted.
     */
    finishTurn?: (turn: {
        message: AssistantMessage;
        toolResults: readonly ToolResultMessage[];
    }, signal?: TauAbortSignal) => Promise<{
        action: "end";
    } | undefined | void> | {
        action: "end";
    } | undefined | void;
}
export type AgentEvent = {
    type: "text_delta";
    delta: string;
} | {
    type: "reasoning_delta";
    delta: string;
}
/** A tool call's arguments so far, while the model writes them (P38b, pi's toolcall_delta). */
 | {
    type: "tool_call_delta";
    index: number;
    name: string;
    argumentsText: string;
} | {
    type: "assistant_message";
    message: AssistantMessage;
} | {
    type: "user_message";
    message: UserMessage | CustomMessage;
} | {
    type: "tool_start";
    toolCall: ToolCall;
} | {
    type: "tool_update";
    toolCall: ToolCall;
    partialOutput: string;
    stream?: ToolUpdateStream;
} | {
    type: "tool_result";
    toolCall: ToolCall;
    result: ToolResult;
    durationMs?: number;
} | {
    type: "compaction";
    result: CompactionResult;
    reason?: CompactionReason;
} | {
    type: "compaction_failed";
    reason: CompactionReason;
    errorMessage?: string;
    aborted: boolean;
} | {
    type: "summarization_retry_scheduled";
    attempt: number;
    maxAttempts: number;
    delayMs: number;
    errorMessage: string;
} | {
    type: "summarization_retry_attempt_start";
    source: "compaction";
    reason: CompactionReason;
} | {
    type: "summarization_retry_attempt_start";
    source: "branchSummary";
} | {
    type: "summarization_retry_finished";
} | {
    type: "auto_retry_start";
    attempt: number;
    maxAttempts: number;
    delayMs: number;
    errorMessage: string;
} | {
    type: "auto_retry_end";
    success: boolean;
    attempt: number;
    finalError?: string;
} | {
    type: "agent_end";
    messages: readonly AgentMessage[];
} | {
    type: "agent_settled";
    aborted: boolean;
};
/**
 * Minimal agent loop: send conversation, stream the reply, execute requested
 * tools, repeat until the model stops asking for tools and no follow-up is
 * queued. Conversation state lives in the session log (P22): every write is an
 * append, `messages` is the log's projection, and each LLM request is built
 * from it.
 *
 * Event ordering per turn (extensions are notified before the host sees the
 * corresponding AgentEvent, so extensions can rewrite content first):
 * turn_start → context → message_start → message_update* → message_end →
 * tool batch → drain steering → turn_end.
 *
 * Tool batch ordering depends on the execution mode (P18). Sequential (explicit
 * config, or any tool in the batch marked executionMode:"sequential"): per tool
 * [tool_call → tool_execution_start → tool_execution_update* →
 * tool_execution_end → tool_result], one tool at a time. Parallel (default,
 * pi's): [tool_call → tool_execution_start] serially in source order for the
 * whole batch, then execution_update/end in completion order interleaved, then
 * tool_result messages committed in source order.
 */
export declare class Agent {
    /** The view of private state the split-out modules read (P51: agent-internals.ts). */
    private readonly internals;
    /** The tool set (P50: tool-state.ts). */
    private readonly toolState;
    /** Projection of the session log (frozen; replaced after every write). */
    private projection;
    /** Source entry of each projected message (pi _entryIdsByMessage). */
    private entryIds;
    /** The active path and its projection as of the last refresh (for sync estimates). */
    private activePath;
    private activeProjection;
    /** Session-wide billed usage as of the last refresh (sumSessionUsage). */
    private sessionUsage;
    /** Every entry and the store metadata as of the last refresh (backs ctx.sessionManager). */
    private allEntries;
    private entriesById;
    private sessionMetadata;
    private readonlySessionManager;
    private readonly platform;
    /** Replaced, not mutated, when setModel switches provider (P28). */
    private config;
    private transport;
    private readonly systemPrompt;
    private readonly baseTools;
    private readonly extensions;
    private ui;
    private readonly capabilities;
    private readonly maxTurnsPerPrompt;
    private readonly steeringQueue;
    private readonly followUpQueue;
    private readonly session;
    private readonly compactionSettings;
    private readonly branchSummaryReserveTokens;
    private readonly summaryModel;
    private readonly continueAfterLength;
    private readonly fallback;
    private readonly retrySettings;
    private readonly pricing;
    /** Model of this agent's requests (Agent.setModel; starts as config.model). */
    private currentModel;
    private currentThinkingLevel;
    private permissionMode;
    private readonly policy;
    private readonly onApproval;
    private readonly toolExecution;
    private readonly verifyTranscript;
    private readonly imageProcessor;
    /** Custom instructions of a requested compaction, or null when none is pending. */
    private pendingCompaction;
    /** Custom messages held for the next run's context (sendMessage deliverAs:"nextTurn"). */
    private readonly pendingNextTurn;
    /**
     * The single writer: every log append (and the projection refresh after it)
     * runs here in order. Sync extension actions enqueue without awaiting; their
     * failure is held in `writeFailure` and rethrown at the next flushWrites().
     */
    private writes;
    private writeFailure;
    /** The operation holding the agent, if any (prompt/resume/compact/navigateTo). */
    private operation;
    /** Extension sendMessage calls made while a compaction or navigation holds the agent; appended when it ends. */
    private readonly heldMessages;
    /** Custom messages sent with triggerTurn:false while the loop runs; committed after the current turn_end (pi 0.84.4). */
    private readonly afterTurnMessages;
    /** Whether the current prompt reached the turn loop (agent_settled fires only then). */
    private runEntered;
    /** True while agent_settled handlers run; runs they start wait until all returned (pi 0.87.1). */
    private settling;
    private readonly deferredStarts;
    private readonly finishTurn;
    private readonly isProjectTrusted;
    /** Runs tool batches: preflight (hooks, validation, policy, approval), execution, result hooks. */
    private readonly toolRunner;
    private readonly preflightLock;
    /** Calls tools make through ctx.executeTool(), and their record per model-issued call. */
    private readonly nestedCalls;
    /** Declared tools that prepareLoadout hooks leave out of requests (pi _hiddenDeclarations). */
    private hiddenDeclarations;
    /**
     * True only while the LLM turn loop is in flight — pi's isStreaming. Hooks
     * that fire before the loop (input, before_agent_start) see false, so their
     * sendMessage lands immediately instead of being queued as steering.
     */
    private looping;
    /** Abort handle of the operation in flight; abort() and ctx.abort() fire it. */
    private activeAbort;
    constructor(options: AgentOptions);
    /** Names of the active tools, the ones declared to the model (pi getActiveTools). */
    getActiveTools(): string[];
    /** Every registered tool, active or not (pi getAllTools). */
    getAllTools(): ToolInfo[];
    /** Tools callable through `ctx.executeTool()`, without `execute`. */
    getCallableTools(): ToolInfo[];
    /**
     * Set the active tools by name (pi setActiveTools): unknown and `hidden` tools are
     * ignored, and the change applies from the next request. Only registered tools can
     * be activated, so a permission mode that never registered a tool keeps it out
     * (D20). Deactivating any tool drops the names still pending from the transcript;
     * a change that only adds tools (tool_search loading some) keeps them (pi).
     */
    setActiveTools(toolNames: readonly string[]): void;
    /**
     * Set the host's default tools (pi usesDefaultTools, 1.4 #10785): unlike setActiveTools, the
     * tools a resumed session recorded still win, with `modifiers` (`--tools +name`/`-name`)
     * applied to them. A session that recorded none keeps these.
     */
    setDefaultTools(toolNames: readonly string[], modifiers?: readonly string[]): void;
    /**
     * The tools the model sees in the next request: active, with loadout descriptions,
     * without hidden declarations. Hosts build their system prompt's tool list from it.
     */
    getDeclaredTools(): ToolInfo[];
    /**
     * The context one tool call's execute receives (pi ExtensionToolContext): the
     * shared context plus executeTool/tools bound to this call. Only tools get it —
     * handlers keep the plain context, so a tool_call handler cannot recurse.
     */
    private toolContext;
    /** The model-visible conversation: the session log's projection (read-only; P22). */
    get messages(): readonly AgentMessage[];
    /** Run a log write after every earlier one; its failure goes to the caller. */
    private enqueueWrite;
    /** Enqueue a write nobody awaits (sync extension actions); a failure surfaces at the next flushWrites(). */
    private enqueueBackground;
    /** Wait for every queued write; rethrow a background write's failure. */
    private flushWrites;
    /** Rebuild `messages` from the log's active path (pi _refreshFinalizedContext). */
    private refresh;
    /**
     * P42b (tech debt #17): when the log only grew by entries that continue the active path
     * (the usual write), extend the path, projection, entry index and usage in place instead
     * of rebuilding them. A compaction or context edit (they change earlier context), a move
     * (the new entries do not hang under the old leaf) or anything else falls back to the full
     * rebuild. Returns whether it applied.
     */
    private appendInPlace;
    /** Append a message to the log and refresh the projection; returns its entry id. Call inside the writer. */
    private appendAndRefresh;
    /** Log a message after every queued write; returns its entry id. */
    private record;
    /**
     * Append a message the host produced outside the loop (e.g. the TUI's `!`
     * user bash) to the log. Replaces pushing onto `messages`, which is now a
     * read-only projection.
     */
    appendMessage(message: AgentMessage): Promise<void>;
    /**
     * Log a system message when the prompt or the tool set differs from what the
     * transcript replays to (pi _preparePromptAndToolLoadout + declareToolChanges).
     * The kernel has one section, `prompt`, holding the effective prompt text, so
     * the folded wire prompt equals the plain prompt byte for byte.
     */
    private declareLoadout;
    /**
     * The verifyTranscript check: the projection rebuilt from the logged path equals
     * `messages`. Logged entries are deep-frozen (P22), so the messages they hold are the very
     * objects the projection uses: those compare by identity; a message a projection derives
     * anew (a summary, an edited one) compares by its JSON, worked out once per object (P42b:
     * this used to serialize the whole log on every request).
     */
    private assertTranscriptMatchesLog;
    private readonly messageJsonCache;
    /** A message's JSON, once per message object (they do not change once made). */
    private messageJson;
    /** The read-only session view handed to extensions as ctx.sessionManager (pi ReadonlySessionManager). */
    sessionManager(): ReadonlySessionManager;
    /**
     * Wait until every queued log write has landed in `messages`. Extension
     * actions such as an idle `sendMessage` append asynchronously (the store is
     * async); a host that renders new messages after running an extension command
     * awaits this first. Rethrows a failed background write.
     */
    flush(): Promise<void>;
    /** Session entry id a projected message came from (undefined for messages not in `messages`). */
    entryIdOf(message: AgentMessage): string | undefined;
    /** Fire message_start/message_end for a non-streamed message, then log it. */
    private commitMessage;
    /** commitMessage, also returning the session entry id. */
    private commitMessageWithId;
    /**
     * ctx.ui for extensions (P40d, pi): a prompt that waits for the user is bracketed by
     * ui_prompt_start and ui_prompt_end. Approvals do not go through it (pi: extension prompts only).
     */
    private promptingUi;
    /** Context handed to extension handlers; also usable by hosts to dispatch extension commands. */
    extensionContext(): ExtensionContext;
    setUi(ui: UiCapability | undefined): void;
    private runSubagent;
    /** A usage with its cost filled from the host pricing, when there is one (D19-3). */
    private priced;
    /** Prices for a model: the fixed pricing, or what the host's resolver says for it. */
    private pricingFor;
    /** The extension command a sent message names, with its arguments (P43, as hosts parse typed input). */
    private commandOf;
    /** Run a command named by sendUserMessage (P43); the prompt it returns goes out with the message's images. */
    private runSentCommand;
    /** The model this agent's requests use. */
    get model(): string;
    /** The provider of that model, as assistant messages record it (P43: the shell tools' TAU_SESSION_PROVIDER). */
    get provider(): string;
    /**
     * Use another model from the next request on and log a model_change entry
     * (pi). No events: hosts run model_select around it; the extension-facing
     * `api.setModel` does that itself.
     *
     * `options` switch the provider with it (P28, D21 addendum): another
     * transport (endpoint, key, wire protocol), the provider and api labels the
     * messages carry, and the model's context window. The switch takes effect
     * between requests; the transports turn earlier messages from another model
     * into what the new one accepts (pi transformMessages).
     */
    setModel(model: string, options?: SetModelOptions): void;
    /** The thinking level sent with requests; undefined = the endpoint's default. */
    get thinkingLevel(): ThinkingLevel | undefined;
    /** Change the thinking level from the next request on; logs a thinking_level_change entry (pi words: "none" → "off"). */
    setThinkingLevel(level: ThinkingLevel | undefined): void;
    /** The permission mode tool calls are checked against (D20). */
    getPermissionMode(): PermissionMode;
    /**
     * Change the permission mode from the next tool call on; subagents spawned
     * afterwards inherit it (tau, P25: the TUI's Shift+Tab). Not recorded in the
     * session: a resumed session takes its mode from the host again, so a
     * session never comes back more permissive than the host started it.
     */
    setPermissionMode(mode: PermissionMode): void;
    /** The extension-facing model switch: model_select before, the switch, model_select after (pi setModel). */
    private switchModelForExtension;
    /**
     * Header changes from before_provider_headers for one request (P23d). A
     * transport that does not apply headers cannot honor them, so registering a
     * handler with it is an error, not a silent no-op (D15 supplement).
     */
    private providerHeaders;
    /**
     * The provider event callbacks for one request (P40d, pi before_provider_request,
     * after_provider_response, provider_stream_event). A transport that does not report them
     * cannot honor them, so registering a handler with it is an error (D15 supplement).
     */
    private providerEventHooks;
    /**
     * The transport for summary requests (compaction, turn prefix, branch summary):
     * the agent's model and thinking level, no prompt-cache write (pi 0.82.0), and
     * the before_provider_headers changes — pi runs the hook for every provider call.
     */
    private summaryTransport;
    /** The output cap for summary requests: the summary model's when there is one (P53c), else the agent model's. */
    private summaryMaxTokens;
    /**
     * Current context-token estimate (pi semantics: last assistant usage + trailing heuristic).
     * With a window, `reserveTokens` is what compaction keeps free (P49: read's allow_large
     * reads what is left between them).
     */
    getContextUsage(): ContextUsageEstimate & {
        contextWindow?: number;
        reserveTokens?: number;
    };
    /**
     * Everything this session was billed for, all branches, compaction-proof
     * (D19): assistant turns, summaries and tool-reported usage. Hosts show it as
     * the session cost; the context estimate is getContextUsage().
     */
    getSessionUsage(): Usage;
    /** Compaction settings for the configured window (small windows scale the budgets down). */
    private effectiveCompactionSettings;
    /** Claim the agent for one operation; a second one throws busy (D14: a programming error). */
    private beginOperation;
    /** Release the agent; extension messages held during a compaction or navigation land now. */
    private endOperation;
    /** Summarization retry for this agent: its RetrySettings, the host sleep, events through emit. */
    private summarizationRetry;
    /**
     * One request with the current model and transport, outside the conversation: nothing
     * is recorded or added to the context, and the agent is not held (P34f: session titles).
     * Throws on a failed or truncated reply.
     */
    completeText(systemPrompt: string, prompt: string, options?: {
        maxTokens?: number;
        signal?: TauAbortSignal;
    }): Promise<string>;
    /** Name the session (pi setSessionName), in turn with the run's writes (P58: a host's recorder beside them forked the log). */
    setSessionName(name: string): Promise<void>;
    /**
     * Label an entry of the session tree, or clear its label with an empty one (P39f, the
     * host's /tree; pi's setLabel through appendLabelChange). Throws for an unknown entry.
     */
    setLabel(entryId: string, label: string | undefined): Promise<void>;
    /**
     * A side question (P39c, Claude Code's /btw): the conversation as the next request would
     * send it (same prefix, so a warm prompt cache serves it), the newest 20 earlier side
     * exchanges, then the question. Nothing joins the conversation and no tool runs; the
     * request still declares the tools (a changed prefix would miss the cache), so tool
     * calls the model makes anyway are only counted. Works while a run is in flight: it sees
     * the committed messages, not the reply being written. Its usage is logged as a `usage`
     * entry so session totals stay honest.
     */
    askAside(question: string, options?: {
        exchanges?: ReadonlyArray<{
            question: string;
            answer: string;
        }>;
        signal?: TauAbortSignal;
        onText?: (delta: string) => void;
    }): Promise<{
        text: string;
        toolCalls: number;
        stopReason: AssistantMessage["stopReason"];
    }>;
    /**
     * Compact the conversation now: summarize old history (session_before_compact
     * may cancel or take over) and append a compaction entry; the projection
     * becomes [summary, ...kept]. Holds the agent: throws busy while a run,
     * compaction or navigation is in flight, and abort() cancels it.
     *
     * Resolves undefined when there is nothing to compact. A failure reports
     * session_compact_failed first, then rejects (pi manual semantics):
     * TauError("cancelled") when a session_before_compact handler cancelled,
     * TauError("aborted") on abort, TauError("compaction_failed") otherwise.
     */
    compact(customInstructions?: string, reason?: "manual" | "threshold", signal?: TauAbortSignal, onEvent?: (event: AgentEvent) => void): Promise<CompactionResult | undefined>;
    /**
     * Queue input for the running prompt: committed after the current turn, before
     * the next request. Input handlers see it first (pi 0.86.0, streamingBehavior
     * "steer") and may transform or consume it; the result says which (pi 0.99.0).
     */
    steer(content: UserContent): Promise<QueuedInputDisposition>;
    /** Queue input consumed when the prompt would otherwise end; the loop then continues. Same input rules as steer(). */
    followUp(content: UserContent): Promise<QueuedInputDisposition>;
    /** Run input handlers on queued input, then enqueue what survives (pi _queueUserInput). */
    private queueUserInput;
    hasQueuedMessages(): boolean;
    /**
     * Empty both queues and return what they held (pi AgentSession.clearQueue).
     * Queues survive an abort by design; hosts call this when a run ends to restore
     * the user's queued text to the editor instead of replaying it later.
     */
    clearQueue(): {
        steering: (UserMessage | CustomMessage)[];
        followUp: (UserMessage | CustomMessage)[];
    };
    /** True when no operation holds the agent (pi isIdle). */
    get isIdle(): boolean;
    /** Abort signal of the operation in flight; undefined when idle (pi ctx.signal). */
    get signal(): TauAbortSignal | undefined;
    /**
     * Abort whatever holds the agent — a run with its retry backoff, a compaction,
     * a branch summary (pi AgentSession.abort). No-op when idle.
     */
    abort(reason?: string): void;
    clearSteeringQueue(): void;
    clearFollowUpQueue(): void;
    prompt(input: UserContent, signal?: TauAbortSignal): AsyncGenerator<AgentEvent>;
    /** agent_settled once the agent is idle; runs started from its handlers go to the host afterwards. */
    private settle;
    private notifySettled;
    /**
     * Run the agent loop without committing new user input — the continuation
     * behind sendMessage({triggerTurn:true}) while idle (pi's _runAgentPrompt
     * for app messages). The conversation must already end in something the
     * model should answer (a user/custom message).
     */
    resume(signal?: TauAbortSignal): AsyncGenerator<AgentEvent>;
    /**
     * Navigate the session tree to another entry (pi's navigateTree): summarize
     * the branch being abandoned (unless summarize: false or an extension
     * supplies/cancels via session_before_tree), move the leaf, record a
     * branch_summary entry at the new position, and rebuild the conversation
     * from the new path. A user or custom message as the target moves the leaf to
     * its parent and returns its text as `editorText`, to edit and send again (pi;
     * P38c — before, tau moved to the message itself).
     */
    navigateTo(entryId: string, options?: {
        summarize?: boolean;
        customInstructions?: string;
        /** P40d (pi): customInstructions replace the default summary prompt. */
        replaceInstructions?: boolean;
        /** P40d (pi): a label for the branch summary entry (or the target, without a summary). */
        label?: string;
        signal?: TauAbortSignal;
        onEvent?: (event: AgentEvent) => void;
    }): Promise<{
        cancelled: boolean;
        editorText?: string;
    }>;
    private runPrompt;
    /**
     * before_agent_start for a run: extensions rewrite the system prompt and may
     * inject custom messages. Shared by prompt() and resume() — resume() used the
     * base prompt, dropping e.g. injected skills and the provider's prompt cache (HF-10).
     */
    private applyBeforeAgentStart;
    /**
     * A prompt's runs (pi _runAgentPrompt). The turn loop ends with agent_end;
     * messages queued meanwhile — e.g. by agent_end handlers — start a
     * continuation run in the same generator (pi _handlePostAgentRun, no timers);
     * then agent_before_settle may append drafts and ask for one more run. The
     * turn budget spans all runs of the prompt.
     */
    private runLoop;
    /** Whether a request now has something to answer: the context does not end with the assistant, or messages are queued. */
    private canContinue;
    /** Custom messages boundary handlers appended, for the run loop to announce as user_message events. */
    private readonly boundaryMessages;
    /** Append a boundary handler's drafts in order (custom entries and custom messages). */
    private commitBoundaryDrafts;
    /**
     * One run's turns, in pi runLoop order: steering is polled at the start;
     * each turn prepares (compaction) → polls steering again if none was pending →
     * turn_start → commits pending messages → requests → runs the tool batch →
     * finishTurn → turn_end; steering is polled after turn_end, follow-ups when
     * the run would stop. Returns how the run ended; agent_end has fired.
     */
    private runTurns;
    /**
     * Run the actionable turn_end boundary: drafts land in order, and a continue
     * request is honored only for a completed turn with runnable context.
     */
    private runTurnEndBoundary;
    /**
     * End a run early (abort): a retry cycle in progress closes as cancelled (pi
     * _finishCancelledRetry), then agent_end — never a throw (D14).
     */
    private endRun;
    /** Commit one tool result to messages/session and produce the AgentEvent pair tail. */
    private commitToolResult;
    /** What the split-out modules read (P51: agent-internals.ts): getters read the current values. */
    private exposeInternals;
}
/** P53c: a separate model for summaries (compaction, turn prefixes, branch summaries). */
export interface SummaryModel {
    transport: ChatTransport;
    model: string;
    /** Its output cap, for the summary request's max_tokens (pi 1.4); absent: the reserve-based size. */
    maxOutputTokens?: number;
}
