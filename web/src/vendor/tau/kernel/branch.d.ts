import { type FileOperations, type SummarizationRetry } from "./compaction.js";
import type { AgentMessage, Usage } from "./messages.js";
import type { ChatTransport } from "./openai.js";
import type { TauAbortSignal } from "./platform.js";
import type { SessionEntry, SessionStore } from "./session.js";
/**
 * Branch summarization for session-tree navigation, ported from pi
 * (packages/agent/src/harness/compaction/branch-summarization.ts): collecting
 * the entries a navigation abandons, budgeted message selection with nested
 * summary handling, and the structured summary prompt (verbatim). Deviations:
 * errors throw (TauError/SessionError) instead of pi's Result type, and
 * summarization runs over the agent's ChatTransport (P16), as in compaction.ts.
 */
/** File-operation details stored on generated branch summary entries. */
export interface BranchSummaryDetails {
    /** Files read while exploring the summarized branch. */
    readFiles: string[];
    /** Files modified while exploring the summarized branch. */
    modifiedFiles: string[];
}
/** Prepared branch content for summarization. */
export interface BranchPreparation {
    /** Messages selected for the branch summary. */
    messages: AgentMessage[];
    /** File operations extracted from the branch. */
    fileOps: FileOperations;
    /** Estimated token count for selected messages. */
    totalTokens: number;
}
/** Entries selected for branch summarization. */
export interface CollectEntriesResult {
    /** Entries to summarize in chronological order. */
    entries: SessionEntry[];
    /** Deepest common ancestor between the previous leaf and target entry. */
    commonAncestorId: string | null;
}
export interface BranchSummaryResult {
    summary: string;
    readFiles: string[];
    modifiedFiles: string[];
    /** Usage of the summarization call (pi 0.81.0); absent when no call was made. */
    usage?: Usage;
}
/** Output cap of a branch summary request (pi 0.85.0). */
export declare const BRANCH_SUMMARY_MAX_TOKENS = 4096;
export interface GenerateBranchSummaryOptions {
    signal?: TauAbortSignal;
    /** Retry policy for the summary call (pi completeSummarization). */
    retry?: SummarizationRetry;
    /** Optional instructions appended to or replacing the default prompt. */
    customInstructions?: string;
    /** Replace the default prompt with custom instructions instead of appending them. */
    replaceInstructions?: boolean;
    /** Tokens reserved for prompt and model output. Defaults to 16384. */
    reserveTokens?: number;
    /** The model's output limit (pi 1.4); unknown keeps 4096. */
    maxTokens?: number;
    /**
     * Context window bounding the message-selection budget. Previously read off
     * the OpenAI config; explicit since the transport seam (P16). Defaults to
     * 128000, as before.
     */
    contextWindow?: number;
}
/** Collect entries that should be summarized before navigating to a different session tree entry. */
export declare function collectEntriesForBranchSummary(store: SessionStore, oldLeafId: string | null, targetId: string): Promise<CollectEntriesResult>;
/** Prepare branch entries for summarization within an optional token budget. */
export declare function prepareBranchEntries(entries: SessionEntry[], tokenBudget?: number): BranchPreparation;
/** Prefix prepended to generated branch summaries (verbatim from pi). */
export declare const BRANCH_SUMMARY_PREAMBLE = "The user explored a different conversation branch before returning here.\nSummary of that exploration:\n\n";
/** Generate a summary for abandoned branch entries (pi's generateBranchSummary). */
export declare function generateBranchSummary(transport: ChatTransport, entries: SessionEntry[], options?: GenerateBranchSummaryOptions): Promise<BranchSummaryResult>;
