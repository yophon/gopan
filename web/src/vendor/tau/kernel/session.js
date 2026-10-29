import { SessionError, toError } from "./errors.js";
import { buildSessionProjection } from "./projection.js";
import { uuidv7 } from "./uuid.js";
/** Entries a fork copies, verbatim from pi's getEntriesToFork (session/repo-utils.ts). */
export async function getEntriesToFork(store, options) {
    if (!options.entryId)
        return store.getEntries();
    const target = await store.getEntry(options.entryId);
    if (!target)
        throw new SessionError("invalid_fork_target", `Entry ${options.entryId} not found`);
    let effectiveLeafId;
    if ((options.position ?? "before") === "at") {
        effectiveLeafId = target.id;
    }
    else {
        if (target.type !== "message" || target.message.role !== "user") {
            throw new SessionError("invalid_fork_target", `Entry ${options.entryId} is not a user message`);
        }
        effectiveLeafId = target.parentId;
    }
    return store.getPathToRoot(effectiveLeafId);
}
/**
 * Entry-id generation as in pi: 8 random hex digits (pi: randomUUID().slice(0, 8),
 * a v4 UUID), retried on collision, full UUID as the last resort. tau used to slice
 * a uuidv7, whose first 8 hex digits are the millisecond clock's high bits: every
 * entry after the first in a ~65 s window collided and fell back to a full UUID.
 */
