/**
 * Permission rules (P37a), copied from Claude Code's `permissions.allow/ask/deny`:
 * `Tool` or `Tool(specifier)`, evaluated deny → ask → allow with the first match
 * deciding. pi has no counterpart (it leaves approval to extensions); the rule
 * syntax, matching and precedence follow code.claude.com/docs/en/permissions.
 *
 * Rules reduce prompts and accidents; like Claude Code's, they are not a
 * security boundary (`sh -c`, absolute binaries and the like get past a deny).
 * Pure ES: hosts read and write the settings files, the kernel only matches.
 */
export type PermissionRuleKind = "allow" | "ask" | "deny";
export interface PermissionRule {
    /** As written, e.g. "Bash(npm test *)". */
    text: string;
    kind: PermissionRuleKind;
    /** Lower-cased tool names or name globs the rule covers ("*" for every tool). */
    tools: readonly string[];
    /** The text inside the parentheses; undefined for a whole-tool rule. */
    specifier?: string;
    /** What "/path" patterns are relative to (the settings file's root). */
    base?: string;
    /** Where it came from, shown in /permissions and audit records. */
    source?: string;
    /** A "!pattern" path rule: excludes matches of the same kind from the same source. */
    negated?: boolean;
}
export interface PermissionRules {
    allow: readonly PermissionRule[];
    ask: readonly PermissionRule[];
    deny: readonly PermissionRule[];
}
export interface PermissionRuleCall {
    toolName: string;
    args: Record<string, unknown>;
    /** The working directory relative paths resolve against. */
    cwd?: string;
    /** The home directory "~/" resolves to. */
    home?: string;
}
export interface PermissionRuleMatch {
    kind: PermissionRuleKind;
    rule: PermissionRule;
}
export declare const EMPTY_PERMISSION_RULES: PermissionRules;
/**
 * Parse one rule string. `kind` matters for validation: allow rules may not use an
 * unanchored tool-name glob, parameter rules are for deny and ask only.
 */
export declare function parsePermissionRule(text: string, kind: PermissionRuleKind, options?: {
    base?: string;
    source?: string;
}): {
    rule: PermissionRule;
} | {
    error: string;
};
/** Whether a whole-tool deny rule takes this tool off the model's list (Claude Code). */
export declare function deniesWholeTool(rules: PermissionRules, toolName: string): boolean;
/**
 * The rule that decides a call, deny → ask → allow, first match wins. For bash an
 * allow needs every subcommand matched (by any allow rules); deny and ask fire on
 * any subcommand. Read/Edit deny and ask rules also apply to bash's file commands
 * and redirections.
 */
export declare function matchPermissionRules(rules: PermissionRules, call: PermissionRuleCall): PermissionRuleMatch | undefined;
/** `*` matches any text (spaces too); a trailing ` *` that is the only wildcard also matches the bare command; `:*` is the legacy trailing form. */
export declare function bashPatternMatches(pattern: string, input: string): boolean;
/** A PowerShell pattern: bash's wildcards over the command with aliases resolved, case ignored (Claude Code). */
export declare function powerShellPatternMatches(pattern: string, segment: string): boolean;
export interface ParsedCommand {
    /** False when quotes or parentheses do not balance or an operator dangles. */
    ok: boolean;
    raw: string;
    /** Every simple command, wrappers and leading assignments stripped, words joined by one space. */
    segments: string[];
    /** Redirection targets and tee arguments (checked like edits). */
    writes: string[];
    /** Input redirections and cat/head/tail/less/more/sed file arguments (checked like reads). */
    reads: string[];
}
/**
 * Split a shell command into its simple commands (POSIX shell rules: `&&`, `||`,
 * `;`, `|`, `|&`, `&`, newlines; commands inside `$(…)`, backticks, `<(…)` and
 * `( … )` count as their own). Best effort, like Claude Code's: it reads text, it
 * does not run a shell.
 */
export declare function parseShellCommand(command: string): ParsedCommand;
/**
 * One canonical spelling for path matching: forward slashes, `.` and `..` folded, a
 * Windows drive written `/d/…` (Claude Code's form; Git Bash `/d/…`, WSL `/mnt/d/…`
 * and Cygwin `/cygdrive/d/…` spellings map to it when the working directory is a
 * Windows path), lower-cased on Windows. Relative paths resolve against `cwd`.
 */
export declare function canonicalPath(path: string, cwd: string | undefined, home?: string): string;
/**
 * The host a fetch of `url` goes to. The WHATWG URL parser where the engine has it (the
 * same one fetch uses: `\` as `/`, percent-decoding, numeric IPv4); a regex in bare
 * engines without URL.
 */
export declare function hostOf(url: string): string | undefined;
/** Merge rule lists (settings files and flags); evaluation is deny-first, so order only matters within a kind. */
export declare function mergePermissionRules(...sets: readonly PermissionRules[]): PermissionRules;
