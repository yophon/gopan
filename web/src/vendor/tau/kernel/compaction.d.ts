import { type AgentMessage, type AssistantMessage, type Usage } from "./messages.js";
import type { ChatTransport } from "./openai.js";
import type { TauAbortSignal } from "./platform.js";
import { type SessionProjection } from "./projection.js";
import { type RetryCallbacks, type RetrySettings } from "./retry.js";
import type { SessionEntry } from "./session.js";
/**
 * Context compaction, ported from pi 1.2.0, with pi 1.4's summary output limit (P48)
 * (packages/coding-agent/src/core/compaction/compaction.ts + utils.ts; the
 * harness copy lags behind it, P22): token estimation, cut-point selection over
 * the session projection, structured summarization prompts (verbatim),
 * iterative summary updates, split-turn handling, file-operation extraction,
 * summary usage. Deviations: results throw TauError instead of pi's Result
 * type; summarization runs over the agent's ChatTransport (P16) instead of pi's
 * model registry (D3), so summaries use the same model/protocol as the
 * conversation; the two split-turn calls run sequentially (pi used Promise.all
 * until 0.80.4).
 */
export interface CompactionSettings {
    /** Enable automatic compaction decisions. */
    enabled: boolean;
    /** Tokens reserved for summary prompt and output. */
    reserveTokens: number;
    /** Approximate recent-context tokens to keep after compaction. */
    keepRecentTokens: number;
}
export declare const DEFAULT_COMPACTION_SETTINGS: CompactionSettings;
export interface CompactionResult {
    /** Summary text that replaces compacted history in future context. */
    summary: string;
    /** Entry id where retained history starts. */
    firstKeptEntryId: string;
    /** Estimated context tokens before compaction. */
    tokensBefore: number;
    /** Usage of the summarization call(s) (pi 0.81.0). */
    usage?: Usage;
    details?: unknown;
}
export interface ContextUsageEstimate {
    tokens: number;
    usageTokens: number;
    trailingTokens: number;
    lastUsageIndex: number | null;
}
export declare function calculateContextTokens(usage: Usage): number;
/**
 * CJK-aware weighted token estimate. Deviation from pi's chars/4 heuristic (P14):
 * mainstream BPE tokenizers emit roughly one token per CJK character, so chars/4
 * undercounts Chinese/Japanese/Korean text 3-4x and delays compaction badly for
 * tau's primary audience. CJK-range characters count 1 token each; everything
 * else keeps pi's chars/4. Single pass, charCodeAt only (bare-engine friendly;
 * surrogate halves fall into the non-CJK bucket, which is fine for an estimate).
 */
export declare function estimateStringTokens(text: string): number;
/** Estimate token count for one message (weighted heuristic, see estimateStringTokens). */
export declare function estimateTokens(message: AgentMessage): number;
/** Estimate context tokens: last valid assistant usage + heuristic for trailing messages. */
export declare function estimateContextTokens(messages: readonly AgentMessage[]): ContextUsageEstimate;
/**
 * Estimate the projected context without trusting usage captured before a
 * later context edit or compaction on the branch (pi 1.2.0
 * estimateProjectedContextTokens): the kept tail of a compaction still carries
 * assistant usage from the pre-compaction context, and a /compact right below
 * the threshold would otherwise compact again on the next turn.
 */
export declare function estimateProjectedContextTokens(projection: SessionProjection, branchEntries: readonly SessionEntry[]): ContextUsageEstimate;
/**
 * The settings in effect for a context window. tau deviation: pi applies the
 * configured reserve/keep budgets as they are, which on a small window (local
 * 8K–32K endpoints, which tau recommends) means a summary request before every
 * turn that cannot free enough room. Both budgets are capped at a quarter of the
 * window; from 80K tokens up nothing changes.
 */
export declare function compactionSettingsForWindow(settings: CompactionSettings, contextWindow: number | undefined): CompactionSettings;
export declare function shouldCompact(contextTokens: number, contextWindow: number, settings: CompactionSettings): boolean;
/** Find the context-visible user-role message that starts the turn containing an entry. */
export declare function findTurnStartIndex(entries: readonly SessionEntry[], entryIndex: number, startIndex: number): number;
export interface CutPointResult {
    firstKeptEntryIndex: number;
    turnStartIndex: number;
    isSplitTurn: boolean;
}
/** Find the compaction cut point that keeps approximately the requested recent-token budget. */
export declare function findCutPoint(entries: readonly SessionEntry[], startIndex: number, endIndex: number, keepRecentTokens: number): CutPointResult;
export declare const SUMMARIZATION_SYSTEM_PROMPT = "You are a context summarization assistant. Your task is to read a conversation between a user and an AI assistant, then produce a structured summary following the exact format specified.\n\nDo NOT continue the conversation. Do NOT respond to any questions in the conversation. ONLY output the structured summary.";
/**
 * Serialize messages to plain text for summarization prompts. Thinking is
 * omitted (pi 1.2.0): providers return summaries of the real reasoning and
 * replayed thinking is often not part of the live context, so including it can
 * make the summary request larger than the context it summarizes. System
 * messages are prompt state, not conversation.
 */
