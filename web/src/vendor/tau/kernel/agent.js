import { AbortHandle } from "./abort.js";
import { askAside } from "./aside.js";
import { compactionSettingsForWindow, completeText, DEFAULT_COMPACTION_SETTINGS, estimateProjectedContextTokens, shouldCompact, } from "./compaction.js";
import { compactWithEvents, runCompactionOperation } from "./compaction-run.js";
import { SessionError, TauError, toError } from "./errors.js";
import { commandPromptContent, } from "./extensions.js";
import { createFallbackChain } from "./fallback.js";
import { combineUsage, computeUsageCost, emptyUsage, toolCallsOf, userContentText, } from "./messages.js";
import { runNavigation } from "./navigation.js";
import { NestedToolCallRunner } from "./nested-calls.js";
import { createOpenAICompatTransport, OPENAI_COMPLETIONS_API, } from "./openai.js";
import { PendingMessageQueue } from "./pending-queue.js";
import { defaultPlatform } from "./platform.js";
import { createDefaultPolicy } from "./policy.js";
import { buildContextEntries, buildSessionProjection, diffSystemPromptSections, getCurrentSystemMessage, getCurrentSystemPrompt, getCurrentTools, getToolStateChanges, normalizeMessageContent, sessionEntryToContextMessages, sumSessionUsage, toToolDeclaration, } from "./projection.js";
import { DEFAULT_RETRY_SETTINGS } from "./retry.js";
import { InMemorySessionStore, SessionRecorder } from "./session.js";
import { runSubagent } from "./subagent-spawn.js";
import { normalizeToolOutcome, PreflightLock, ToolBatchRunner, } from "./tool-batch.js";
import { ToolState, withoutHiddenDeclarations } from "./tool-state.js";
import { assertToolSchema, errorResult, hasToolSchema, } from "./tools.js";
import { recoverFromFailure } from "./turn-recovery.js";
import { pullResponse } from "./turn-stream.js";
import { uuidv7 } from "./uuid.js";
/** No cap unless the host sets one (pi, Claude Code and Codex do not cap a prompt; Esc stops a run). */
const DEFAULT_MAX_TURNS = Number.POSITIVE_INFINITY;
/** Text of the latest user/custom message — the "prompt" a resumed run answers. */
function lastPromptText(messages) {
    for (let i = messages.length - 1; i >= 0; i--) {
        const message = messages[i];
        if (message.role === "user")
            return userContentText(message.content);
        if (message.role === "custom") {
            return typeof message.content === "string" ? message.content : userContentText(message.content);
        }
    }
    return "";
}
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
export class Agent {
    /** The view of private state the split-out modules read (P51: agent-internals.ts). */
    internals = this.exposeInternals();
    /** The tool set (P50: tool-state.ts). */
    toolState = new ToolState(this.internals);
    /** Projection of the session log (frozen; replaced after every write). */
    projection = Object.freeze([]);
    /** Source entry of each projected message (pi _entryIdsByMessage). */
    entryIds = new WeakMap();
    /** The active path and its projection as of the last refresh (for sync estimates). */
    activePath = [];
    activeProjection = { entries: [], messages: [] };
    /** Session-wide billed usage as of the last refresh (sumSessionUsage). */
    sessionUsage = emptyUsage();
    /** Every entry and the store metadata as of the last refresh (backs ctx.sessionManager). */
    allEntries = [];
    entriesById = new Map();
    sessionMetadata;
    readonlySessionManager;
    platform;
    /** Replaced, not mutated, when setModel switches provider (P28). */
    config;
    transport;
    systemPrompt;
    baseTools;
    extensions;
    ui;
    capabilities;
    maxTurnsPerPrompt;
    steeringQueue;
    followUpQueue;
    session;
    compactionSettings;
    branchSummaryReserveTokens;
    summaryModel;
    continueAfterLength;
    fallback;
    retrySettings;
    pricing;
    /** Model of this agent's requests (Agent.setModel; starts as config.model). */
    currentModel;
    currentThinkingLevel;
    permissionMode;
    policy;
    onApproval;
    toolExecution;
    verifyTranscript;
    imageProcessor;
    /** Custom instructions of a requested compaction, or null when none is pending. */
    pendingCompaction = null;
    /** Custom messages held for the next run's context (sendMessage deliverAs:"nextTurn"). */
    pendingNextTurn = [];
    /**
     * The single writer: every log append (and the projection refresh after it)
     * runs here in order. Sync extension actions enqueue without awaiting; their
     * failure is held in `writeFailure` and rethrown at the next flushWrites().
     */
    writes = Promise.resolve();
    writeFailure;
    /** The operation holding the agent, if any (prompt/resume/compact/navigateTo). */
    operation;
    /** Extension sendMessage calls made while a compaction or navigation holds the agent; appended when it ends. */
    heldMessages = [];
    /** Custom messages sent with triggerTurn:false while the loop runs; committed after the current turn_end (pi 0.84.4). */
    afterTurnMessages = [];
    /** Whether the current prompt reached the turn loop (agent_settled fires only then). */
    runEntered = false;
    /** True while agent_settled handlers run; runs they start wait until all returned (pi 0.87.1). */
    settling = false;
    deferredStarts = [];
    finishTurn;
    isProjectTrusted;
    /** Runs tool batches: preflight (hooks, validation, policy, approval), execution, result hooks. */
    toolRunner;
    preflightLock;
    /** Calls tools make through ctx.executeTool(), and their record per model-issued call. */
    nestedCalls;
    /** Declared tools that prepareLoadout hooks leave out of requests (pi _hiddenDeclarations). */
    hiddenDeclarations = new Set();
    /**
     * True only while the LLM turn loop is in flight — pi's isStreaming. Hooks
     * that fire before the loop (input, before_agent_start) see false, so their
     * sendMessage lands immediately instead of being queued as steering.
     */
    looping = false;
    /** Abort handle of the operation in flight; abort() and ctx.abort() fire it. */
    activeAbort;
    constructor(options) {
        this.platform = options.platform ?? defaultPlatform();
        this.config = options.config;
        this.transport = options.transport ?? createOpenAICompatTransport(this.platform, this.config);
        this.systemPrompt = options.systemPrompt;
        this.baseTools = [...(options.tools ?? [])];
        for (const tool of this.baseTools)
            assertToolSchema(tool);
        this.extensions = options.extensions;
        this.ui = options.ui;
        this.capabilities = { ...options.capabilities, platform: this.platform };
        const seed = (options.initialMessages ?? []).map(normalizeMessageContent);
        this.projection = Object.freeze(seed);
        if (options.session) {
            this.session = options.session;
            this.enqueueBackground(async () => {
                await this.refresh();
                this.toolState.restoreToolsFromTranscript({ initial: true });
            });
        }
        else {
            const store = new InMemorySessionStore({ id: uuidv7(this.platform.randomBytes), cwd: "/", timestamp: new Date().toISOString() }, this.platform.randomBytes);
            this.session = SessionRecorder.attach(store, null);
            if (seed.length > 0) {
                this.enqueueBackground(async () => {
                    for (const message of seed)
                        await this.session.recordMessage(message);
                    await this.refresh();
                    this.toolState.restoreToolsFromTranscript({ initial: true });
                });
            }
        }
        this.maxTurnsPerPrompt = options.maxTurnsPerPrompt ?? DEFAULT_MAX_TURNS;
        this.steeringQueue = new PendingMessageQueue(options.steeringMode ?? "one-at-a-time");
        this.followUpQueue = new PendingMessageQueue(options.followUpMode ?? "one-at-a-time");
        this.compactionSettings = { ...DEFAULT_COMPACTION_SETTINGS, ...options.compaction };
        this.branchSummaryReserveTokens = options.branchSummary?.reserveTokens;
        this.summaryModel = options.summaryModel;
        this.continueAfterLength = Math.max(0, options.continueAfterLength ?? 0);
        this.fallback = options.fallback && options.fallback.routes.length > 0 ? options.fallback : undefined;
        this.retrySettings = { ...DEFAULT_RETRY_SETTINGS, ...options.retry };
        this.pricing = options.pricing;
        this.currentModel = options.config.model;
        this.currentThinkingLevel = options.thinkingLevel;
        this.permissionMode = options.permissionMode ?? "autonomous";
        this.policy = options.policy ?? createDefaultPolicy();
        this.onApproval = options.onApproval;
        this.toolExecution = options.toolExecution ?? "parallel";
        this.verifyTranscript = options.verifyTranscript ?? true;
        this.imageProcessor = options.imageProcessor;
        this.finishTurn = options.finishTurn;
        this.isProjectTrusted = options.isProjectTrusted;
        this.preflightLock = options.preflightLock ?? new PreflightLock();
        const currentPermissionMode = () => this.permissionMode;
        this.toolRunner = new ToolBatchRunner({
            extensions: this.extensions,
            policy: this.policy,
            // Read on every preflight: setPermissionMode() applies to the next tool call.
            get permissionMode() {
                return currentPermissionMode();
            },
            // Read on every call: a host may move the session's working directory (P46, worktrees).
            get cwd() {
                return options.capabilities?.fs?.cwd;
            },
            onApproval: this.onApproval,
            ui: () => this.ui,
            preflightLock: this.preflightLock,
            toolContext: (ctx, toolCallId, signal) => this.toolContext(ctx, toolCallId, signal),
            commit: (toolCall, outcome, ctx, batch) => this.commitToolResult(toolCall, outcome, ctx, batch),
            ...(options.imageProcessor ? { images: options.imageProcessor } : {}),
        });
        this.nestedCalls = new NestedToolCallRunner({
            getTools: () => this.toolState.callableTools(),
            isSequential: () => this.toolExecution === "sequential",
            runToolCall: (toolCall, tools, parentToolCallId, signal, onUpdate) => this.toolRunner.runNested(toolCall, tools, this.extensionContext(), parentToolCallId, signal, onUpdate),
        });
        options.extensions?.attachHostActions({
            sendMessage: (input, sendOptions) => {
                const message = {
                    role: "custom",
                    customType: input.customType,
                    content: input.content,
                    display: input.display ?? true,
                    details: input.details,
                    timestamp: Date.now(),
                };
                // pi semantics (agent-session.sendCustomMessage): nextTurn always holds;
                // while streaming, deliverAs picks the queue (default steer); while idle
                // the message lands immediately and triggerTurn may start a run.
                if (sendOptions?.deliverAs === "nextTurn") {
                    this.pendingNextTurn.push(message);
                    return;
                }
                if (this.looping) {
                    // Streaming with triggerTurn:false must not steer: it lands after the current
                    // turn_end without asking for a request (pi 0.84.2/0.84.4).
                    if (sendOptions?.triggerTurn === false)
                        this.afterTurnMessages.push(message);
                    else if (sendOptions?.deliverAs === "followUp")
                        this.followUpQueue.enqueue(message);
                    else
                        this.steeringQueue.enqueue(message);
                    return;
                }
                // A compaction or navigation is rewriting the log: append once it ends, so the
                // message lands on the resulting path instead of an abandoned one.
                if (this.operation === "compact" || this.operation === "navigate") {
                    this.heldMessages.push({ message, triggerTurn: sendOptions?.triggerTurn === true });
                    return;
                }
                this.enqueueBackground(() => this.appendAndRefresh(message));
                if (sendOptions?.triggerTurn) {
                    const resume = this.extensions?.hostAction("resumeTurn");
                    if (resume && this.settling)
                        this.deferredStarts.push(resume);
                    else
                        resume?.();
                }
            },
            sendUserMessage: (content, sendOptions) => {
                // P43 (pi expandPromptTemplates): `/name args` naming an extension command runs it at
                // once, even mid-run; the prompt it returns is sent the usual way.
                if (sendOptions?.expandPromptTemplates) {
                    const command = this.commandOf(content);
                    if (command) {
                        void this.runSentCommand(command, content, sendOptions.deliverAs);
                        return;
                    }
                }
                // pi semantics: always triggers a turn. Streaming → steer/followUp; idle →
                // the host submits a fresh prompt (no host action → the call is a no-op
                // beyond a queued message, so require it explicitly).
                if (this.looping) {
                    // Queued input from an extension passes the input handlers too (pi source "extension").
                    const behavior = sendOptions?.deliverAs === "followUp" ? "followUp" : "steer";
                    this.enqueueBackground(() => this.queueUserInput(content, behavior, "extension"));
                    return;
                }
                // Between prompt() starting and the loop running (input, before_agent_start)
                // the agent is neither idle nor streaming: a host submitPrompt would be
                // refused and the message silently lost, so say so (HF-11).
                if (this.operation === "prompt" || this.operation === "resume") {
                    throw new TauError("busy", "sendUserMessage() before the agent loop started; use sendMessage({ deliverAs })");
                }
                if (this.operation !== undefined) {
                    throw new TauError("busy", `sendUserMessage() while ${this.operation === "compact" ? "compacting" : "navigating"}; use sendMessage({ deliverAs })`);
                }
                const submit = this.extensions?.hostAction("submitPrompt");
                if (!submit) {
                    throw new TauError("no_host", "sendUserMessage() while idle requires a host that provides submitPrompt");
                }
                if (this.settling)
                    this.deferredStarts.push(() => submit(content));
                else
                    submit(content);
            },
            appendEntry: (customType, data) => {
                this.enqueueBackground(async () => {
                    await this.session.appendCustom(customType, data);
                    await this.refresh();
                });
            },
            setSessionName: (name) => {
                this.enqueueBackground(() => this.session.setName(name).then(() => this.refresh()));
                void this.extensions?.notifySessionInfoChanged(name, this.extensionContext());
            },
            setLabel: (entryId, label) => {
                // pi throws for an unknown target; the check runs on the last refreshed log.
                if (!this.entriesById.has(entryId))
                    throw new SessionError("not_found", `Entry ${entryId} not found`);
                this.enqueueBackground(async () => {
                    await this.session.appendLabel(entryId, label);
                    await this.refresh();
                });
            },
            abort: () => {
                this.abort("Aborted by extension");
            },
            getActiveTools: () => this.toolState.getActiveTools(),
            getAllTools: () => this.toolState.getAllTools(),
            setActiveTools: (toolNames) => this.toolState.setActiveTools(toolNames),
            getSessionName: () => this.sessionMetadata?.name,
            setModel: (model) => this.switchModelForExtension(model),
            getThinkingLevel: () => this.currentThinkingLevel,
            setThinkingLevel: (level) => this.setThinkingLevel(level),
        });
    }
    /** Names of the active tools, the ones declared to the model (pi getActiveTools). */
    getActiveTools() {
        return this.toolState.getActiveTools();
    }
    /** Every registered tool, active or not (pi getAllTools). */
    getAllTools() {
        return this.toolState.getAllTools();
    }
    /** Tools callable through `ctx.executeTool()`, without `execute`. */
    getCallableTools() {
        return this.toolState.getCallableTools();
    }
    /**
     * Set the active tools by name (pi setActiveTools): unknown and `hidden` tools are
     * ignored, and the change applies from the next request. Only registered tools can
     * be activated, so a permission mode that never registered a tool keeps it out
     * (D20). Deactivating any tool drops the names still pending from the transcript;
     * a change that only adds tools (tool_search loading some) keeps them (pi).
     */
    setActiveTools(toolNames) {
        this.toolState.setActiveTools(toolNames);
    }
    /**
     * Set the host's default tools (pi usesDefaultTools, 1.4 #10785): unlike setActiveTools, the
     * tools a resumed session recorded still win, with `modifiers` (`--tools +name`/`-name`)
     * applied to them. A session that recorded none keeps these.
     */
    setDefaultTools(toolNames, modifiers = []) {
        this.toolState.setDefaultTools(toolNames, modifiers);
    }
    /**
     * The tools the model sees in the next request: active, with loadout descriptions,
     * without hidden declarations. Hosts build their system prompt's tool list from it.
     */
    getDeclaredTools() {
        return this.toolState.getDeclaredTools();
    }
    /**
     * The context one tool call's execute receives (pi ExtensionToolContext): the
     * shared context plus executeTool/tools bound to this call. Only tools get it —
     * handlers keep the plain context, so a tool_call handler cannot recurse.
     */
    toolContext(ctx, toolCallId, signal) {
        const toolCtx = Object.create(ctx);
        Object.defineProperty(toolCtx, "tools", { get: () => this.toolState.getCallableTools(), enumerable: true });
        Object.defineProperty(toolCtx, "executeTool", {
            value: (name, args, options = {}) => this.nestedCalls.execute(toolCallId, name, args, {
                signal: options.signal ?? signal,
                onUpdate: options.onUpdate,
            }),
            enumerable: true,
        });
        return toolCtx;
    }
    /** The model-visible conversation: the session log's projection (read-only; P22). */
    get messages() {
        return this.projection;
    }
    /** Run a log write after every earlier one; its failure goes to the caller. */
    enqueueWrite(write) {
        const run = this.writes.then(write);
        this.writes = run.then(() => undefined, () => undefined);
        return run;
    }
    /** Enqueue a write nobody awaits (sync extension actions); a failure surfaces at the next flushWrites(). */
    enqueueBackground(write) {
        this.enqueueWrite(write).catch((error) => {
            this.writeFailure ??= toError(error);
        });
    }
    /** Wait for every queued write; rethrow a background write's failure. */
    async flushWrites() {
        await this.writes;
        const failure = this.writeFailure;
        if (failure) {
            this.writeFailure = undefined;
            throw failure;
        }
    }
    /** Rebuild `messages` from the log's active path (pi _refreshFinalizedContext). */
    async refresh() {
        const entries = await this.session.store.getEntries();
        if (this.appendInPlace(entries)) {
            this.sessionMetadata = await this.session.store.getMetadata();
            return;
        }
        const path = await this.session.store.getPathToRoot(this.session.currentLeafId);
        const projection = buildSessionProjection(path);
        const entryIds = new WeakMap();
        for (const entry of projection.entries) {
            for (const message of entry.messages)
                entryIds.set(message, entry.sourceEntry.id);
        }
        this.entryIds = entryIds;
        this.activePath = path;
        this.activeProjection = projection;
        this.projection = Object.freeze(projection.messages);
        this.allEntries = entries;
        this.entriesById = new Map(entries.map((entry) => [entry.id, entry]));
        this.sessionUsage = sumSessionUsage(entries);
        this.sessionMetadata = await this.session.store.getMetadata();
    }
    /**
     * P42b (tech debt #17): when the log only grew by entries that continue the active path
     * (the usual write), extend the path, projection, entry index and usage in place instead
     * of rebuilding them. A compaction or context edit (they change earlier context), a move
     * (the new entries do not hang under the old leaf) or anything else falls back to the full
     * rebuild. Returns whether it applied.
     */
    appendInPlace(entries) {
        const previous = this.allEntries;
        const path = this.activePath;
        const projection = this.activeProjection;
        if (!path || !projection || previous.length === 0 || entries.length <= previous.length)
            return false;
        // The same log, grown at its end (a session switch replaces the store, and its entries).
        if (entries[previous.length - 1] !== previous[previous.length - 1])
            return false;
        const added = entries.slice(previous.length);
        let parentId = path.length > 0 ? path[path.length - 1].id : null;
        for (const entry of added) {
            if (entry.type === "compaction" || entry.type === "context_edit" || entry.parentId !== parentId)
                return false;
            parentId = entry.id;
        }
        if (parentId !== this.session.currentLeafId)
            return false;
        const projected = added.map((sourceEntry) => ({
            sourceEntry,
            messages: sessionEntryToContextMessages(sourceEntry),
        }));
        const messages = projected.flatMap((entry) => entry.messages);
        for (const entry of projected) {
            for (const message of entry.messages)
                this.entryIds.set(message, entry.sourceEntry.id);
        }
        this.activePath = [...path, ...added];
        this.activeProjection = {
            entries: [...projection.entries, ...projected],
            messages: [...projection.messages, ...messages],
        };
        this.projection = Object.freeze(this.activeProjection.messages);
        this.allEntries = entries;
        for (const entry of added)
            this.entriesById.set(entry.id, entry);
        this.sessionUsage = combineUsage(this.sessionUsage, sumSessionUsage(added));
        return true;
    }
    /** Append a message to the log and refresh the projection; returns its entry id. Call inside the writer. */
    async appendAndRefresh(message) {
        const entry = await this.session.recordMessage(message);
        await this.refresh();
        return entry.id;
    }
    /** Log a message after every queued write; returns its entry id. */
    record(message) {
        return this.enqueueWrite(() => this.appendAndRefresh(message));
    }
    /**
     * Append a message the host produced outside the loop (e.g. the TUI's `!`
     * user bash) to the log. Replaces pushing onto `messages`, which is now a
     * read-only projection.
     */
    async appendMessage(message) {
        if (this.looping)
            throw new TauError("busy", "appendMessage() while the agent loop is running");
        await this.flushWrites();
        await this.record(normalizeMessageContent(message));
    }
    /**
     * Log a system message when the prompt or the tool set differs from what the
     * transcript replays to (pi _preparePromptAndToolLoadout + declareToolChanges).
     * The kernel has one section, `prompt`, holding the effective prompt text, so
     * the folded wire prompt equals the plain prompt byte for byte.
     */
    async declareLoadout(promptText) {
        await this.flushWrites();
        const current = getCurrentSystemMessage(this.messages);
        const sections = diffSystemPromptSections(current?.sections ?? {}, promptText === "" ? {} : { prompt: promptText });
        const loadout = this.toolState.computeLoadout();
        this.hiddenDeclarations = loadout.hidden;
        const tools = getToolStateChanges(getCurrentTools(this.messages), 
        // A schemaless tool that slipped past registration cannot be declared; the
        // preflight guard still answers a call to it with a paired error result (HF-8).
        loadout.declared.filter(hasToolSchema).map(toToolDeclaration));
        if (!sections && tools.toolsAdded.length === 0 && tools.toolsRemoved.length === 0)
            return;
        const update = {
            role: "system",
            content: "",
            ...(sections ? { sections } : {}),
            ...(tools.toolsAdded.length > 0 ? { toolsAdded: tools.toolsAdded } : {}),
            ...(tools.toolsRemoved.length > 0 ? { toolsRemoved: tools.toolsRemoved } : {}),
            timestamp: Date.now(),
        };
        await this.record(update);
    }
    /**
     * The verifyTranscript check: the projection rebuilt from the logged path equals
     * `messages`. Logged entries are deep-frozen (P22), so the messages they hold are the very
     * objects the projection uses: those compare by identity; a message a projection derives
     * anew (a summary, an edited one) compares by its JSON, worked out once per object (P42b:
     * this used to serialize the whole log on every request).
     */
    async assertTranscriptMatchesLog() {
        const path = await this.session.store.getPathToRoot(this.session.currentLeafId);
        const expected = buildSessionProjection(path).messages;
        const actual = this.messages;
        const same = expected.length === actual.length &&
            expected.every((message, index) => message === actual[index] || this.messageJson(message) === this.messageJson(actual[index]));
        if (!same) {
            throw new TauError("invalid_state", "Model context diverged from the session log: the request would contain something the log does not (kernel bug)");
        }
    }
    messageJsonCache = new WeakMap();
    /** A message's JSON, once per message object (they do not change once made). */
    messageJson(message) {
        let json = this.messageJsonCache.get(message);
        if (json === undefined) {
            json = JSON.stringify(message);
            this.messageJsonCache.set(message, json);
        }
        return json;
    }
    /** The read-only session view handed to extensions as ctx.sessionManager (pi ReadonlySessionManager). */
    sessionManager() {
        if (this.readonlySessionManager)
            return this.readonlySessionManager;
        const branch = (fromId) => {
            const path = [];
            let id = fromId;
            while (id !== null) {
                const entry = this.entriesById.get(id);
                if (!entry)
                    break;
                path.push(entry);
                id = entry.parentId;
            }
            return path.reverse();
        };
        this.readonlySessionManager = {
            getCwd: () => this.sessionMetadata?.cwd ?? "",
            getSessionId: () => this.sessionMetadata?.id ?? "",
            getSessionFile: () => this.sessionMetadata?.filePath,
            getSessionName: () => this.sessionMetadata?.name,
            getLeafId: () => this.session.currentLeafId,
            getLeafEntry: () => {
                const leafId = this.session.currentLeafId;
                return leafId === null ? undefined : this.entriesById.get(leafId);
            },
            getEntry: (id) => this.entriesById.get(id),
            getLabel: (id) => {
                let label;
                for (const entry of this.allEntries)
                    if (entry.type === "label" && entry.targetId === id)
                        label = entry.label;
                return label;
            },
            getBranch: (fromId) => branch(fromId ?? this.session.currentLeafId),
            getEntries: () => [...this.allEntries],
            buildContextEntries: () => buildContextEntries(this.activePath),
            buildSessionProjection: () => this.activeProjection,
        };
        return this.readonlySessionManager;
    }
    /**
     * Wait until every queued log write has landed in `messages`. Extension
     * actions such as an idle `sendMessage` append asynchronously (the store is
     * async); a host that renders new messages after running an extension command
     * awaits this first. Rethrows a failed background write.
     */
    async flush() {
        await this.flushWrites();
    }
    /** Session entry id a projected message came from (undefined for messages not in `messages`). */
    entryIdOf(message) {
        return this.entryIds.get(message);
    }
    /** Fire message_start/message_end for a non-streamed message, then log it. */
    async commitMessage(message, ctx) {
        return (await this.commitMessageWithId(message, ctx)).message;
    }
    /** commitMessage, also returning the session entry id. */
    async commitMessageWithId(message, ctx) {
        let final = message;
        if (this.extensions) {
            await this.extensions.notifyMessageStart(final, ctx);
            final = await this.extensions.runMessageEnd(final, ctx);
        }
        const entryId = await this.record(final);
        return { message: final, entryId };
    }
    /**
     * ctx.ui for extensions (P40d, pi): a prompt that waits for the user is bracketed by
     * ui_prompt_start and ui_prompt_end. Approvals do not go through it (pi: extension prompts only).
     */
    promptingUi(ui) {
        const extensions = this.extensions;
        if (!ui || !extensions?.hasUiPromptHandlers())
            return ui;
        const around = async (kind, title, run) => {
            const ctx = this.extensionContext();
            await extensions.notifyUiPrompt({ type: "ui_prompt_start", reason: "ui_prompt", kind, title }, ctx);
            try {
                return await run();
            }
            finally {
                await extensions.notifyUiPrompt({ type: "ui_prompt_end", reason: "ui_prompt", kind, title }, ctx);
            }
        };
        const { editor, selectMany } = ui;
        return {
            confirm: (title, message) => around("confirm", title, () => ui.confirm(title, message)),
            input: (title, placeholder) => around("input", title, () => ui.input(title, placeholder)),
            select: (title, options, dialog) => around("select", title, () => (dialog ? ui.select(title, options, dialog) : ui.select(title, options))),
            notify: (message, level) => ui.notify(message, level),
            ...(editor ? { editor: (title, prefill) => around("editor", title, () => editor.call(ui, title, prefill)) } : {}),
            ...(selectMany
                ? { selectMany: (title, options) => around("select", title, () => selectMany.call(ui, title, options)) }
                : {}),
        };
    }
    /** Context handed to extension handlers; also usable by hosts to dispatch extension commands. */
    extensionContext() {
        const agent = this;
        return {
            ui: this.promptingUi(this.ui),
            get messages() {
                return agent.messages;
            },
            capabilities: this.capabilities,
            getContextUsage: () => this.getContextUsage(),
            compact: (customInstructions) => {
                this.extensions?.assertActive();
                this.pendingCompaction = customInstructions ?? "";
            },
            runSubagent: (prompt, options, signal) => this.runSubagent(prompt, options, signal),
            abort: () => {
                this.extensions?.assertActive();
                const hostAbort = this.extensions?.hostAction("abort");
                if (hostAbort)
                    hostAbort();
                else
                    this.abort("Aborted by extension");
            },
            isIdle: () => this.isIdle,
            get signal() {
                return agent.signal;
            },
            hasPendingMessages: () => this.hasQueuedMessages(),
            shutdown: () => {
                this.extensions?.assertActive();
                this.extensions?.hostAction("shutdown")?.();
            },
            isProjectTrusted: () => this.isProjectTrusted?.() === true,
            cwd: this.capabilities.paths?.cwd ?? this.capabilities.fs?.cwd ?? "",
            get model() {
                return agent.currentModel;
            },
            get thinkingLevel() {
                return agent.currentThinkingLevel;
            },
            sessionManager: this.sessionManager(),
            getSystemPrompt: () => getCurrentSystemPrompt(this.messages),
            discoverResources: (reason) => {
                const cwd = this.capabilities.paths?.cwd ?? this.capabilities.fs?.cwd ?? "";
                return (this.extensions?.runResourcesDiscover(cwd, reason, this.extensionContext()) ??
                    Promise.resolve({ skillPaths: [], promptPaths: [], themePaths: [] }));
            },
        };
    }
    setUi(ui) {
        this.ui = ui;
    }
    runSubagent(prompt, options = {}, signal) {
        return runSubagent(this.internals, prompt, options, signal);
    }
    /** A usage with its cost filled from the host pricing, when there is one (D19-3). */
    priced(usage) {
        const pricing = this.pricingFor(this.currentModel);
        return pricing ? { ...usage, cost: computeUsageCost(usage, pricing) } : usage;
    }
    /** Prices for a model: the fixed pricing, or what the host's resolver says for it. */
    pricingFor(model) {
        return typeof this.pricing === "function" ? this.pricing(model) : this.pricing;
    }
    /** The extension command a sent message names, with its arguments (P43, as hosts parse typed input). */
    commandOf(content) {
        const text = typeof content === "string"
            ? content
            : content.flatMap((part) => (part.type === "text" ? [part.text] : [])).join("\n");
        if (!text.startsWith("/") || !this.extensions)
            return undefined;
        const space = text.search(/\s/);
        const name = (space === -1 ? text : text.slice(0, space)).slice(1);
        const command = this.extensions.commands.get(name);
        return command ? { command, args: space === -1 ? "" : text.slice(space + 1).trim() } : undefined;
    }
    /** Run a command named by sendUserMessage (P43); the prompt it returns goes out with the message's images. */
    async runSentCommand(found, content, deliverAs) {
        try {
            const output = await found.command.handler(found.args, this.extensionContext());
            if (typeof output === "string")
                this.ui?.notify(output, "info");
            else if (output?.action === "prompt") {
                const images = [
                    ...(output.images ?? []),
                    ...(typeof content === "string" ? [] : content.filter((part) => part.type === "image")),
                ];
                const prompt = commandPromptContent({ text: output.text, images });
                this.extensions?.hostAction("sendUserMessage")?.(prompt, deliverAs ? { deliverAs } : undefined);
            }
        }
        catch (error) {
            this.extensions?.reportError("send_user_message", toError(error).message);
        }
    }
    /** The model this agent's requests use. */
    get model() {
        return this.currentModel;
    }
    /** The provider of that model, as assistant messages record it (P43: the shell tools' TAU_SESSION_PROVIDER). */
    get provider() {
        return this.config.provider ?? "openai-compat";
    }
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
    setModel(model, options = {}) {
        const switchesProvider = options.transport !== undefined ||
            (options.provider !== undefined && options.provider !== this.config.provider) ||
            (options.api !== undefined && options.api !== this.config.api) ||
            ("contextWindow" in options && options.contextWindow !== this.config.contextWindow) ||
            ("maxOutputTokens" in options && options.maxOutputTokens !== this.config.maxOutputTokens);
        if (model === this.currentModel && !switchesProvider)
            return;
        if (switchesProvider) {
            const next = { ...this.config, model };
            if (options.provider !== undefined)
                next.provider = options.provider;
            if (options.api !== undefined)
                next.api = options.api;
            if ("contextWindow" in options) {
                if (options.contextWindow === undefined)
                    delete next.contextWindow;
                else
                    next.contextWindow = options.contextWindow;
            }
            if ("maxOutputTokens" in options) {
                if (options.maxOutputTokens === undefined)
                    delete next.maxOutputTokens;
                else
                    next.maxOutputTokens = options.maxOutputTokens;
            }
            this.config = next;
            if (options.transport)
                this.transport = options.transport;
        }
        this.currentModel = model;
        const provider = this.config.provider ?? "openai-compat";
        this.enqueueBackground(async () => {
            await this.session.append({ type: "model_change", provider, modelId: model });
            await this.refresh();
        });
    }
    /** The thinking level sent with requests; undefined = the endpoint's default. */
    get thinkingLevel() {
        return this.currentThinkingLevel;
    }
    /** Change the thinking level from the next request on; logs a thinking_level_change entry (pi words: "none" → "off"). */
    setThinkingLevel(level) {
        if (level === this.currentThinkingLevel)
            return;
        this.currentThinkingLevel = level;
        if (level === undefined)
            return;
        this.enqueueBackground(async () => {
            await this.session.append({ type: "thinking_level_change", thinkingLevel: level === "none" ? "off" : level });
            await this.refresh();
        });
    }
    /** The permission mode tool calls are checked against (D20). */
    getPermissionMode() {
        return this.permissionMode;
    }
    /**
     * Change the permission mode from the next tool call on; subagents spawned
     * afterwards inherit it (tau, P25: the TUI's Shift+Tab). Not recorded in the
     * session: a resumed session takes its mode from the host again, so a
     * session never comes back more permissive than the host started it.
     */
    setPermissionMode(mode) {
        this.permissionMode = mode;
    }
    /** The extension-facing model switch: model_select before, the switch, model_select after (pi setModel). */
    async switchModelForExtension(requested) {
        const ctx = this.extensionContext();
        const previous = this.currentModel;
        const decision = this.extensions
            ? await this.extensions.runModelSelectBefore({ currentModel: previous, requestedModel: requested }, ctx)
            : { model: requested };
        if ("cancel" in decision && decision.cancel)
            return false;
        this.setModel(decision.model);
        await this.extensions?.notifyModelSelected({ previousModel: previous, requestedModel: requested, selectedModel: decision.model }, ctx);
        return true;
    }
    /**
     * Header changes from before_provider_headers for one request (P23d). A
     * transport that does not apply headers cannot honor them, so registering a
     * handler with it is an error, not a silent no-op (D15 supplement).
     */
    async providerHeaders(ctx) {
        if (!this.extensions?.hasProviderHeaderHandlers())
            return undefined;
        if (!this.transport.supportsHeaders) {
            throw new TauError("unsupported", "before_provider_headers handlers are registered, but the transport does not declare supportsHeaders");
        }
        const headers = await this.extensions.runBeforeProviderHeaders(ctx);
        return Object.keys(headers).length > 0 ? headers : undefined;
    }
    /**
     * The provider event callbacks for one request (P40d, pi before_provider_request,
     * after_provider_response, provider_stream_event). A transport that does not report them
     * cannot honor them, so registering a handler with it is an error (D15 supplement).
     */
    providerEventHooks(ctx, model) {
        const extensions = this.extensions;
        if (!extensions?.hasProviderEventHandlers())
            return undefined;
        if (!this.transport.supportsProviderEvents) {
            throw new TauError("unsupported", "before_provider_request / after_provider_response / provider_stream_event handlers are registered, but the transport does not declare supportsProviderEvents");
        }
        const wanted = extensions.providerEventHandlers();
        const provider = this.config.provider ?? "openai-compat";
        const api = this.config.api ?? "openai-completions";
        return {
            ...(wanted.request ? { onPayload: (payload) => extensions.runBeforeProviderRequest(payload, ctx) } : {}),
            ...(wanted.response
                ? {
                    onResponse: (response) => extensions.notifyAfterProviderResponse(response, ctx),
                }
                : {}),
            ...(wanted.stream
                ? {
                    onStreamEvent: (data) => extensions.notifyProviderStreamEvent({ provider, api, model, data }, ctx),
                }
                : {}),
        };
    }
    /**
     * The transport for summary requests (compaction, turn prefix, branch summary):
     * the agent's model and thinking level, no prompt-cache write (pi 0.82.0), and
     * the before_provider_headers changes — pi runs the hook for every provider call.
     */
    summaryTransport() {
        // P53c: a separate summary model has its own transport; the thinking level is the agent model's.
        const separate = this.summaryModel;
        const inner = separate?.transport ?? this.transport;
        const agent = this;
        const transport = (request) => (async function* () {
            const ctx = agent.extensionContext();
            const model = separate?.model ?? agent.currentModel;
            const headers = await agent.providerHeaders(ctx);
            const hooks = agent.providerEventHooks(ctx, model);
            yield* inner({
                ...request,
                model,
                cacheRetention: "none",
                ...(!separate && agent.currentThinkingLevel !== undefined
                    ? { thinkingLevel: agent.currentThinkingLevel }
                    : {}),
                ...(headers ? { headers } : {}),
                ...hooks,
            });
        })();
        transport.supportsHeaders = inner.supportsHeaders;
        transport.supportsProviderEvents = inner.supportsProviderEvents;
        return transport;
    }
    /** The output cap for summary requests: the summary model's when there is one (P53c), else the agent model's. */
    summaryMaxTokens() {
        return this.summaryModel ? this.summaryModel.maxOutputTokens : this.config.maxOutputTokens;
    }
    /**
     * Current context-token estimate (pi semantics: last assistant usage + trailing heuristic).
     * With a window, `reserveTokens` is what compaction keeps free (P49: read's allow_large
     * reads what is left between them).
     */
    getContextUsage() {
        return {
            ...estimateProjectedContextTokens(this.activeProjection, this.activePath),
            contextWindow: this.config.contextWindow,
            ...(this.config.contextWindow !== undefined
                ? { reserveTokens: this.effectiveCompactionSettings().reserveTokens }
                : {}),
        };
    }
    /**
     * Everything this session was billed for, all branches, compaction-proof
     * (D19): assistant turns, summaries and tool-reported usage. Hosts show it as
     * the session cost; the context estimate is getContextUsage().
     */
    getSessionUsage() {
        return this.sessionUsage;
    }
    /** Compaction settings for the configured window (small windows scale the budgets down). */
    effectiveCompactionSettings() {
        return compactionSettingsForWindow(this.compactionSettings, this.config.contextWindow);
    }
    /** Claim the agent for one operation; a second one throws busy (D14: a programming error). */
    beginOperation(operation, signal) {
        if (this.operation !== undefined) {
            throw new TauError("busy", `${operation}() while ${this.operation} holds the agent`);
        }
        this.operation = operation;
        const handle = new AbortHandle();
        handle.follow(signal);
        this.activeAbort = handle;
        return handle;
    }
    /** Release the agent; extension messages held during a compaction or navigation land now. */
    endOperation() {
        this.operation = undefined;
        this.activeAbort = undefined;
        const held = this.heldMessages.splice(0);
        for (const { message } of held)
            this.enqueueBackground(() => this.appendAndRefresh(message));
        if (held.some((entry) => entry.triggerTurn))
            this.extensions?.hostAction("resumeTurn")?.();
    }
    /** Summarization retry for this agent: its RetrySettings, the host sleep, events through emit. */
    summarizationRetry(source, emit) {
        return {
            settings: this.retrySettings,
            sleep: this.platform.sleep,
            callbacks: emit && {
                onRetryScheduled: (attempt, maxAttempts, delayMs, errorMessage) => emit({ type: "summarization_retry_scheduled", attempt, maxAttempts, delayMs, errorMessage }),
                onRetryAttemptStart: () => emit({ type: "summarization_retry_attempt_start", ...source }),
                onRetryFinished: () => emit({ type: "summarization_retry_finished" }),
            },
        };
    }
    /**
     * One request with the current model and transport, outside the conversation: nothing
     * is recorded or added to the context, and the agent is not held (P34f: session titles).
     * Throws on a failed or truncated reply.
     */
    async completeText(systemPrompt, prompt, options = {}) {
        const result = await completeText(this.transport, systemPrompt, prompt, options.maxTokens ?? 256, options.signal, {
            label: "Request",
        });
        return result.text;
    }
    /** Name the session (pi setSessionName), in turn with the run's writes (P58: a host's recorder beside them forked the log). */
    async setSessionName(name) {
        await this.enqueueWrite(() => this.session.setName(name).then(() => this.refresh()));
    }
    /**
     * Label an entry of the session tree, or clear its label with an empty one (P39f, the
     * host's /tree; pi's setLabel through appendLabelChange). Throws for an unknown entry.
     */
    async setLabel(entryId, label) {
        await this.flushWrites();
        if (!this.entriesById.has(entryId))
            throw new SessionError("not_found", `Entry ${entryId} not found`);
        const text = label?.trim();
        await this.enqueueWrite(async () => {
            await this.session.appendLabel(entryId, text ? text : undefined);
            await this.refresh();
        });
    }
    /**
     * A side question (P39c, Claude Code's /btw): the conversation as the next request would
     * send it (same prefix, so a warm prompt cache serves it), the newest 20 earlier side
     * exchanges, then the question. Nothing joins the conversation and no tool runs; the
     * request still declares the tools (a changed prefix would miss the cache), so tool
     * calls the model makes anyway are only counted. Works while a run is in flight: it sees
     * the committed messages, not the reply being written. Its usage is logged as a `usage`
     * entry so session totals stay honest.
     */
    askAside(question, options = {}) {
        return askAside(this.internals, question, options);
    }
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
    async compact(customInstructions, reason = "manual", signal, onEvent) {
        const handle = this.beginOperation("compact", signal);
        try {
            const outcome = await runCompactionOperation(this.internals, customInstructions, reason, handle.signal, onEvent);
            if (outcome.kind === "none")
                return undefined;
            if (outcome.kind === "done")
                return outcome.result;
            if (outcome.cancelledByExtension)
                throw new TauError("cancelled", "Compaction cancelled");
            if (outcome.aborted)
                throw new TauError("aborted", "Compaction aborted");
            throw new TauError("compaction_failed", outcome.error.message, outcome.error);
        }
        finally {
            this.endOperation();
        }
    }
    /**
     * Queue input for the running prompt: committed after the current turn, before
     * the next request. Input handlers see it first (pi 0.86.0, streamingBehavior
     * "steer") and may transform or consume it; the result says which (pi 0.99.0).
     */
    steer(content) {
        return this.queueUserInput(content, "steer", "interactive");
    }
    /** Queue input consumed when the prompt would otherwise end; the loop then continues. Same input rules as steer(). */
    followUp(content) {
        return this.queueUserInput(content, "followUp", "interactive");
    }
    /** Run input handlers on queued input, then enqueue what survives (pi _queueUserInput). */
    async queueUserInput(content, behavior, source) {
        const queue = behavior === "steer" ? this.steeringQueue : this.followUpQueue;
        if (!this.extensions) {
            queue.enqueue({ role: "user", content, timestamp: Date.now() });
            return "queued";
        }
        const text = userContentText(content);
        const images = typeof content === "string" ? undefined : content.filter((block) => block.type === "image");
        const result = await this.extensions.runInput(text, this.extensionContext(), {
            source,
            streamingBehavior: behavior,
            ...(images && images.length > 0 ? { images } : {}),
        });
        if (result.handled)
            return "handled";
        queue.enqueue({ role: "user", content: rewrittenInput(content, text, result), timestamp: Date.now() });
        return "queued";
    }
    hasQueuedMessages() {
        return this.steeringQueue.hasItems() || this.followUpQueue.hasItems();
    }
    /**
     * Empty both queues and return what they held (pi AgentSession.clearQueue).
     * Queues survive an abort by design; hosts call this when a run ends to restore
     * the user's queued text to the editor instead of replaying it later.
     */
    clearQueue() {
        return { steering: this.steeringQueue.clear(), followUp: this.followUpQueue.clear() };
    }
    /** True when no operation holds the agent (pi isIdle). */
    get isIdle() {
        return this.operation === undefined;
    }
    /** Abort signal of the operation in flight; undefined when idle (pi ctx.signal). */
    get signal() {
        return this.activeAbort?.signal;
    }
    /**
     * Abort whatever holds the agent — a run with its retry backoff, a compaction,
     * a branch summary (pi AgentSession.abort). No-op when idle.
     */
    abort(reason = "Aborted") {
        this.activeAbort?.abort(new TauError("aborted", reason));
    }
    clearSteeringQueue() {
        this.steeringQueue.clear();
    }
    clearFollowUpQueue() {
        this.followUpQueue.clear();
    }
    async *prompt(input, signal) {
        const handle = this.beginOperation("prompt", signal);
        this.runEntered = false;
        let finished = false;
        try {
            await this.flushWrites();
            yield* this.runPrompt(input, handle.signal);
            finished = true;
        }
        finally {
            this.endOperation();
            // A run that threw (max_turns, a hook bug) or whose consumer stopped early still settles.
            if (!finished && this.runEntered)
                await this.notifySettled(handle.signal.aborted);
        }
        if (this.runEntered)
            yield* this.settle(handle.signal.aborted);
    }
    /** agent_settled once the agent is idle; runs started from its handlers go to the host afterwards. */
    async *settle(aborted) {
        await this.notifySettled(aborted);
        yield { type: "agent_settled", aborted };
        for (const start of this.deferredStarts.splice(0))
            start();
    }
    async notifySettled(aborted) {
        this.runEntered = false;
        this.settling = true;
        try {
            await this.extensions?.notifyAgentSettled(aborted, this.extensionContext());
        }
        finally {
            this.settling = false;
        }
    }
    /**
     * Run the agent loop without committing new user input — the continuation
     * behind sendMessage({triggerTurn:true}) while idle (pi's _runAgentPrompt
     * for app messages). The conversation must already end in something the
     * model should answer (a user/custom message).
     */
    async *resume(signal) {
        const handle = this.beginOperation("resume", signal);
        this.runEntered = false;
        let finished = false;
        try {
            await this.flushWrites();
            const ctx = this.extensionContext();
            const systemPrompt = await this.applyBeforeAgentStart(lastPromptText(this.messages), ctx);
            await this.extensions?.notifyAgentStart(ctx);
            yield* this.runLoop(ctx, systemPrompt, handle.signal);
            finished = true;
        }
        finally {
            this.endOperation();
            if (!finished && this.runEntered)
                await this.notifySettled(handle.signal.aborted);
        }
        if (this.runEntered)
            yield* this.settle(handle.signal.aborted);
    }
    /**
     * Navigate the session tree to another entry (pi's navigateTree): summarize
     * the branch being abandoned (unless summarize: false or an extension
     * supplies/cancels via session_before_tree), move the leaf, record a
     * branch_summary entry at the new position, and rebuild the conversation
     * from the new path. A user or custom message as the target moves the leaf to
     * its parent and returns its text as `editorText`, to edit and send again (pi;
     * P38c — before, tau moved to the message itself).
     */
    async navigateTo(entryId, options) {
        const handle = this.beginOperation("navigate", options?.signal);
        try {
            return await runNavigation(this.internals, entryId, handle.signal, options);
        }
        finally {
            this.endOperation();
        }
    }
    async *runPrompt(input, signal) {
        const ctx = this.extensionContext();
        // Input hooks (pi semantics) operate on text. Image blocks ride along untouched:
        // a rewrite replaces the text blocks with the rewritten text and keeps the images.
        let finalInput = input;
        if (this.extensions) {
            const inputText = userContentText(input);
            const images = typeof input === "string" ? undefined : input.filter((block) => block.type === "image");
            const inputResult = await this.extensions.runInput(inputText, ctx, {
                source: "interactive",
                ...(images && images.length > 0 ? { images } : {}),
            });
            if (inputResult.handled) {
                yield { type: "agent_end", messages: this.messages };
                return;
            }
            finalInput = rewrittenInput(input, inputText, inputResult);
        }
        const systemPrompt = await this.applyBeforeAgentStart(userContentText(finalInput), ctx);
        await this.commitMessage({ role: "user", content: finalInput, timestamp: Date.now() }, ctx);
        await this.extensions?.notifyAgentStart(ctx);
        yield* this.runLoop(ctx, systemPrompt, signal);
    }
    /**
     * before_agent_start for a run: extensions rewrite the system prompt and may
     * inject custom messages. Shared by prompt() and resume() — resume() used the
     * base prompt, dropping e.g. injected skills and the provider's prompt cache (HF-10).
     */
    async applyBeforeAgentStart(promptText, ctx) {
        const base = this.systemPrompt ?? "";
        // The run records the loadout in the transcript; restored tools that did not
        // register by now are dropped, so a tool that never registers does not stay pending (pi).
        if (!this.extensions) {
            this.toolState.pendingTools.clear();
            await this.declareLoadout(base);
            return base;
        }
        const beforeStart = await this.extensions.runBeforeAgentStart(promptText, base, ctx);
        this.toolState.pendingTools.clear();
        // The rewritten prompt is logged like any other (D27, deviation from pi's
        // unrecorded forced prompt): the model must only ever see what the log holds.
        await this.declareLoadout(beforeStart.systemPrompt);
        for (const injected of beforeStart.messages) {
            await this.commitMessage({
                role: "custom",
                customType: injected.customType,
                content: injected.content,
                display: injected.display ?? true,
                details: injected.details,
                timestamp: Date.now(),
            }, ctx);
        }
        return beforeStart.systemPrompt;
    }
    /**
     * A prompt's runs (pi _runAgentPrompt). The turn loop ends with agent_end;
     * messages queued meanwhile — e.g. by agent_end handlers — start a
     * continuation run in the same generator (pi _handlePostAgentRun, no timers);
     * then agent_before_settle may append drafts and ask for one more run. The
     * turn budget spans all runs of the prompt.
     */
    async *runLoop(ctx, systemPrompt, signal) {
        // Custom messages held for "the next turn" (sendMessage deliverAs:"nextTurn")
        // enter the conversation before this run's first LLM call, as in pi.
        for (const held of this.pendingNextTurn.splice(0)) {
            await this.commitMessage(held, ctx);
        }
        this.looping = true;
        this.runEntered = true;
        const state = { turnIndex: 0 };
        try {
            for (let run = 0;; run++) {
                if (run > 0)
                    await this.extensions?.notifyAgentStart(ctx);
                const outcome = yield* this.runTurns(ctx, systemPrompt, signal, state);
                if (signal?.aborted || outcome === "aborted")
                    break;
                if (this.hasQueuedMessages())
                    continue;
                const settle = this.extensions
                    ? await this.extensions.runBeforeSettle(outcome, ctx)
                    : { entries: [], continue: false };
                await this.commitBoundaryDrafts(settle.entries, "agent_before_settle", ctx);
                for (const message of this.boundaryMessages.splice(0))
                    yield { type: "user_message", message };
                if (signal?.aborted)
                    break;
                if (!settle.continue && !this.hasQueuedMessages())
                    break;
                if (!this.canContinue()) {
                    if (settle.continue) {
                        this.extensions?.reportError("agent_before_settle", "agent_before_settle requested continuation without runnable model context");
                    }
                    break;
                }
            }
        }
        finally {
            this.looping = false;
            // Custom messages held for after a turn (triggerTurn:false) still land (pi flushes them).
            for (const held of this.afterTurnMessages.splice(0))
                await this.commitMessage(held, ctx);
        }
    }
    /** Whether a request now has something to answer: the context does not end with the assistant, or messages are queued. */
    canContinue() {
        let last;
        for (let i = this.messages.length - 1; i >= 0; i--) {
            if (this.messages[i].role !== "system") {
                last = this.messages[i];
                break;
            }
        }
        return (last !== undefined && last.role !== "assistant") || this.hasQueuedMessages();
    }
    /** Custom messages boundary handlers appended, for the run loop to announce as user_message events. */
    boundaryMessages = [];
    /** Append a boundary handler's drafts in order (custom entries and custom messages). */
    async commitBoundaryDrafts(drafts, event, ctx) {
        for (const draft of drafts) {
            if (draft.type === "custom") {
                await this.enqueueWrite(async () => {
                    await this.session.appendCustom(draft.customType, draft.data);
                    await this.refresh();
                });
            }
            else if (draft.type === "custom_message") {
                const committed = await this.commitMessage({
                    role: "custom",
                    customType: draft.customType,
                    content: draft.content,
                    display: draft.display ?? true,
                    details: draft.details,
                    timestamp: Date.now(),
                }, ctx);
                // P45: hosts see them as they land (the TUI draws LSP diagnostics under the turn).
                this.boundaryMessages.push(committed);
            }
            else {
                this.extensions?.reportError(event, `Unsupported boundary draft type: ${draft.type}`);
            }
        }
    }
    /**
     * One run's turns, in pi runLoop order: steering is polled at the start;
     * each turn prepares (compaction) → polls steering again if none was pending →
     * turn_start → commits pending messages → requests → runs the tool batch →
     * finishTurn → turn_end; steering is polled after turn_end, follow-ups when
     * the run would stop. Returns how the run ended; agent_end has fired.
     */
    async *runTurns(ctx, systemPrompt, signal, state) {
        const recovery = { retryAttempt: 0, overflowRecoveryAttempted: false };
        let lengthStreak = 0;
        /** P53c: replies cut off by the output cap continued in a row. */
        let continuedReplies = 0;
        // P54c: one fallback chain per run, starting from the run's model; the route that answers keeps the run.
        const primary = {
            transport: this.transport,
            model: this.currentModel,
            label: `${this.provider}/${this.currentModel}`,
        };
        const fallback = this.fallback ? createFallbackChain(primary, this.fallback) : undefined;
        const transport = fallback?.transport ?? this.transport;
        let explicitContinue = false;
        // Check for steering at start: the user may have typed while the run was starting (pi).
        let pending = this.steeringQueue.drain();
        while (true) {
            let hasMoreToolCalls = true;
            while (hasMoreToolCalls || pending.length > 0) {
                // Abort observed between turns (e.g. signalled during tool execution) ends
                // the run before the next LLM call instead of waiting to fail inside it.
                if (signal?.aborted) {
                    yield* this.endRun(ctx, recovery.retryAttempt, pending);
                    return "aborted";
                }
                if (state.turnIndex >= this.maxTurnsPerPrompt) {
                    throw new TauError("max_turns", `Prompt exceeded ${this.maxTurnsPerPrompt} turns without completing`);
                }
                // Prepare the turn: compaction, requested first, then threshold — before
                // turn_start, as pi's prepareNextTurn (the first turn keeps the slot). A
                // failure is reported, not thrown (D14): the turn goes on uncompacted.
                let compactionReason;
                let customInstructions;
                if (this.pendingCompaction !== null) {
                    compactionReason = "manual";
                    customInstructions = this.pendingCompaction === "" ? undefined : this.pendingCompaction;
                    this.pendingCompaction = null;
                }
                else if (this.config.contextWindow !== undefined &&
                    shouldCompact(this.getContextUsage().tokens, this.config.contextWindow, this.effectiveCompactionSettings())) {
                    compactionReason = "threshold";
                }
                if (compactionReason !== undefined) {
                    const reason = compactionReason;
                    const outcome = yield* compactWithEvents(this.internals, customInstructions, reason, signal);
                    if (outcome.kind === "done")
                        yield { type: "compaction", result: outcome.result, reason };
                    if (outcome.kind === "failed") {
                        yield {
                            type: "compaction_failed",
                            reason,
                            ...(outcome.errorMessage !== undefined ? { errorMessage: outcome.errorMessage } : {}),
                            aborted: outcome.aborted,
                        };
                    }
                    // Aborted while compacting: the run ends here (D14: agent_end, no throw).
                    if (signal?.aborted) {
                        yield* this.endRun(ctx, recovery.retryAttempt, pending);
                        return "aborted";
                    }
                }
                // Preparation can be long-running: pick up steering queued while it ran. Only
                // when nothing is pending, or one-at-a-time would deliver two messages (pi).
                if (pending.length === 0)
                    pending = this.steeringQueue.drain();
                const turnIndex = state.turnIndex;
                await this.extensions?.notifyTurnStart(turnIndex, ctx);
                for (const message of pending) {
                    const committed = (await this.commitMessage(message, ctx));
                    yield { type: "user_message", message: committed };
                }
                pending = [];
                // An abort that landed in turn_start handlers must not send the request (pi #9340's kin).
                if (signal?.aborted) {
                    yield* this.endRun(ctx, recovery.retryAttempt);
                    return "aborted";
                }
                // The request is the log's projection: declare prompt/tool changes as a system
                // message first, then check the projection against a JSON replay of the log.
                await this.declareLoadout(systemPrompt);
                // The tools this request declares are the ones its calls may use: a tool that
                // changes mid-batch (registered, deactivated) takes effect from the next request (pi).
                const toolSnapshot = this.toolState.activeTools();
                await this.flushWrites();
                if (this.verifyTranscript)
                    await this.assertTranscriptMatchesLog();
                let request = [...this.messages];
                if (this.extensions) {
                    request = await this.extensions.runContext(request, ctx);
                }
                request = withoutHiddenDeclarations(request, this.hiddenDeclarations);
                const partial = {
                    role: "assistant",
                    content: [],
                    api: this.config.api ?? OPENAI_COMPLETIONS_API,
                    provider: this.config.provider ?? "openai-compat",
                    model: this.currentModel,
                    usage: emptyUsage(),
                    // A streaming partial is unfinished (pi 0.83.0); the final message lands a concrete reason.
                    stopReason: "pending",
                    timestamp: Date.now(),
                };
                const requestStart = partial.timestamp;
                await this.extensions?.notifyMessageStart(partial, ctx);
                // Awaited here, outside the stream: a failing headers handler is a bug, not a stream error.
                const headers = await this.providerHeaders(ctx);
                const hooks = this.providerEventHooks(ctx, this.currentModel);
                const stream = transport({
                    messages: request,
                    signal,
                    model: this.currentModel,
                    ...(this.currentThinkingLevel !== undefined ? { thinkingLevel: this.currentThinkingLevel } : {}),
                    ...(headers ? { headers } : {}),
                    ...hooks,
                });
                const streamed = yield* pullResponse(this.internals, stream, partial, ctx, requestStart);
                const streamFailure = streamed.failure;
                let assistantMessage = streamed.message;
                if (streamFailure) {
                    // As in pi (agent-loop.ts streamAssistantResponse): a stream failure does not
                    // throw — it becomes an assistant message with stopReason error/aborted that
                    // enters the conversation and the session, then the run ends normally.
                    // The partial keeps its request-start timestamp (pi), whatever the failure time.
                    partial.stopReason = streamFailure.code === "aborted" ? "aborted" : "error";
                    partial.errorMessage = streamFailure.message;
                    partial.durationMs = Math.max(0, Date.now() - requestStart);
                    let finalMessage = partial;
                    if (this.extensions) {
                        finalMessage = (await this.extensions.runMessageEnd(finalMessage, ctx));
                    }
                    const failedEntryId = await this.record(finalMessage);
                    yield { type: "assistant_message", message: finalMessage };
                    // error/aborted are hard exits (D14): finishTurn's decision and a boundary
                    // continue are ignored; drafts still land.
                    await this.finishTurn?.({ message: finalMessage, toolResults: [] }, signal);
                    await this.runTurnEndBoundary(turnIndex, finalMessage, failedEntryId, [], [], ctx);
                    for (const message of this.boundaryMessages.splice(0))
                        yield { type: "user_message", message };
                    const decision = yield* recoverFromFailure(this.internals, {
                        failed: finalMessage,
                        failedEntryId,
                        failure: streamFailure,
                        recovery,
                        run: state,
                        signal,
                        ctx,
                        fallback,
                        endRun: (attempt) => this.endRun(ctx, attempt, pending),
                    });
                    if (decision !== "again")
                        return decision;
                    hasMoreToolCalls = true;
                    continue;
                }
                if (!assistantMessage) {
                    throw new TauError("stream_error", "Stream ended without a final message");
                }
                if (this.extensions) {
                    assistantMessage = (await this.extensions.runMessageEnd(assistantMessage, ctx));
                }
                const messageEntryId = await this.record(assistantMessage);
                yield { type: "assistant_message", message: assistantMessage };
                // A request that went through: the next overflow may be recovered again (pi).
                if (assistantMessage.stopReason !== "error" && assistantMessage.stopReason !== "length") {
                    recovery.overflowRecoveryAttempted = false;
                }
                if (recovery.retryAttempt > 0) {
                    // A clean response after retries closes the cycle (pi resets on success).
                    const endEvent = { success: true, attempt: recovery.retryAttempt };
                    await this.extensions?.notifyAutoRetryEnd(endEvent, ctx);
                    yield { type: "auto_retry_end", ...endEvent };
                    recovery.retryAttempt = 0;
                }
                const toolCalls = toolCallsOf(assistantMessage);
                const batch = { messages: [], entryIds: [], terminate: [] };
                const toolResults = batch.messages;
                const toolResultEntryIds = batch.entryIds;
                if (toolCalls.length > 0 && assistantMessage.stopReason === "length") {
                    // A length stop may have cut the arguments off: fail every call instead of
                    // running possibly borked ones (pi 0.80.4). tau routes them through the
                    // immediate-result path, like any call that never executes.
                    for (const toolCall of toolCalls) {
                        yield { type: "tool_start", toolCall };
                        yield await this.commitToolResult(toolCall, errorResult(truncatedToolCallText(toolCall.name)), ctx, batch);
                    }
                }
                else {
                    // pi dispatch semantics (agent-loop.ts): global sequential config or any
                    // sequential-marked tool in the batch downgrades the whole batch.
                    const forceSequential = this.toolExecution === "sequential" ||
                        toolCalls.some((tc) => toolSnapshot.get(tc.name)?.executionMode === "sequential");
                    if (forceSequential || toolCalls.length <= 1) {
                        yield* this.toolRunner.runSequential(toolCalls, toolSnapshot, ctx, signal, batch);
                    }
                    else {
                        yield* this.toolRunner.runParallel(toolCalls, toolSnapshot, ctx, signal, batch);
                    }
                }
                // Early termination needs every finalized result of the batch to ask for it (pi).
                const terminate = batch.terminate.length > 0 && batch.terminate.every(Boolean);
                hasMoreToolCalls = toolCalls.length > 0 && !terminate;
                lengthStreak = assistantMessage.stopReason === "length" && toolCalls.length > 0 ? lengthStreak + 1 : 0;
                const decision = await this.finishTurn?.({ message: assistantMessage, toolResults }, signal);
                const boundaryContinue = await this.runTurnEndBoundary(turnIndex, assistantMessage, messageEntryId, toolResults, toolResultEntryIds, ctx);
                for (const message of this.boundaryMessages.splice(0))
                    yield { type: "user_message", message };
                state.turnIndex++;
                // Custom messages sent with triggerTurn:false while the turn ran land now (pi 0.84.4).
                for (const held of this.afterTurnMessages.splice(0))
                    await this.commitMessage(held, ctx);
                // tau guard: a model whose output keeps hitting the cap cannot finish its tool
                // call; end the run instead of spinning to max_turns.
                const truncatedOut = lengthStreak >= MAX_LENGTH_TRUNCATED_TURNS;
                if (decision?.action === "end" || truncatedOut) {
                    await this.flushWrites();
                    await this.extensions?.notifyAgentEnd(this.messages, ctx);
                    yield { type: "agent_end", messages: this.messages };
                    return "completed";
                }
                explicitContinue = boundaryContinue;
                pending = this.steeringQueue.drain();
                // P53c (yo-agent): a reply the output cap cut off goes on with one more request, not an error.
                const cutOff = assistantMessage.stopReason === "length" && toolCalls.length === 0;
                continuedReplies = cutOff ? continuedReplies + 1 : 0;
                if (cutOff && continuedReplies <= this.continueAfterLength && pending.length === 0) {
                    pending.push({
                        role: "custom",
                        customType: CONTINUE_AFTER_LENGTH,
                        content: CONTINUE_AFTER_LENGTH_TEXT,
                        display: false,
                        timestamp: Date.now(),
                    });
                }
                if (hasMoreToolCalls || pending.length > 0)
                    explicitContinue = false;
            }
            // The run would stop here: follow-ups continue it.
            const followUps = this.followUpQueue.drain();
            if (followUps.length > 0) {
                pending = followUps;
                explicitContinue = false;
                continue;
            }
            // No natural request was selected: fulfill a turn_end continue with one context-only request.
            if (explicitContinue) {
                explicitContinue = false;
                continue;
            }
            break;
        }
        await this.flushWrites();
        await this.extensions?.notifyAgentEnd(this.messages, ctx);
        yield { type: "agent_end", messages: this.messages };
        return "completed";
    }
    /**
     * Run the actionable turn_end boundary: drafts land in order, and a continue
     * request is honored only for a completed turn with runnable context.
     */
    async runTurnEndBoundary(turnIndex, message, messageEntryId, toolResults, toolResultEntryIds, ctx) {
        if (!this.extensions)
            return false;
        const outcome = message.stopReason === "aborted" ? "aborted" : message.stopReason === "error" ? "error" : "completed";
        const boundary = await this.extensions.runTurnEnd({ turnIndex, message, toolResults, messageEntryId, toolResultEntryIds, outcome }, ctx);
        await this.commitBoundaryDrafts(boundary.entries, "turn_end", ctx);
        if (!boundary.continue || outcome !== "completed")
            return false;
        if (!this.canContinue()) {
            this.extensions.reportError("turn_end", "turn_end requested continuation without runnable model context");
            return false;
        }
        return true;
    }
    /**
     * End a run early (abort): a retry cycle in progress closes as cancelled (pi
     * _finishCancelledRetry), then agent_end — never a throw (D14).
     */
    async *endRun(ctx, retryAttempt, pending = []) {
        // Messages already polled from the queue go back to it, so the host can restore them.
        this.steeringQueue.restore(pending);
        this.nestedCalls.clear();
        if (retryAttempt > 0) {
            const endEvent = { success: false, attempt: retryAttempt, finalError: "Retry cancelled" };
            await this.extensions?.notifyAutoRetryEnd(endEvent, ctx);
            yield { type: "auto_retry_end", ...endEvent };
        }
        await this.flushWrites();
        await this.extensions?.notifyAgentEnd(this.messages, ctx);
        yield { type: "agent_end", messages: this.messages };
    }
    /** Commit one tool result to messages/session and produce the AgentEvent pair tail. */
    async commitToolResult(toolCall, outcome, ctx, batch) {
        const { durationMs, usage: ownUsage, terminate, ...result } = normalizeToolOutcome(toolCall.name, outcome);
        // The calls this tool made through ctx.executeTool() and their usage (pi).
        const nested = this.nestedCalls.takeRecord(toolCall.id);
        const usage = nested?.usage ? (ownUsage ? combineUsage(ownUsage, nested.usage) : nested.usage) : ownUsage;
        const toolResultMessage = {
            role: "toolResult",
            toolCallId: toolCall.id,
            toolName: toolCall.name,
            content: result.content,
            isError: result.isError === true,
            timestamp: Date.now(),
            ...(result.details !== undefined ? { details: result.details } : {}),
            ...(durationMs !== undefined ? { durationMs } : {}),
            ...(usage ? { usage } : {}),
            ...(nested?.calls ? { nestedCalls: nested.calls } : {}),
        };
        const committed = await this.commitMessageWithId(toolResultMessage, ctx);
        batch.messages.push(committed.message);
        batch.entryIds.push(committed.entryId);
        batch.terminate.push(terminate === true);
        return {
            type: "tool_result",
            toolCall,
            result: terminate ? { ...result, terminate } : result,
            ...(durationMs !== undefined ? { durationMs } : {}),
        };
    }
    /** What the split-out modules read (P51: agent-internals.ts): getters read the current values. */
    exposeInternals() {
        const agent = this;
        return {
            get activePath() {
                return agent.activePath;
            },
            get baseTools() {
                return agent.baseTools;
            },
            get branchSummaryReserveTokens() {
                return agent.branchSummaryReserveTokens;
            },
            get capabilities() {
                return agent.capabilities;
            },
            get compactionSettings() {
                return agent.compactionSettings;
            },
            get config() {
                return agent.config;
            },
            createAgent: (options) => new Agent(options),
            get currentModel() {
                return agent.currentModel;
            },
            get currentThinkingLevel() {
                return agent.currentThinkingLevel;
            },
            currentTools: () => agent.toolState.currentTools(),
            effectiveCompactionSettings: () => agent.effectiveCompactionSettings(),
            enqueueBackground: (write) => agent.enqueueBackground(write),
            enqueueWrite: (write) => agent.enqueueWrite(write),
            extensionContext: () => agent.extensionContext(),
            get extensions() {
                return agent.extensions;
            },
            flushWrites: () => agent.flushWrites(),
            get followUpMode() {
                return agent.followUpQueue.mode;
            },
            get hiddenDeclarations() {
                return agent.hiddenDeclarations;
            },
            get imageProcessor() {
                return agent.imageProcessor;
            },
            get isProjectTrusted() {
                return agent.isProjectTrusted;
            },
            get maxTurnsPerPrompt() {
                return agent.maxTurnsPerPrompt;
            },
            get messages() {
                return agent.messages;
            },
            get onApproval() {
                return agent.onApproval;
            },
            get permissionMode() {
                return agent.permissionMode;
            },
            get platform() {
                return agent.platform;
            },
            get policy() {
                return agent.policy;
            },
            get preflightLock() {
                return agent.preflightLock;
            },
            priced: (usage) => agent.priced(usage),
            get pricing() {
                return agent.pricing;
            },
            pricingFor: (model) => agent.pricingFor(model),
            providerEventHooks: (ctx, model) => agent.providerEventHooks(ctx, model),
            providerHeaders: (ctx) => agent.providerHeaders(ctx),
            refresh: () => agent.refresh(),
            restoreToolsFromTranscript: () => agent.toolState.restoreToolsFromTranscript(),
            get retrySettings() {
                return agent.retrySettings;
            },
            get session() {
                return agent.session;
            },
            get steeringMode() {
                return agent.steeringQueue.mode;
            },
            summarizationRetry: (source, emit) => agent.summarizationRetry(source, emit),
            summaryTransport: () => agent.summaryTransport(),
            summaryMaxTokens: () => agent.summaryMaxTokens(),
            get systemPrompt() {
                return agent.systemPrompt;
            },
            get toolExecution() {
                return agent.toolExecution;
            },
            get transport() {
                return agent.transport;
            },
            get ui() {
                return agent.ui;
            },
        };
    }
}
/** Consecutive turns whose tool calls were cut off by the output cap before the run ends (tau guard). */
const MAX_LENGTH_TRUNCATED_TURNS = 3;
/** P53c: the hidden message that asks for the rest of a reply the output cap cut off (yo-agent's "请继续"). */
const CONTINUE_AFTER_LENGTH = "continue_after_length";
const CONTINUE_AFTER_LENGTH_TEXT = "Your previous reply was cut off by the output limit. Continue exactly where it stopped, without repeating what you already wrote.";
/** Error text for a tool call of a length-truncated response, verbatim from pi 0.80.4. */
function truncatedToolCallText(toolName) {
    return `Tool call "${toolName}" was not executed: the response hit the output token limit, so its arguments may be truncated. Re-issue the tool call with complete arguments.`;
}
/** Input content after input handlers: rewritten text replaces the text blocks; images follow the handlers. */
function rewrittenInput(original, originalText, result) {
    const originalImages = typeof original === "string" ? [] : original.filter((block) => block.type === "image");
    const images = result.images ?? originalImages;
    if (result.text === originalText && images === originalImages)
        return original;
    if (images.length === 0)
        return result.text;
    return [{ type: "text", text: result.text }, ...images];
}
