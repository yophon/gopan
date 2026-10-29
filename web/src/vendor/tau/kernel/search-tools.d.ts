/**
 * grep, find and ls (P26a). Parameters, output format and limit notices follow
 * pi 1.2.0 (core/tools/grep.ts, find.ts, ls.ts). Deviation: pi shells out to
 * ripgrep and fd (downloading them when missing); the kernel only has the
 * injected FileSystem, so these walk directories and match in pure ES and work
 * on every host (OPFS, mini-program, phone). Like rg/fd they skip `.git` and
 * honor `.gitignore` files (common syntax: wildcards, `**`, anchoring, `dir/`,
 * `!` negation); `node_modules` is always skipped.
 */
import type { FileSystem } from "./capabilities.js";
import { type Tool } from "./tools.js";
/** Glob → RegExp over `/`-separated paths: `**` crosses directories, `*` and `?` do not, plus `[...]` and `{a,b}`. */
export declare function globToRegExp(glob: string): RegExp;
/**
 * How rg --glob and fd --glob treat a pattern: without a `/` it matches the
 * file name at any depth; with one it matches the path from the search root
 * (also below it, as pi prefixes `**\/` for fd).
 */
export declare function globMatcher(pattern: string): (relativePath: string) => boolean;
interface IgnoreRule {
    negate: boolean;
    directoryOnly: boolean;
    matches: (relativePath: string) => boolean;
}
/** Parse one .gitignore file; paths given to the rules are relative to its directory. */
export declare function parseGitignore(text: string): IgnoreRule[];
export declare function escapeRegExp(text: string): string;
export declare function createGrepTool(fs: FileSystem): Tool;
export declare function createFindTool(fs: FileSystem): Tool;
export declare function createLsTool(fs: FileSystem): Tool;
export {};
