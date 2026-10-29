import type { FileSystem } from "./capabilities.js";
import type { AgentMessage, AssistantMessage, CustomMessage, ImageContent, SystemMessage, TextContent, ToolResultMessage, Usage, UserMessage } from "./messages.js";
import type { Platform } from "./platform.js";
/**
 * Session persistence in pi's v3 JSONL format (coding-agent/docs/
 * session-format.md): a header line followed by tree entries linked via
 * id/parentId. Entry shapes follow pi 1.2.0. tau reads every pi entry type it
 * knows (model_change and thinking_level_change are kept as chain links but
 * not produced until P23) and skips unknown ones. Compatibility is one-way
 * (D13 addendum ②): tau reads pi sessions; pi need not resume tau's, which may
 * contain tau's `leaf` entries.
 */
export interface SessionHeader {
    type: "session";
    version: 3;
    id: string;
    timestamp: string;
    cwd: string;
    parentSession?: string;
}
export interface SessionEntryBase {
    id: string;
    parentId: string | null;
    timestamp: string;
}
/** Content an append-only context edit may replace without changing message metadata (pi). */
export type ContextEditableContent = UserMessage["content"] | AssistantMessage["content"] | ToolResultMessage["content"] | CustomMessage["content"];
export type SessionEntry = (SessionEntryBase & {
    type: "message";
    message: AgentMessage;
}) | (SessionEntryBase & {
    type: "custom";
    customType: string;
    data: unknown;
}) | (SessionEntryBase & {
    type: "thinking_level_change";
    thinkingLevel: string;
}) | (SessionEntryBase & {
    type: "model_change";
    provider: string;
    modelId: string;
}) | (SessionEntryBase & {
    type: "usage";
    /** Arbitrary usage category, e.g. "cache_warm". Counted in session totals, never in context. */
    kind: string;
    provider: string;
    model: string;
    usage: Usage;
    note?: string;
}) | (SessionEntryBase & {
    type: "context_edit";
    targetId: string;
    /** Null omits the target from model context; a value replaces only its content. */
    replacement: {
        content: ContextEditableContent;
    } | null;
}) | (SessionEntryBase & {
    type: "label";
    targetId: string;
    label: string | undefined;
}) | (SessionEntryBase & {
    type: "custom_message";
    customType: string;
    content: string | (TextContent | ImageContent)[];
    display: boolean;
    details?: unknown;
}) | (SessionEntryBase & {
    type: "session_info";
    name?: string;
}) | (SessionEntryBase & {
    type: "compaction";
    summary: string;
    firstKeptEntryId: string;
    tokensBefore: number;
    details?: unknown;
    /** Usage of the summarization call(s). */
    usage?: Usage;
    fromHook?: boolean;
    /** Complete prompt and tool state at this boundary; replaces the system messages it summarizes. */
    systemMessage?: SystemMessage;
}) | (SessionEntryBase & {
    type: "branch_summary";
    /** Old leaf of the abandoned branch (see SessionRecorder.moveTo for the pi deviation). */
    fromId: string;
    summary: string;
    details?: unknown;
    usage?: Usage;
    fromHook?: boolean;
}) | (SessionEntryBase & {
    type: "leaf";
    leafId: string | null;
});
/** Omit that distributes over unions (plain Omit collapses SessionEntry to its common fields). */
type DistributiveOmit<T, K extends keyof T> = T extends unknown ? Omit<T, K> : never;
/** A session entry before the recorder assigns id, parentId and timestamp. */
export type SessionEntryDraft = DistributiveOmit<SessionEntry, "id" | "parentId" | "timestamp">;
export interface SessionMetadata {
    id: string;
    cwd: string;
    timestamp: string;
    /** Last activity: the newest entry's timestamp (the header's when empty). --continue sorts by it. */
    modified?: string;
    filePath?: string;
    name?: string;
    /** Fork lineage: source file path (JSONL) or source session id (in-memory). */
    parentSession?: string;
    /** Text of the first user message, at most 500 characters (pi SessionInfo.firstMessage); absent when there is none. */
    firstMessage?: string;
    /** Message entries in the file, every branch included (pi SessionInfo.messageCount). */
    messageCount?: number;
}
/** Mirrors pi's SessionStorage (harness/types.ts), minus find/label (P5). */
export interface SessionStore {
    getMetadata(): Promise<SessionMetadata>;
    getLeafId(): Promise<string | null>;
    setLeafId(leafId: string | null): Promise<void>;
    createEntryId(): Promise<string>;
    appendEntry(entry: SessionEntry): Promise<void>;
    getEntry(id: string): Promise<SessionEntry | undefined>;
    getPathToRoot(leafId: string | null): Promise<SessionEntry[]>;
    getEntries(): Promise<SessionEntry[]>;
}
/** Options for a new session: an id, and the session it continues from (pi newSession's parentSession, P37b /handoff). */
export interface CreateSessionOptions {
    id?: string;
    /** Source file path (JSONL) or source session id (in-memory), as fork() records it. */
    parentSession?: string;
}
/** Mirrors pi's SessionRepo. */
export interface SessionRepo {
    create(options?: CreateSessionOptions): Promise<SessionStore>;
    open(metadata: SessionMetadata): Promise<SessionStore>;
    list(): Promise<SessionMetadata[]>;
    delete(metadata: SessionMetadata): Promise<void>;
    /** Copy a session (or a prefix of it) into a new independent session. */
    fork(source: SessionMetadata, options?: ForkOptions): Promise<SessionStore>;
}
export interface ForkOptions {
    /** Fork point; omitted = copy the whole session including all branches. */
    entryId?: string;
    /** "at" includes the target entry; "before" (default) requires a user message and cuts above it. */
    position?: "before" | "at";
    id?: string;
}
/** Entries a fork copies, verbatim from pi's getEntriesToFork (session/repo-utils.ts). */
export declare function getEntriesToFork(store: SessionStore, options: ForkOptions): Promise<SessionEntry[]>;
export declare class InMemorySessionStore implements SessionStore {
    private readonly metadata;
    private readonly tree;
    private readonly randomBytes;
    constructor(metadata: SessionMetadata, randomBytes: Platform["randomBytes"]);
    getMetadata(): Promise<SessionMetadata>;
    getLeafId(): Promise<string | null>;
    setLeafId(leafId: string | null): Promise<void>;
    createEntryId(): Promise<string>;
    appendEntry(entry: SessionEntry): Promise<void>;
    getEntry(id: string): Promise<SessionEntry | undefined>;
    getPathToRoot(leafId: string | null): Promise<SessionEntry[]>;
    getEntries(): Promise<SessionEntry[]>;
}
export declare class InMemorySessionRepo implements SessionRepo {
    private readonly platform;
    private readonly cwd;
    private readonly stores;
    private readonly metadataById;
    constructor(platform: Platform, cwd?: string);
    create(options?: CreateSessionOptions): Promise<SessionStore>;
    open(metadata: SessionMetadata): Promise<SessionStore>;
    list(): Promise<SessionMetadata[]>;
    delete(metadata: SessionMetadata): Promise<void>;
    fork(source: SessionMetadata, options?: ForkOptions): Promise<SessionStore>;
}
/** Session directory naming mirrors pi: `--<cwd with / replaced by ->--`. */
export declare function sessionDirSlug(cwd: string): string;
export declare class JsonlSessionRepo implements SessionRepo {
    private readonly fs;
    private readonly platform;
    private readonly sessionsDir;
    private readonly cwd;
    /** sessionsDir is the per-cwd directory (host composes root + sessionDirSlug(cwd)). */
    constructor(fs: FileSystem, platform: Platform, sessionsDir: string, cwd: string);
    private loadStore;
    create(options?: CreateSessionOptions): Promise<SessionStore>;
    open(metadata: SessionMetadata): Promise<SessionStore>;
    list(): Promise<SessionMetadata[]>;
    delete(metadata: SessionMetadata): Promise<void>;
    /**
     * Copy a session file into this directory and open the copy (pi importFromJsonl):
     * a taken file name gets `-1`, `-2`, …; a file already in this directory opens in place.
     */
    importFile(sourcePath: string): Promise<SessionStore>;
    fork(source: SessionMetadata, options?: ForkOptions): Promise<SessionStore>;
}
/**
 * Appends to a SessionStore, maintaining the parentId chain. Every entry is
 * JSON-cloned on the way in: the log holds exactly what a JSONL file would
 * replay, and later mutation of the caller's objects cannot reach it (P22).
 */
