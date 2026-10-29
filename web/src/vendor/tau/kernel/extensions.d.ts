import type { ActivityOutcome, AfterProviderResponseEvent, AutoRetryEndEvent, AutoRetryStartEvent, BoundaryDraft, CommandPromptEvent, CustomMessageInput, EventBus, ExtensionErrorSink, InputSource, MarkdownTransformer, ModelSelectEventResult, ProjectTrustEventResult, ProviderStreamEvent, ResourcesDiscoverEvent, ResourcesDiscoverResult, SessionBeforeCompactEvent, SessionBeforeCompactResult, SessionBeforeForkEvent, SessionBeforeForkResult, SessionBeforeSwitchEvent, SessionBeforeTreeEvent, SessionBeforeTreeResult, SessionCompactEvent, SessionCompactFailedEvent, SessionShutdownEvent, SessionStartEvent, SessionTreeEvent, SubagentStartEvent, SubagentStopEvent, ThinkingLevel, ThinkingLevelSelectEventResult, ToolCallEvent, ToolExecutionEndEvent, ToolExecutionStartEvent, ToolExecutionUpdateEvent, ToolResultEvent, TurnEndEvent, UIPromptEndEvent, UIPromptStartEvent, UserBashEvent, UserBashEventResult } from "./extension-events.js";
import type { Extension, ExtensionContext, ExtensionHostActions, ExtensionLoadError, InlineExtension, RegisteredCommand, RegisteredDiagnostic, RegisteredEntryRenderer, RegisteredFlag, RegisteredMessageRenderer, RegisteredShortcut, RegisteredStatusItem, RegisteredToolRenderer, RegisteredWidget } from "./extension-types.js";
import type { AgentMessage, AssistantMessage, ImageContent, UserContent } from "./messages.js";
import type { ChatStreamEvent } from "./openai.js";
import { type Tool, type ToolInfo } from "./tools.js";
export * from "./extension-events.js";
export * from "./extension-types.js";
export type { ToolInfo };
/**
 * The extension API deliberately mirrors pi's extension API
 * (pi-mono/packages/coding-agent/src/core/extensions/types.ts) — same event
 * names, event shapes, and result conventions — restricted to the subset that
 * is meaningful in a runtime-agnostic kernel. Porting a pi extension should be
 * mechanical; deviations exist only where pi's API assumes its product layer
 * (TUI components, typebox schemas, session manager).
 */