function generateEntryId(randomBytes, has) {
    for (let i = 0; i < 100; i++) {
        const id = [...randomBytes(4)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
        if (!has(id))
            return id;
    }
    return uuidv7(randomBytes);
}
/**
 * Shared tree state for both store implementations. The active leaf is the
 * last appended non-leaf entry unless an explicit leaf entry overrides it.
 */
/** Freeze a JSON value and everything inside it. */
function deepFreeze(value) {
    if (value !== null && typeof value === "object" && !Object.isFrozen(value)) {
        Object.freeze(value);
        for (const child of Object.values(value))
            deepFreeze(child);
    }
    return value;
}
class EntryTree {
    entries = [];
    byId = new Map();
    leafId = null;
    /** P42c: what getMetadata reports, kept as entries are added instead of scanned each time. */
    messageCount = 0;
    firstMessage;
    /** The latest session_info entry's name; `named` says whether there was one (a name can be cleared). */
    name;
    named = false;
    /**
     * Entries are append-only and deep-frozen on the way in (P22): the agent's
     * projection shares their message objects, so an in-place edit of a logged
     * message throws instead of silently rewriting history.
     */
    add(entry) {
        deepFreeze(entry);
        this.entries.push(entry);
        this.byId.set(entry.id, entry);
        if (entry.type === "leaf")
            this.leafId = entry.leafId;
        else
            this.leafId = entry.id;
        if (entry.type === "session_info") {
            this.name = entry.name;
            this.named = true;
        }
        else if (entry.type === "message") {
            this.messageCount++;
            if (this.firstMessage === undefined && entry.message.role === "user") {
                const text = contentLine(entry.message.content);
                if (text !== "")
                    this.firstMessage = text.slice(0, 500);
            }
        }
    }
    /** messageCount and firstMessage, as messageSummary reported them. */
    summary() {
        return {
            ...(this.firstMessage !== undefined ? { firstMessage: this.firstMessage } : {}),
            messageCount: this.messageCount,
        };
    }
    pathToRoot(leafId) {
        const path = [];
        let currentId = leafId;
        while (currentId !== null) {
            const entry = this.byId.get(currentId);
            if (!entry)
                throw new SessionError("invalid_entry", `Entry ${currentId} not found while walking to root`);
            path.push(entry);
            currentId = entry.parentId;
        }
        return path.reverse();
    }
}
export class InMemorySessionStore {
    metadata;
    tree = new EntryTree();
    randomBytes;
    constructor(metadata, randomBytes) {
        this.metadata = metadata;
        this.randomBytes = randomBytes;
    }
    async getMetadata() {
        return {
            ...this.metadata,
            name: (this.tree.named ? this.tree.name : undefined) ?? this.metadata.name,
            ...this.tree.summary(),
        };
    }
    async getLeafId() {
        return this.tree.leafId;
    }
    async setLeafId(leafId) {
        this.tree.add({
            type: "leaf",
            id: generateEntryId(this.randomBytes, (id) => this.tree.byId.has(id)),
            parentId: null,
            timestamp: new Date().toISOString(),
            leafId,
        });
    }
    async createEntryId() {
        return generateEntryId(this.randomBytes, (id) => this.tree.byId.has(id));
    }
    async appendEntry(entry) {
        this.tree.add(entry);
    }
    async getEntry(id) {
        return this.tree.byId.get(id);
    }
    async getPathToRoot(leafId) {
        return this.tree.pathToRoot(leafId);
    }
    async getEntries() {
        return [...this.tree.entries];
    }
}
export class InMemorySessionRepo {
    platform;
    cwd;
    stores = new Map();
    metadataById = new Map();
    constructor(platform, cwd = "/") {
        this.platform = platform;
        this.cwd = cwd;
    }
    async create(options) {
        const metadata = {
            id: options?.id ?? uuidv7(this.platform.randomBytes),
            cwd: this.cwd,
            timestamp: new Date().toISOString(),
            ...(options?.parentSession ? { parentSession: options.parentSession } : {}),
        };
        const store = new InMemorySessionStore(metadata, this.platform.randomBytes);
        this.stores.set(metadata.id, store);
        this.metadataById.set(metadata.id, metadata);
        return store;
    }
    async open(metadata) {
        const store = this.stores.get(metadata.id);
        if (!store)
            throw new SessionError("not_found", `Session ${metadata.id} not found`);
        return store;
    }
    async list() {
        return Promise.all([...this.stores.values()].map((store) => store.getMetadata()));
    }
    async delete(metadata) {
        this.stores.delete(metadata.id);
        this.metadataById.delete(metadata.id);
    }
    async fork(source, options) {
        const sourceStore = await this.open(source);
        const entries = await getEntriesToFork(sourceStore, options ?? {});
        const metadata = {
            id: options?.id ?? uuidv7(this.platform.randomBytes),
            cwd: this.cwd,
            timestamp: new Date().toISOString(),
            parentSession: source.id,
        };
        const store = new InMemorySessionStore(metadata, this.platform.randomBytes);
        for (const entry of entries)
            await store.appendEntry(entry);
        this.stores.set(metadata.id, store);
        this.metadataById.set(metadata.id, metadata);
        return store;
    }
}
// ---------------------------------------------------------------------------
// JSONL implementation, on the FileSystem capability (pure kernel code).
// ---------------------------------------------------------------------------
function parseHeaderLine(line, filePath) {
    let parsed;
    try {
        parsed = JSON.parse(line);
    }
    catch (error) {
        throw new SessionError("invalid_session", `Invalid session file ${filePath}: bad header`, toError(error));
    }
    if (parsed === null ||
        typeof parsed !== "object" ||
        parsed.type !== "session" ||
        parsed.version !== 3) {
        throw new SessionError("invalid_session", `Invalid session file ${filePath}: first line is not a v3 header`);
    }
    const header = parsed;
    if (typeof header.id !== "string" || typeof header.timestamp !== "string" || typeof header.cwd !== "string") {
        throw new SessionError("invalid_session", `Invalid session file ${filePath}: header missing id/timestamp/cwd`);
    }
    // Validated above.
    return parsed;
}
/** The entry type at the start of a line as tau and pi write them: `{"type":"message",…`. */
const LEADING_TYPE = /^\{"type":"([a-z_]+)"/;
/**
 * A session file's list entry without building its entry tree (P42c; pi buildSessionInfo also
 * reads the file line by line): the header, the latest name, the last entry's time, the message
 * count and the first user message, as JsonlSessionStore.getMetadata reports them. Lines that
 * start with their type are counted without parsing; names, the first user message, the last
 * line (a crash can leave it half written) and lines in another shape are parsed.
 */
async function readSessionMetadata(fs, filePath) {
    const text = await fs.readTextFile(filePath);
    const lines = text.split("\n");
    let first = 0;
    while (first < lines.length && lines[first] === "")
        first++;
    if (first >= lines.length)
        throw new SessionError("invalid_session", `Invalid session file ${filePath}: empty`);
    const header = parseHeaderLine(lines[first], filePath);
    let last = lines.length - 1;
    while (last > first && lines[last] === "")
        last--;
    let messageCount = 0;
    let firstMessage;
    let name;
    let modified;
    for (let i = first + 1; i <= last; i++) {
        const line = lines[i];
        if (line === "")
            continue;
        const leading = i < last ? LEADING_TYPE.exec(line)?.[1] : undefined;
        const needsEntry = leading === undefined || leading === "session_info" || (leading === "message" && firstMessage === undefined);
        const entry = needsEntry ? parseEntryLine(line, filePath, i + 1) : undefined;
        if (needsEntry && !entry)
            continue;
        const type = entry?.type ?? leading;
        if (type === "session_info" && entry?.type === "session_info")
            name = entry.name;
        if (type === "message") {
            messageCount++;
            if (firstMessage === undefined && entry?.type === "message" && entry.message.role === "user") {
                const text = contentLine(entry.message.content);
                if (text !== "")
                    firstMessage = text.slice(0, 500);
            }
        }
        if (i === last && entry)
            modified = entry.timestamp;
        else if (i === last)
            modified = undefined;
    }
    if (modified === undefined) {
        // The last line did not parse (a crash mid-write): the latest whole entry's time.
        for (let i = last - 1; i > first && modified === undefined; i--) {
            if (lines[i] !== "")
                modified = parseEntryLine(lines[i], filePath, i + 1)?.timestamp;
        }
    }
    return {
        id: header.id,
        cwd: header.cwd,
        timestamp: header.timestamp,
        modified: modified ?? header.timestamp,
        filePath,
        name,
        parentSession: header.parentSession,
        ...(firstMessage !== undefined ? { firstMessage } : {}),
        messageCount,
    };
}
/** Run `map` over `items`, at most `limit` at a time, keeping the order (pi mapWithConcurrency). */
async function mapWithConcurrency(items, limit, map) {
    const results = new Array(items.length);
    let next = 0;
    const worker = async () => {
        while (next < items.length) {
            const index = next++;
            results[index] = await map(items[index]);
        }
    };
    await Promise.all(Array.from({ length: Math.min(limit, items.length) }, () => worker()));
    return results;
}
function parseEntryLine(line, filePath, lineNumber) {
    let parsed;
    try {
        parsed = JSON.parse(line);
    }
    catch {
        // pi parseSessionEntryLine: a malformed line (typically the half-written last
        // line of a crashed run) is skipped instead of making the whole session unreadable.
        return undefined;
    }
    if (parsed === null || typeof parsed !== "object")
        return undefined;
    const entry = parsed;
    // Entry types tau does not know (a newer pi) stay in the tree as opaque chain links:
    // skipping them would orphan their children. Projection and compaction ignore them.
    if (typeof entry.type !== "string")
        return undefined;
    if (typeof entry.id !== "string" || typeof entry.timestamp !== "string") {
        throw new SessionError("invalid_entry", `Invalid session file ${filePath}: line ${lineNumber} is missing id/timestamp`);
    }
    // Validated above.
    return parsed;
}
/** First user message text and message count over all entries, as pi's session list reads them. */
function lastSessionName(entries) {
    for (let i = entries.length - 1; i >= 0; i--) {
        const entry = entries[i];
        if (entry.type === "session_info")
            return entry.name;
    }
    return undefined;
}
/** A user message's text for the session list: its text parts joined, trimmed. */
function contentLine(content) {
    return (typeof content === "string"
        ? content
        : content
            .filter((part) => part.type === "text")
            .map((part) => part.text)
            .join(" ")).trim();
}
class JsonlSessionStore {
    fs;
    filePath;
    header;
    tree;
    randomBytes;
    /**
     * False for a fresh session until its first message: the header and any
     * earlier entries (leaf moves, a name) are held in memory, so a start-up that
     * never prompts leaves no empty file behind for --continue to pick (pi 0.99.0, HF-16).
     */
    persisted;
    /** The loaded file lacks a final newline (crash mid-write); the first append adds one (pi 0.84.4). */
    missingNewline;
    constructor(fs, filePath, header, tree, randomBytes, persisted = true, missingNewline = false) {
        this.fs = fs;
        this.filePath = filePath;
        this.header = header;
        this.tree = tree;
        this.randomBytes = randomBytes;
        this.persisted = persisted;
        this.missingNewline = missingNewline;
    }
    async getMetadata() {
        return {
            id: this.header.id,
            cwd: this.header.cwd,
            timestamp: this.header.timestamp,
            modified: this.tree.entries.at(-1)?.timestamp ?? this.header.timestamp,
            filePath: this.filePath,
            name: this.tree.named ? this.tree.name : undefined,
            parentSession: this.header.parentSession,
            ...this.tree.summary(),
        };
    }
    async getLeafId() {
        return this.tree.leafId;
    }
    async setLeafId(leafId) {
        const entry = {
            type: "leaf",
            id: generateEntryId(this.randomBytes, (id) => this.tree.byId.has(id)),
            parentId: null,
            timestamp: new Date().toISOString(),
            leafId,
        };
        await this.appendEntry(entry);
    }
    async createEntryId() {
        return generateEntryId(this.randomBytes, (id) => this.tree.byId.has(id));
    }
    async appendEntry(entry) {
        if (this.persisted) {
            const prefix = this.missingNewline ? "\n" : "";
            await this.fs.appendFile(this.filePath, `${prefix}${JSON.stringify(entry)}\n`);
            this.missingNewline = false;
        }
        else if (entry.type === "message") {
            const lines = [this.header, ...this.tree.entries, entry].map((line) => JSON.stringify(line));
            await this.fs.writeTextFile(this.filePath, `${lines.join("\n")}\n`);
            this.persisted = true;
        }
        this.tree.add(entry);
    }
    async getEntry(id) {
        return this.tree.byId.get(id);
    }
    async getPathToRoot(leafId) {
        return this.tree.pathToRoot(leafId);
    }
    async getEntries() {
        return [...this.tree.entries];
    }
}
/** Session directory naming mirrors pi: `--<cwd with / replaced by ->--`. */
export function sessionDirSlug(cwd) {
    // Windows drive paths use backslashes; keep both separators out of the slug so
    // the resulting directory name stays valid on every platform (a colon in a
    // Windows directory name is rejected by the filesystem).
    return `--${cwd.replaceAll("/", "-").replaceAll("\\", "-").replace(/^-+/, "").replaceAll(":", "")}--`;
}
export class JsonlSessionRepo {
    fs;
    platform;
    sessionsDir;
    cwd;
    /** sessionsDir is the per-cwd directory (host composes root + sessionDirSlug(cwd)). */
    constructor(fs, platform, sessionsDir, cwd) {
        this.fs = fs;
        this.platform = platform;
        this.sessionsDir = sessionsDir;
        this.cwd = cwd;
    }
    async loadStore(filePath) {
        const text = await this.fs.readTextFile(filePath);
        const lines = text.split("\n").filter((line) => line !== "");
        if (lines.length === 0)
            throw new SessionError("invalid_session", `Invalid session file ${filePath}: empty`);
        const header = parseHeaderLine(lines[0], filePath);
        const tree = new EntryTree();
        for (let i = 1; i < lines.length; i++) {
            const entry = parseEntryLine(lines[i], filePath, i + 1);
            if (entry)
                tree.add(entry);
        }
        return new JsonlSessionStore(this.fs, filePath, header, tree, this.platform.randomBytes, true, !text.endsWith("\n"));
    }
    async create(options) {
        const id = options?.id ?? uuidv7(this.platform.randomBytes);
        const timestamp = new Date().toISOString();
        const header = {
            type: "session",
            version: 3,
            id,
            timestamp,
            cwd: this.cwd,
            ...(options?.parentSession ? { parentSession: options.parentSession } : {}),
        };
        const fileName = `${timestamp.replaceAll(":", "-")}_${id}.jsonl`;
        const filePath = joinSessionPath(this.sessionsDir, fileName);
        return new JsonlSessionStore(this.fs, filePath, header, new EntryTree(), this.platform.randomBytes, false);
    }
    async open(metadata) {
        if (!metadata.filePath)
            throw new SessionError("not_found", `Session ${metadata.id} has no file path`);
        return this.loadStore(metadata.filePath);
    }
    async list() {
        let files;
        try {
            files = await this.fs.listDir(this.sessionsDir);
        }
        catch {
            return [];
        }
        // P42c (pi): ten files at a time, each read for its list entry only.
        const found = await mapWithConcurrency(files.filter((file) => file.name.endsWith(".jsonl")), 10, async (file) => {
            try {
                return await readSessionMetadata(this.fs, file.path);
            }
            catch {
                // Unreadable session files are skipped in listings.
                return undefined;
            }
        });
        const sessions = found.filter((meta) => meta !== undefined);
        // Most recently active first, as pi lists sessions (not by creation time).
        return sessions.sort((a, b) => (b.modified ?? b.timestamp).localeCompare(a.modified ?? a.timestamp));
    }
    async delete(metadata) {
        if (!metadata.filePath)
            throw new SessionError("not_found", `Session ${metadata.id} has no file path`);
        await this.fs.remove(metadata.filePath);
    }
    /**
     * Copy a session file into this directory and open the copy (pi importFromJsonl):
     * a taken file name gets `-1`, `-2`, …; a file already in this directory opens in place.
     */
    async importFile(sourcePath) {
        const text = await this.fs.readTextFile(sourcePath);
        const first = text.split("\n").find((line) => line !== "");
        if (first === undefined)
            throw new SessionError("invalid_session", `Invalid session file ${sourcePath}: empty`);
        parseHeaderLine(first, sourcePath);
        const normalize = (path) => path.replaceAll("\\", "/").replace(/\/+$/, "");
        const fileName = normalize(sourcePath).split("/").at(-1) ?? "session.jsonl";
        const sourceDir = normalize(sourcePath).slice(0, -fileName.length - 1);
        if (sourceDir === normalize(this.sessionsDir))
            return this.loadStore(sourcePath);
        let taken;
        try {
            taken = new Set((await this.fs.listDir(this.sessionsDir)).map((file) => file.name));
        }
        catch {
            taken = new Set();
        }
        const stem = fileName.endsWith(".jsonl") ? fileName.slice(0, -".jsonl".length) : fileName;
        let target = `${stem}.jsonl`;
        for (let n = 1; taken.has(target); n++)
            target = `${stem}-${n}.jsonl`;
        const filePath = joinSessionPath(this.sessionsDir, target);
        await this.fs.writeTextFile(filePath, text);
        return this.loadStore(filePath);
    }
    async fork(source, options) {
        if (!source.filePath)
            throw new SessionError("not_found", `Session ${source.id} has no file path`);
        const sourceStore = await this.loadStore(source.filePath);
        const entries = await getEntriesToFork(sourceStore, options ?? {});
        const id = options?.id ?? uuidv7(this.platform.randomBytes);
        const timestamp = new Date().toISOString();
        const header = {
            type: "session",
            version: 3,
            id,
            timestamp,
            cwd: this.cwd,
            parentSession: source.filePath,
        };
        const fileName = `${timestamp.replaceAll(":", "-")}_${id}.jsonl`;
        const filePath = joinSessionPath(this.sessionsDir, fileName);
        const lines = [header, ...entries].map((line) => JSON.stringify(line));
        await this.fs.writeTextFile(filePath, `${lines.join("\n")}\n`);
        return this.loadStore(filePath);
    }
}
// ---------------------------------------------------------------------------
// Recording and restoring conversations.
// ---------------------------------------------------------------------------
/**
 * Appends to a SessionStore, maintaining the parentId chain. Every entry is
 * JSON-cloned on the way in: the log holds exactly what a JSONL file would
 * replay, and later mutation of the caller's objects cannot reach it (P22).
 */
export class SessionRecorder {
    store;
    leafId;
    constructor(store, leafId) {
        this.store = store;
        this.leafId = leafId;
    }
    static async open(store) {
        return new SessionRecorder(store, await store.getLeafId());
    }
    /** Recorder over a store whose leaf is already known (a fresh store: null). */
    static attach(store, leafId) {
        return new SessionRecorder(store, leafId);
    }
    get currentLeafId() {
        return this.leafId;
    }
    /** Append an entry at the current leaf and return it as stored. */
    async append(draft) {
        const id = await this.store.createEntryId();
        const entry = JSON.parse(JSON.stringify({ ...draft, id, parentId: this.leafId, timestamp: new Date().toISOString() }));
        await this.store.appendEntry(entry);
        this.leafId = id;
        return entry;
    }
    async recordCompaction(result) {
        return this.append({ type: "compaction", ...result });
    }
    /** Record a message; custom messages become custom_message entries (pi). Returns the entry. */
    async recordMessage(message) {
        if (message.role === "custom") {
            return this.append({
                type: "custom_message",
                customType: message.customType,
                content: message.content,
                display: message.display,
                details: message.details,
            });
        }
        return this.append({ type: "message", message });
    }
    /** Omit (null) or replace the content of an entry in future context; the entry itself stays (pi 0.87.0). */
    async appendContextEdit(targetId, replacement) {
        return this.append({ type: "context_edit", targetId, replacement });
    }
    /** Bookmark an entry; an undefined label clears it (pi LabelEntry). */
    async appendLabel(targetId, label) {
        return this.append({ type: "label", targetId, label });
    }
    async appendCustom(customType, data) {
        return this.append({ type: "custom", customType, data });
    }
    async setName(name) {
        return this.append({ type: "session_info", name });
    }
    /**
     * Move the active leaf to another entry (session-tree navigation),
     * optionally recording a branch_summary of the abandoned branch at the new
     * position. Mirrors pi's Session.moveTo. Deviation: pi's implementation
     * writes fromId = destination id (redundant with parentId); tau records the
     * abandoned branch's old leaf, matching pi's session-format.md ("Entry we
     * branched from") whose example has fromId ≠ parentId.
     */
    async moveTo(entryId, summary) {
        if (entryId !== null && !(await this.store.getEntry(entryId))) {
            throw new SessionError("not_found", `Entry ${entryId} not found`);
        }
        const fromId = this.leafId ?? "root";
        await this.store.setLeafId(entryId);
        this.leafId = entryId;
        if (!summary)
            return undefined;
        const id = await this.store.createEntryId();
        const entry = {
            type: "branch_summary",
            id,
            parentId: entryId,
            timestamp: new Date().toISOString(),
            fromId,
            summary: summary.summary,
            details: summary.details,
            ...(summary.usage ? { usage: summary.usage } : {}),
            fromHook: summary.fromHook,
        };
        await this.store.appendEntry(entry);
        this.leafId = id;
        return entry;
    }
}
/**
 * Model context of a path — the session projection (projection.ts, pi
 * buildSessionContext) — plus the session name from the full path.
 */
export function messagesFromPath(path) {
    return { messages: buildSessionProjection(path).messages, name: lastSessionName(path) };
}
/** Rebuild conversation state from the store's active path (leaf → root). */
export async function restoreSession(store) {
    return messagesFromPath(await store.getPathToRoot(await store.getLeafId()));
}
/**
 * Join a session file onto its directory with the directory's own separator.
 * The kernel is pure ES (no node:path); on Windows the host hands us a
 * backslash directory, and mixing `/` into it made recorded paths
 * (`parentSession`) disagree with the host's `path.join` of the same file.
 */
function joinSessionPath(dir, fileName) {
    const sep = dir.includes("\\") && !dir.includes("/") ? "\\" : "/";
    return dir.endsWith(sep) ? `${dir}${fileName}` : `${dir}${sep}${fileName}`;
}
