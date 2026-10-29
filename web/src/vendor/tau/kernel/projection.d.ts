import { type AgentMessage, type SystemMessage, type ToolDefinition, type Usage } from "./messages.js";
import type { SessionEntry } from "./session.js";
/** Render a system message as a complete prompt: its content followed by its live sections. */
export declare function getSystemMessageText(message: SystemMessage): string;
/**
 * Render a later system message for endpoints that accept system messages
 * mid-conversation. Section changes are framed by name so the model can relate
 * them to the leading prompt. Request-time only.
 */
export declare function renderSystemMessageUpdate(message: SystemMessage): string;
/** Tools available after applying every transcript delta in order. */
export declare function getCurrentTools(messages: readonly {
    role: string;
}[]): ToolDefinition[];
/**
 * Replay every system message into one system message holding the current
 * prompt and tools: later `content` is appended, `sections` are patched by
 * name, tools are resolved with getCurrentTools.
 */
export declare function getCurrentSystemMessage(messages: readonly {
    role: string;
}[]): SystemMessage | undefined;
/** The current system prompt text after replaying every system message. */
export declare function getCurrentSystemPrompt(messages: readonly {
    role: string;
}[]): string;
/**
 * Rebuild a transcript for endpoints without mid-conversation system
 * messages: the replayed system message leads, every other system message is
 * dropped. Transports fold the head into their own prompt field.
 */
export declare function collapseSystemMessages(messages: readonly AgentMessage[]): AgentMessage[];
/** Strip a tool to what the model is told (name, description, parameters), JSON-cloned. */
export declare function toToolDeclaration(tool: ToolDefinition): ToolDefinition;
/** Whether two tools declare the same interface to the model (serialized declarations compared). */
export declare function declarationsEqual(left: ToolDefinition, right: ToolDefinition): boolean;
export interface ToolStateChanges {
    toolsAdded: ToolDefinition[];
    toolsRemoved: {
        name: string;
    }[];
}
/** Compare two complete tool states. A changed definition is a removal followed by an addition. */
export declare function getToolStateChanges(previous: readonly ToolDefinition[], current: readonly ToolDefinition[]): ToolStateChanges;
/** Patch turning `previous` sections into `current` (pi diffSystemPromptSections); undefined when equal. */
export declare function diffSystemPromptSections(previous: Record<string, string | null>, current: Record<string, string>): Record<string, string | null> | undefined;
/**
 * Normalize null/missing content at an ingestion boundary (pi 0.80.4): untyped
 * callers and old files can hand over messages without content.
 */
export declare function normalizeMessageContent(message: AgentMessage): AgentMessage;
/**
 * Messages one entry contributes to context. Session files are parsed without
 * validation, so null/missing content is normalized here (pi 0.80.4).
 */
export declare function sessionEntryToContextMessages(entry: SessionEntry): AgentMessage[];
/**
 * The compaction-aware entry list of a path (pi buildContextEntries): with a
 * compaction on the path, the latest compaction entry comes first, then the
 * kept entries from its firstKeptEntryId (minus system messages — the
 * compaction's checkpoint replaces them), then everything after it.
 */
export declare function buildContextEntries(path: readonly SessionEntry[]): SessionEntry[];
export interface ProjectedEntry {
    /** Raw append-only entry that owns this contribution. */
    sourceEntry: SessionEntry;
    /** Model-visible messages after context edits; empty for state-only entries and omissions. */
    messages: AgentMessage[];
}
export interface SessionProjection {
    entries: ProjectedEntry[];
    messages: AgentMessage[];
}
/**
 * Provenance-preserving, compaction-aware model context of a path (pi
 * buildSessionProjection). Context edits apply per branch: only edits on the
 * path count, and the latest edit of a target wins.
 */
export declare function buildSessionProjection(path: readonly SessionEntry[]): SessionProjection;
/**
 * Everything the session was billed for, folded over all entries of every
 * branch (pi getSessionStats / footer): assistant messages, tool results that
 * report usage (e.g. a subagent run), compaction and branch summaries, and
 * `usage` entries. Unlike the context, compaction never makes it smaller (D19).
 */
export declare function sumSessionUsage(entries: readonly SessionEntry[]): Usage;