/** The user content a command's prompt sends: its text, followed by its images. */
export declare function commandPromptContent(output: {
    text: string;
    images?: ImageContent[];
}): UserContent;
export declare class ExtensionRegistry {
    readonly tools: Map<string, Tool>;
    readonly commands: Map<string, RegisteredCommand>;
    readonly shortcuts: Map<string, RegisteredShortcut>;
    readonly messageRenderers: Map<string, RegisteredMessageRenderer>;
    readonly entryRenderers: Map<string, RegisteredEntryRenderer>;
    /** P40d: each extension's markdown transformer, in load order. */
    private readonly markdownTransformerMap;
    readonly toolRenderers: Map<string, RegisteredToolRenderer>;
    readonly widgets: Map<string, RegisteredWidget>;
    readonly headerItems: Map<string, RegisteredStatusItem>;
    readonly footerItems: Map<string, RegisteredStatusItem>;
    readonly diagnostics: Map<string, RegisteredDiagnostic>;
    readonly flags: Map<string, RegisteredFlag>;
    /** Every extension that failed to load so far (rolled back; see add()). */
    readonly loadErrors: ExtensionLoadError[];
    /** Registration conflicts so far (first registration kept). */
    readonly warnings: string[];
    private readonly flagValues;
    private readonly toolOwners;
    private readonly flagOwners;
    private readonly commandOwners;
    private readonly commandOccurrences;
    private extensionCount;
    private invalidated;
    private hostActions;
    private errorSink;
    private readonly channels;
    /** pi EventBus over a plain map (the kernel has no Node event emitter). */
    readonly events: EventBus;
    private readonly handlers;
    static load(extensions: (Extension | InlineExtension)[]): Promise<ExtensionRegistry>;
    /**
     * Run extensions' setup against this registry (e.g. project extensions after a
     * trust decision), one at a time. Each extension's registrations are staged: a
     * setup that throws is rolled back entirely — no half-registered tool without
     * its guard — and reported in `errors`; the others still load (pi). Same-name
     * tools and flags keep the first registration and report a warning. Never
     * throws; the results also accumulate in `loadErrors` / `warnings`.
     */
    add(extensions: (Extension | InlineExtension)[]): Promise<{
        errors: ExtensionLoadError[];
        warnings: string[];
    }>;
    /**
     * Mark this registry stale (pi runtime invalidate): every api and ctx it handed
     * out throws from now on. Hosts call it after a replacement registry took over.
     */
    invalidate(): void;
    /** Throw when this registry was invalidated; the Agent checks it before acting on a ctx call. */
    assertActive(): void;
    /** Register a command; duplicates get pi's occurrence suffixes (`name:1`, `name:2`, …). */
    private addCommand;
    /** Copy of everything an extension's setup can change, for rollback. */
    private snapshot;
    private restore;
    /** The api one extension's setup receives; it goes stale with a failed setup or an invalidated registry. */
    private createApi;
    /**
     * Back the extension action methods. Merges: the Agent attaches the core
     * actions on construction, and the host may attach more (submitPrompt,
     * resumeTurn, abort) before or after — later attachments override same-name
     * actions without clobbering the rest.
     */
    attachHostActions(actions: ExtensionHostActions): void;
    /** Look up a host action if any host attached it (Agent-internal helper). */
    hostAction<K extends keyof ExtensionHostActions>(name: K): ExtensionHostActions[K];
    private requireHostActions;
    /** Host-side: isolate notification handlers — their errors go to `sink` instead of the caller (HF-12). */
    setErrorSink(sink: ExtensionErrorSink | undefined): void;
    /** Run notification handlers in order; with an error sink, one failing handler does not stop the rest. */
    private dispatch;
    notifySessionCompactFailed(event: Omit<SessionCompactFailedEvent, "type">, ctx: ExtensionContext): Promise<void>;
    /** Host-side: supply parsed CLI flag values. Unknown names are ignored (host validates). */
    setFlagValues(values: Record<string, boolean | string>): void;
    getFlag(name: string): boolean | string | undefined;
    /** Run input handlers. Transforms chain; "handled" short-circuits. */
    runInput(text: string, ctx: ExtensionContext, options?: {
        source?: InputSource;
        streamingBehavior?: "steer" | "followUp";
        images?: ImageContent[];
    }): Promise<{
        handled: boolean;
        text: string;
        images?: ImageContent[];
    }>;
    /** Run tool_call handlers. Handlers may mutate event.input; a block short-circuits; "ask" beats "allow". */
    runToolCall(event: ToolCallEvent, ctx: ExtensionContext): Promise<{
        blocked: boolean;
        reason?: string;
        terminate?: boolean;
        permission?: "allow" | "ask";
    }>;
    /** Run tool_result handlers. Partial overrides chain onto the event. */
    runToolResult(event: ToolResultEvent, ctx: ExtensionContext): Promise<Pick<ToolResultEvent, "content" | "details" | "isError" | "usage" | "structuredContent">>;
    /** Run user_bash handlers. Rewrites chain; cancel short-circuits. */
    runUserBash(event: Omit<UserBashEvent, "type">, ctx: ExtensionContext): Promise<NonNullable<UserBashEventResult> & {
        command: string;
        recordInContext: boolean;
    }>;
    /** Run model_select before handlers. Rewrites chain; cancel short-circuits. */
    runModelSelectBefore(event: {
        currentModel: string;
        requestedModel: string;
    }, ctx: ExtensionContext): Promise<NonNullable<ModelSelectEventResult> & {
        model: string;
    }>;
    notifyModelSelected(event: {
        previousModel: string;
        requestedModel: string;
        selectedModel: string;
    }, ctx: ExtensionContext): Promise<void>;
    /** Run thinking_level_select before handlers. Rewrites chain; cancel short-circuits. */
    runThinkingLevelSelectBefore(event: {
        currentLevel?: ThinkingLevel;
        requestedLevel?: ThinkingLevel;
    }, ctx: ExtensionContext): Promise<NonNullable<ThinkingLevelSelectEventResult> & {
        level?: ThinkingLevel;
    }>;
    notifyThinkingLevelSelected(event: {
        previousLevel?: ThinkingLevel;
        requestedLevel?: ThinkingLevel;
        selectedLevel?: ThinkingLevel;
    }, ctx: ExtensionContext): Promise<void>;
    /**
     * Request-time transforms in two phases (pi ExtensionRunner.emitContext).
     * `context` handlers see the conversation only and the prompt/tool state is
     * restored after each (a changed list is re-headed with the replayed system
     * message); `context_with_system` handlers then see the full transcript and
     * their output is used as returned. Works on a JSON copy, so in-place edits
     * never reach the session log's projection.
     */
    runContext(messages: readonly AgentMessage[], ctx: ExtensionContext): Promise<AgentMessage[]>;
    /** Run before_agent_start handlers. systemPrompt replacements chain; injected messages accumulate. */
    runBeforeAgentStart(prompt: string, systemPrompt: string, ctx: ExtensionContext): Promise<{
        systemPrompt: string;
        messages: CustomMessageInput[];
    }>;
    notifyMessageStart(message: AgentMessage, ctx: ExtensionContext): Promise<void>;
    notifyMessageUpdate(message: AssistantMessage, event: ChatStreamEvent, ctx: ExtensionContext): Promise<void>;
    /** Run message_end handlers. Replacements chain; a replacement changing the role is ignored. */
    runMessageEnd(message: AgentMessage, ctx: ExtensionContext): Promise<AgentMessage>;
    notifyToolExecutionStart(event: Omit<ToolExecutionStartEvent, "type">, ctx: ExtensionContext): Promise<void>;
    notifyToolExecutionUpdate(event: Omit<ToolExecutionUpdateEvent, "type">, ctx: ExtensionContext): Promise<void>;
    notifyToolExecutionEnd(event: Omit<ToolExecutionEndEvent, "type">, ctx: ExtensionContext): Promise<void>;
    /** Run project_trust handlers. The first decisive answer (yes/no) wins. */
    runProjectTrust(cwd: string, ctx: ExtensionContext): Promise<NonNullable<ProjectTrustEventResult>>;
    runResourcesDiscover(cwd: string, reason: ResourcesDiscoverEvent["reason"], ctx: ExtensionContext): Promise<Required<ResourcesDiscoverResult>>;
    notifySessionStart(reason: SessionStartEvent["reason"], ctx: ExtensionContext): Promise<void>;
    notifySessionShutdown(reason: SessionShutdownEvent["reason"], ctx: ExtensionContext): Promise<void>;
    notifySessionInfoChanged(name: string | undefined, ctx: ExtensionContext): Promise<void>;
    /** Run session_before_compact handlers: cancel or takeover short-circuits. */
    runSessionBeforeCompact(event: Omit<SessionBeforeCompactEvent, "type">, ctx: ExtensionContext): Promise<NonNullable<SessionBeforeCompactResult>>;
    notifySessionCompact(event: Omit<SessionCompactEvent, "type">, ctx: ExtensionContext): Promise<void>;
    /** Run session_before_fork handlers: a cancel short-circuits. */
    runSessionBeforeFork(event: Omit<SessionBeforeForkEvent, "type">, ctx: ExtensionContext): Promise<NonNullable<SessionBeforeForkResult>>;
    /** Run session_before_tree handlers: cancel short-circuits; summary/customInstructions merge (later wins). */
    runSessionBeforeTree(event: Omit<SessionBeforeTreeEvent, "type">, ctx: ExtensionContext): Promise<NonNullable<SessionBeforeTreeResult>>;
    notifySessionTree(event: Omit<SessionTreeEvent, "type">, ctx: ExtensionContext): Promise<void>;
    /** Run session_before_switch handlers; the first cancel wins (pi semantics). */
    runSessionBeforeSwitch(event: Omit<SessionBeforeSwitchEvent, "type">, ctx: ExtensionContext): Promise<{
        cancelled: boolean;
    }>;
    notifyAutoRetryStart(event: Omit<AutoRetryStartEvent, "type">, ctx: ExtensionContext): Promise<void>;
    notifyAutoRetryEnd(event: Omit<AutoRetryEndEvent, "type">, ctx: ExtensionContext): Promise<void>;
    notifyAgentStart(ctx: ExtensionContext): Promise<void>;
    notifyAgentEnd(messages: readonly AgentMessage[], ctx: ExtensionContext): Promise<void>;
    notifyTurnStart(turnIndex: number, ctx: ExtensionContext): Promise<void>;
    /** Report a diagnostic to the host's error sink (dropped without one). */
    reportError(event: string, message: string): void;
    /**
     * Run an actionable boundary (pi emitBoundary): each handler sees the drafts
     * so far; a result's `entries` replaces them, its `continue` overrides the
     * request. Handler errors follow the notification rules (sink or throw).
     */
    private runBoundary;
    runTurnEnd(event: Omit<TurnEndEvent, "type" | "entries" | "continue">, ctx: ExtensionContext): Promise<{
        entries: BoundaryDraft[];
        continue: boolean;
    }>;
    runBeforeSettle(outcome: ActivityOutcome, ctx: ExtensionContext): Promise<{
        entries: BoundaryDraft[];
        continue: boolean;
    }>;
    /** Whether any before_provider_headers handler is registered (the agent checks transport support first). */
    hasProviderHeaderHandlers(): boolean;
    /**
     * Run before_provider_headers: handlers mutate one headers object in turn.
     * Their errors propagate (they change what goes on the wire; never ignored).
     */
    runBeforeProviderHeaders(ctx: ExtensionContext): Promise<Record<string, string | null>>;
    /** Whether any provider event handler (request, response, stream) is registered (P40d). */
    hasProviderEventHandlers(): boolean;
    /** Which provider events have handlers (the agent wires only those into the request). */
    providerEventHandlers(): {
        request: boolean;
        response: boolean;
        stream: boolean;
    };
    /**
     * Run before_provider_request (P40d, pi): each handler sees the payload so far; a result that
     * is not undefined replaces it. Errors propagate: they change what goes on the wire.
     */
    runBeforeProviderRequest(payload: unknown, ctx: ExtensionContext): Promise<unknown>;
    notifyAfterProviderResponse(event: Omit<AfterProviderResponseEvent, "type">, ctx: ExtensionContext): Promise<void>;
    /** provider_stream_event (P40d): read-only and in stream order; not awaited, errors go to the error sink. */
    notifyProviderStreamEvent(event: Omit<ProviderStreamEvent, "type">, ctx: ExtensionContext): void;
    /** Whether any ui_prompt_start / ui_prompt_end handler is registered (P40d). */
    hasUiPromptHandlers(): boolean;
    notifyUiPrompt(event: UIPromptStartEvent | UIPromptEndEvent, ctx: ExtensionContext): Promise<void>;
    /** The extensions' markdown transformers in load order (P40d, pi). */
    markdownTransformers(): MarkdownTransformer[];
    notifyAgentSettled(aborted: boolean, ctx: ExtensionContext): Promise<void>;
    /** subagent_start (P44): every handler's context, in order. */
    runSubagentStart(event: Omit<SubagentStartEvent, "type">, ctx: ExtensionContext): Promise<string[]>;
    /** subagent_stop (P44): the first handler asking the child to go on wins. */
    runSubagentStop(event: Omit<SubagentStopEvent, "type">, ctx: ExtensionContext): Promise<string | undefined>;
    /** command_prompt (P44): a cancel from any handler drops the prompt. */
    runCommandPrompt(event: Omit<CommandPromptEvent, "type">, ctx: ExtensionContext): Promise<{
        cancel: boolean;
        reason?: string;
    }>;
    /** Run one handler; with an error sink its failure is reported and counts as no result. */
    private guarded;
}
