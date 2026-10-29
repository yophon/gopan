/**
 * Permission and approval policy (P15). pi has no counterpart — it leaves
 * approval to extensions. tau ships a kernel-level gate because a
 * distributable kernel cannot treat safety as an optional install; concept
 * shapes follow yo-agent's PolicyEngine/assessRisk, rewritten pure-ES (D20).
 *
 * The static rules reduce accidents; they do not defend against a malicious
 * model or user. Defense in depth comes from the mode ladder, not from
 * pattern exhaustiveness.
 */
import { type PermissionRuleKind, type PermissionRules } from "./permission-rules.js";
/** How much the host trusts the agent. Kernel default: autonomous; interactive hosts should pick supervised. */
export type PermissionMode = "read-only" | "supervised" | "ci" | "accept-edits" | "autonomous" | "bypass";
export type RiskLevel = "low" | "medium" | "high";
export type PolicyAction = "allow" | "ask" | "deny";
export interface PolicyDecision {
    risk: RiskLevel;
    action: PolicyAction;
    /** The rule that fired; feeds approval prompts and deny tool results. */
    reason?: string;
    /** The permission rule that decided, when one did (P37a). */
    rule?: {
        kind: PermissionRuleKind;
        text: string;
        source?: string;
    };
    /** The built-in protected path a write touches; allow rules never pre-approve these (Claude Code). */
    protectedPath?: string;
}
/** A tool call as the policy sees it: name, (hook-rewritten) arguments, and any registration-time risk. */
export interface ToolPolicyCall {
    toolName: string;
    args: Record<string, unknown>;
    /**
     * Risk self-declared via registerTool(tool, { risk }). Consulted only for
     * tools without built-in rules — an extension overriding "bash" cannot
     * lower the built-in classification.
     */
    declaredRisk?: RiskLevel;
    /** The working directory edits are relative to (accept-edits allows edits inside it, P26d). */
    cwd?: string;
    /** The call being assessed, for hosts that record which rule decided it (P37a). */
    toolCallId?: string;
}
export interface ToolPolicy {
    assess(call: ToolPolicyCall, mode: PermissionMode): PolicyDecision;
    /** Whether a whole-tool deny rule takes this tool off the model's list (Claude Code). */
    deniesTool?(toolName: string): boolean;
}
/** Handed to AgentOptions.onApproval (and, by default, UiCapability.confirm) when a call needs approval. */
export interface ApprovalRequest {
    toolCallId: string;
    toolName: string;
    args: Record<string, unknown>;
    risk: RiskLevel;
    reason?: string;
    /** The ask rule that forced the approval, if one did (P37a). */
    rule?: {
        kind: PermissionRuleKind;
        text: string;
        source?: string;
    };
    /** The built-in protected path a write touches (no "always allow" for it). */
    protectedPath?: string;
    /** Set for a call another tool made (ctx.executeTool), e.g. from a codemode script. */
    parentToolCallId?: string;
    /** The subagent that made the call (AgentSpawnOptions.label, P26b). */
    subagent?: string;
    /** Made by a child that outlives the parent's run (AgentSpawnOptions.detached). */
    detached?: boolean;
}
/** The stricter of two permission modes (a subagent never outranks its parent). */
export declare function stricterMode(a: PermissionMode, b: PermissionMode | undefined): PermissionMode;
/**
 * mode × risk → action. read-only denies everything non-read, supervised asks
 * for medium and high, autonomous asks only for high, bypass allows all.
 * accept-edits is supervised here; the default policy additionally allows file
 * edits inside the working directory in it (P26d, yo-agent / Claude Code). ci (P53c,
 * yo-agent's unattended mode) is accept-edits whose asks are denied instead (the tool batch).
 */
export declare function resolvePolicyAction(mode: PermissionMode, risk: RiskLevel): PolicyAction;
/**
 * Built-in protected paths. Entries are segment sequences matched anywhere in
 * the target path ("~/" is stripped); a trailing "*" prefix-matches a segment.
 * ".env*" therefore hits ".env", ".env.local", and "config/.env.prod".
 */
export declare const DEFAULT_PROTECTED_PATHS: readonly string[];
/**
 * Whether a target path stays inside `cwd`: relative without escaping, or absolute
 * under it. Paths are compared in canonical form (P37a): `.`/`..` folded, and on
 * Windows the Git Bash, WSL and Cygwin spellings (`/d/…`, `/mnt/d/…`,
 * `/cygdrive/d/…`) read as the drive path, case-insensitively.
 */
export declare function isInsideCwd(path: string, cwd: string | undefined): boolean;
export interface DefaultPolicyOptions {
    /** Appended to DEFAULT_PROTECTED_PATHS. */
    protectedPaths?: string[];
    /** Appended to the built-in dangerous bash patterns. */
    dangerousPatterns?: RegExp[];
    /** Permission rules (P37a), read on every assess so edited settings apply from the next call. */
    rules?: () => PermissionRules;
    /** Extra roots that count as inside the working directory for accept-edits (P37a). */
    workingDirectories?: () => readonly string[];
    /** The home directory "~/" in rules resolves to. */
    home?: string;
}
/**
 * The default static policy: read tools are low; write/edit are medium unless
 * the target hits a protected path (high); bash is medium unless the command
 * matches a dangerous pattern (high); unknown tools take their declared risk,
 * defaulting to medium. Options append to (never replace) the built-in lists.
 *
 * Permission rules (P37a, Claude Code) come first: deny denies and ask asks in
 * every mode; allow allows in supervised, accept-edits and autonomous (dangerous
 * commands included), except writes to protected paths, and does not lift
 * read-only. Without a matching rule the mode × risk matrix decides.
 */
export declare function createDefaultPolicy(options?: DefaultPolicyOptions): ToolPolicy;
