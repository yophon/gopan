import type { FileSystem } from "./capabilities.js";
/** Per directory, the first of these that is a regular file wins (pi order). */
export declare const CONTEXT_FILE_NAMES: readonly string[];
export interface ContextFile {
    path: string;
    content: string;
}
/**
 * Discover context files: the global one (`globalDir`, tau's `~/.tau`) first,
 * then one per directory from the filesystem root down to `cwd` (ancestors
 * before descendants). Paths are deduplicated. `cwd` must be absolute.
 */
export declare function loadProjectContextFiles(fs: FileSystem, options: {
    cwd: string;
    globalDir?: string;
    /**
     * Files to leave out (P37d): the host passes the main checkout's AGENTS.md that a
     * nested worktree's own copy shadows (pi findShadowedContextFile; the kernel has no realpath).
     */
    skip?: readonly string[];
}): Promise<ContextFile[]>;
/** The `<project_context>` system prompt section (pi wording); "" when there are no files. */
export declare function renderProjectContext(files: readonly ContextFile[]): string;
