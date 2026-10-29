import { completeText, computeFileLists, estimateTokens, extractFileOpsFromMessage, formatFileOperations, SUMMARIZATION_SYSTEM_PROMPT, serializeConversation, } from "./compaction.js";
import { SessionError } from "./errors.js";
/** Output cap of a branch summary request (pi 0.85.0). */
export const BRANCH_SUMMARY_MAX_TOKENS = 4096;
/** Collect entries that should be summarized before navigating to a different session tree entry. */
export async function collectEntriesForBranchSummary(store, oldLeafId, targetId) {
    if (!oldLeafId) {
        return { entries: [], commonAncestorId: null };
    }
    const oldPath = new Set((await store.getPathToRoot(oldLeafId)).map((entry) => entry.id));
    const targetPath = await store.getPathToRoot(targetId);
    let commonAncestorId = null;
    for (let i = targetPath.length - 1; i >= 0; i--) {
        if (oldPath.has(targetPath[i].id)) {
            commonAncestorId = targetPath[i].id;
            break;
        }
    }
    const entries = [];
    let current = oldLeafId;
    while (current && current !== commonAncestorId) {
        const entry = await store.getEntry(current);
        if (!entry)
            throw new SessionError("invalid_session", `Entry ${current} not found`);
        entries.push(entry);
        current = entry.parentId;
    }
    entries.reverse();
    return { entries, commonAncestorId };
}
function getMessageFromEntry(entry) {
    switch (entry.type) {
        case "message":
            if (entry.message.role === "toolResult")
                return undefined;
            return entry.message;
        case "custom_message":
            return {
                role: "custom",
                customType: entry.customType,
                content: entry.content,
                display: entry.display,
                details: entry.details,
                timestamp: Date.parse(entry.timestamp) || 0,
            };
        case "branch_summary":
            return {
                role: "branchSummary",
                summary: entry.summary,
                fromId: entry.fromId,
                timestamp: Date.parse(entry.timestamp) || 0,
            };
        case "compaction":
            return {
                role: "compactionSummary",
                summary: entry.summary,
                tokensBefore: entry.tokensBefore,
                timestamp: Date.parse(entry.timestamp) || 0,
            };
        case "custom":
        case "session_info":
        case "leaf":
            return undefined;
    }
}
/** Prepare branch entries for summarization within an optional token budget. */
export function prepareBranchEntries(entries, tokenBudget = 0) {
    const messages = [];
    const fileOps = { read: new Set(), written: new Set(), edited: new Set() };
    let totalTokens = 0;
    // Nested branch summaries carry their branch's file lists; inherit them.
    for (const entry of entries) {
        if (entry.type === "branch_summary" && !entry.fromHook && entry.details) {
            const details = entry.details;
            if (Array.isArray(details.readFiles)) {
                for (const file of details.readFiles)
                    fileOps.read.add(file);
            }
            if (Array.isArray(details.modifiedFiles)) {
                for (const file of details.modifiedFiles)
                    fileOps.edited.add(file);
            }
        }
    }
    for (let i = entries.length - 1; i >= 0; i--) {
        const entry = entries[i];
        // Tool results stay out of the summary input, but the files their nested calls
        // touched (codemode scripts) still count. Deviation: pi skips them here too.
        if (entry.type === "message" && entry.message.role === "toolResult") {
            extractFileOpsFromMessage(entry.message, fileOps);
        }
        const message = getMessageFromEntry(entry);
        if (!message)
            continue;
        extractFileOpsFromMessage(message, fileOps);
        const tokens = estimateTokens(message);
        if (tokenBudget > 0 && totalTokens + tokens > tokenBudget) {
            // Over budget: still take a compaction/branch summary if we are under
            // 90% of the budget — it stands in for everything before it.
            if (entry.type === "compaction" || entry.type === "branch_summary") {
                if (totalTokens < tokenBudget * 0.9) {
                    messages.unshift(message);
                    totalTokens += tokens;
                }
            }
            break;
        }
        messages.unshift(message);
        totalTokens += tokens;
    }
    return { messages, fileOps, totalTokens };
}
/** Prefix prepended to generated branch summaries (verbatim from pi). */
export const BRANCH_SUMMARY_PREAMBLE = `The user explored a different conversation branch before returning here.
Summary of that exploration:

`;
const BRANCH_SUMMARY_PROMPT = `Create a structured summary of this conversation branch for context when returning later.

Use this EXACT format:

## Goal
[What was the user trying to accomplish in this branch?]

## Constraints & Preferences
- [Any constraints, preferences, or requirements mentioned]
- [Or "(none)" if none were mentioned]

## Progress
### Done
- [x] [Completed tasks/changes]

### In Progress
- [ ] [Work that was started but not finished]

### Blocked
- [Issues preventing progress, if any]

## Key Decisions
- **[Decision]**: [Brief rationale]

## Next Steps
1. [What should happen next to continue this work]

Keep each section concise. Preserve exact file paths, function names, and error messages.`;
/** Generate a summary for abandoned branch entries (pi's generateBranchSummary). */
export async function generateBranchSummary(transport, entries, options) {
    const reserveTokens = options?.reserveTokens ?? 16384;
    const contextWindow = options?.contextWindow || 128000;
    const tokenBudget = contextWindow - reserveTokens;
    const { messages, fileOps } = prepareBranchEntries(entries, tokenBudget);
    if (messages.length === 0) {
        return { summary: "No content to summarize", readFiles: [], modifiedFiles: [] };
    }
    const conversationText = serializeConversation(messages);
    let instructions;
    if (options?.replaceInstructions && options.customInstructions) {
        instructions = options.customInstructions;
    }
    else if (options?.customInstructions) {
        instructions = `${BRANCH_SUMMARY_PROMPT}\n\nAdditional focus: ${options.customInstructions}`;
    }
    else {
        instructions = BRANCH_SUMMARY_PROMPT;
    }
    const promptText = `<conversation>\n${conversationText}\n</conversation>\n\n${instructions}`;
    // The model's output limit when the host knows it (pi 1.4 drops the cap); otherwise 4096 as
    // in pi 0.85.0 (#8845: reasoning used up 2048 and left an empty summary).
    const generated = await completeText(transport, SUMMARIZATION_SYSTEM_PROMPT, promptText, options?.maxTokens ?? BRANCH_SUMMARY_MAX_TOKENS, options?.signal, { label: "Branch summarization", retry: options?.retry });
    let summary = BRANCH_SUMMARY_PREAMBLE + generated.text;
    const { readFiles, modifiedFiles } = computeFileLists(fileOps);
    summary += formatFileOperations(readFiles, modifiedFiles);
    return { summary: summary || "No summary generated", readFiles, modifiedFiles, usage: generated.usage };
}