export declare function serializeConversation(messages: readonly AgentMessage[]): string;
export interface FileOperations {
    read: Set<string>;
    written: Set<string>;
    edited: Set<string>;
}
/**
 * File operations of the tool calls in an assistant message, or of the nested calls
 * recorded on a tool result (pi: calls made from codemode scripts).
 */
export declare function extractFileOpsFromMessage(message: AgentMessage, fileOps: FileOperations): void;
export declare function computeFileLists(fileOps: FileOperations): {
    readFiles: string[];
    modifiedFiles: string[];
};
export declare function formatFileOperations(readFiles: string[], modifiedFiles: string[]): string;
export interface CompactionPreparation {
    firstKeptEntryId: string;
    messagesToSummarize: AgentMessage[];
    turnPrefixMessages: AgentMessage[];
    isSplitTurn: boolean;
    tokensBefore: number;
    previousSummary?: string;
    fileOps: FileOperations;
    settings: CompactionSettings;
}
/**
 * Prepare a path for compaction over its projection (pi 1.2.0
 * prepareCompaction); undefined when there is nothing to summarize — including
 * a tail whose kept budget already covers everything, which used to send an
 * empty summary request every turn (pi 0.86.0 guard).
 */
export declare function prepareCompaction(pathEntries: readonly SessionEntry[], settings: CompactionSettings): CompactionPreparation | undefined;
/**
 * Retry policy for summarization calls (pi completeSummarization): the agent's
 * RetrySettings, the host sleep (absent = no retry, D15) and progress callbacks.
 */
export interface SummarizationRetry {
    settings: RetrySettings;
    sleep?: (ms: number, signal?: TauAbortSignal) => Promise<void>;
    callbacks?: RetryCallbacks;
}
/**
 * Error text when a summarization response must not be persisted, verbatim from
 * pi 1.2.0: a length stop holds partial text and must not become a session
 * checkpoint — with a reasoning model it may hold no text at all.
 */
export declare function getSummarizationFailure(response: AssistantMessage, label: string): string | undefined;
/**
 * One summarization call (pi completeSummarization + getSummarizationFailure):
 * retried on transient errors, then judged. A truncated or failed summary throws
 * TauError("compaction_failed"); an abort throws TauError("aborted").
 */
export declare function completeText(transport: ChatTransport, systemPrompt: string, promptText: string, maxTokens: number, signal?: TauAbortSignal, options?: {
    label?: string;
    retry?: SummarizationRetry;
}): Promise<{
    text: string;
    usage: Usage;
}>;
/** Generate or update a conversation summary and return its usage (pi generateSummaryWithUsage). */
export declare function generateSummaryWithUsage(transport: ChatTransport, messages: readonly AgentMessage[], options: {
    reserveTokens: number;
    /** The model's output limit (pi 1.4: summaries are not capped below it); unknown keeps 0.8 × reserveTokens. */
    maxTokens?: number;
    customInstructions?: string;
    previousSummary?: string;
    signal?: TauAbortSignal;
    retry?: SummarizationRetry;
}): Promise<{
    text: string;
    usage: Usage;
}>;
/** Generate or update a conversation summary (pi's generateSummary). */
export declare function generateSummary(transport: ChatTransport, messages: readonly AgentMessage[], options: {
    reserveTokens: number;
    customInstructions?: string;
    previousSummary?: string;
    signal?: TauAbortSignal;
}): Promise<string>;
/**
 * Run a prepared compaction: summarize (split-turn aware, sequential calls) and
 * assemble the result. The usage of every summarization call is summed into
 * `usage` (pi 0.81.0, D19). Without history before a split turn, the previous
 * summary carries over instead of being replaced by "No prior history." (pi 0.86.0).
 */
export declare function runCompaction(transport: ChatTransport, preparation: CompactionPreparation, customInstructions?: string, signal?: TauAbortSignal, retry?: SummarizationRetry, 
/** The model's output limit for every summary request (pi 1.4); unknown keeps the reserveTokens shares. */
summaryMaxTokens?: number): Promise<CompactionResult>;
