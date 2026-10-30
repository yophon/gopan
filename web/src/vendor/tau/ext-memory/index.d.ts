/**
 * Long-term memory (P26c, reshaped in P32a after Claude Code's auto memory and
 * Hermes Agent; P63 moved it out of the CLI into this pure-ES extension).
 * Layers: about the person (USER.md, always); optionally a project — an index
 * kept in the prompt plus topic files read on demand. The files live wherever
 * the host's MemoryStore puts them: the terminal keeps ~/.tau/memory/USER.md
 * and ~/.tau/projects/<project>/; another host may keep them on a server.
 * Writes have no size limit (P39a, Claude Code's auto memory): the prompt takes
 * the first 200 lines or 25 KB of USER.md and MEMORY.md, and a write that takes
 * one past that still succeeds, with a reminder to tidy it up.
 * The prompt gets a snapshot taken when the session starts, frozen until the
 * next one, so writes do not change the prompt mid-session (prefix caches).
 * All memory tools are low risk: saving needs no approval (user ruling, P32).
 * tau-original: pi has no model-writable memory (D36).
 */
import { type Extension, type FileSystem } from "@yophon/tau-kernel";
/**
 * Where the memory files are kept. A file is a string the host chooses (an absolute
 * path in the terminal, a name like "USER.md" elsewhere); it also shows in tool
 * results and /memory. All async: the store may be across a network.
 */
export interface MemoryStore {
    /** The whole content; "" when the file does not exist (never throws for that). */
    read(file: string): Promise<string>;
    /** Replace the whole content, creating the file (and in the terminal its directories). */
    write(file: string, content: string): Promise<void>;
    /** File names (not paths) in a directory; [] when it does not exist. Only called with a project layer. */
    list(dir: string): Promise<string[]>;
}
/** Where this conversation's memory lives: the person's layer always, a project's when there is one. */
export interface MemoryScope {
    /** About the person. */
    userFile: string;
    /** The project index and its topic directory; absent = only the person's layer. */
    project?: {
        file: string;
        topicsDir: string;
    };
}
export interface MemoryExtensionOptions {
    store: MemoryStore;
    /** Read on every use (the terminal settles it after loading); undefined = no memory here. */
    getScope: () => MemoryScope | undefined;
}
/** What the prompt loads of USER.md and MEMORY.md, whichever comes first (Claude Code: 200 lines or 25KB). */
export declare const MEMORY_LOAD_LIMITS: {
    readonly lines: 200;
    readonly bytes: number;
};
/** "12 lines, 1.4 KB" (or "1 line, 19 B") for memory_read and /memory. */
export declare function describeSize(content: string): string;
/**
 * Claude Code's reminder after a write to a file the prompt loads: over the load limit, what
 * is past it is dropped on the next load; near it (90%), keep it short. Undefined otherwise.
 */
export declare function loadLimitReminder(file: string, content: string): string | undefined;
/** One fact appended as a `- ` line unless it is already there (ignoring case and spacing). */
export declare function appendFact(existing: string, fact: string): {
    content: string;
    line: string;
    deduped: boolean;
};
/**
 * Replace the one occurrence of `old` with `replacement`; an empty replacement deletes it
 * (and its line, if that leaves it empty). `file` names the file in the errors.
 */
export declare function replaceOnce(content: string, old: string, replacement: string, file: string): string;
/**
 * The file a target (and topic) names, and whether the prompt loads it (USER.md and
 * MEMORY.md; topic files are read on demand); throws a reason the model can act on.
 */
export declare function resolveMemoryFile(scope: MemoryScope, target: unknown, topic?: unknown): {
    file: string;
    loaded: boolean;
};
/** The memory as a system prompt section from the layers' contents, or undefined when there is none. */
export declare function memoryPromptSection(layers: {
    userFile: string;
    user: string;
    project?: {
        file: string;
        content: string;
        topics: string[];
    };
}): string | undefined;
/**
 * A store over the kernel's FileSystem capability: the terminal passes host-node's
 * NodeFileSystem, a browser can pass OPFS. Read errors of any kind count as "" (the
 * memory is background, a missing or unreadable file must not break a turn).
 */
export declare function fileSystemMemoryStore(fs: FileSystem): MemoryStore;
export declare function createMemoryExtension(options: MemoryExtensionOptions): Extension;