export declare class SessionRecorder {
    readonly store: SessionStore;
    private leafId;
    private constructor();
    static open(store: SessionStore): Promise<SessionRecorder>;
    /** Recorder over a store whose leaf is already known (a fresh store: null). */
    static attach(store: SessionStore, leafId: string | null): SessionRecorder;
    get currentLeafId(): string | null;
    /** Append an entry at the current leaf and return it as stored. */
    append(draft: SessionEntryDraft): Promise<SessionEntry>;
    recordCompaction(result: {
        summary: string;
        firstKeptEntryId: string;
        tokensBefore: number;
        details?: unknown;
        usage?: Usage;
        fromHook?: boolean;
        systemMessage?: SystemMessage;
    }): Promise<SessionEntry>;
    /** Record a message; custom messages become custom_message entries (pi). Returns the entry. */
    recordMessage(message: AgentMessage): Promise<SessionEntry>;
    /** Omit (null) or replace the content of an entry in future context; the entry itself stays (pi 0.87.0). */
    appendContextEdit(targetId: string, replacement: {
        content: ContextEditableContent;
    } | null): Promise<SessionEntry>;
    /** Bookmark an entry; an undefined label clears it (pi LabelEntry). */
    appendLabel(targetId: string, label: string | undefined): Promise<SessionEntry>;
    appendCustom(customType: string, data: unknown): Promise<SessionEntry>;
    setName(name: string): Promise<SessionEntry>;
    /**
     * Move the active leaf to another entry (session-tree navigation),
     * optionally recording a branch_summary of the abandoned branch at the new
     * position. Mirrors pi's Session.moveTo. Deviation: pi's implementation
     * writes fromId = destination id (redundant with parentId); tau records the
     * abandoned branch's old leaf, matching pi's session-format.md ("Entry we
     * branched from") whose example has fromId ≠ parentId.
     */
    moveTo(entryId: string | null, summary?: {
        summary: string;
        details?: unknown;
        usage?: Usage;
        fromHook?: boolean;
    }): Promise<SessionEntry | undefined>;
}
export interface RestoredSession {
    messages: AgentMessage[];
    name?: string;
}
/**
 * Model context of a path — the session projection (projection.ts, pi
 * buildSessionContext) — plus the session name from the full path.
 */
export declare function messagesFromPath(path: SessionEntry[]): RestoredSession;
/** Rebuild conversation state from the store's active path (leaf → root). */
export declare function restoreSession(store: SessionStore): Promise<RestoredSession>;
export {};
