// Navigating the session tree (P50: split out of agent.ts; pi navigateTree): summarize the
// branch being left, move the leaf, record the branch summary, rebuild the conversation.
import { collectEntriesForBranchSummary, generateBranchSummary } from "./branch.js";
import { SessionError, TauError } from "./errors.js";
import { userContentText } from "./messages.js";
/** Agent.navigateTo's work, inside the operation it holds (abort cancels through `signal`). */
export async function runNavigation(host, entryId, signal, options) {
    await host.flushWrites();
    const store = host.session.store;
    const oldLeafId = await store.getLeafId();
    if (oldLeafId === entryId)
        return { cancelled: false };
    const targetEntry = await store.getEntry(entryId);
    if (!targetEntry)
        throw new SessionError("not_found", `Entry ${entryId} not found`);
    const ctx = host.extensionContext();
    const { entries, commonAncestorId } = await collectEntriesForBranchSummary(store, oldLeafId, entryId);
    const preparation = {
        targetId: entryId,
        oldLeafId,
        commonAncestorId,
        entriesToSummarize: entries,
        userWantsSummary: options?.summarize !== false,
        customInstructions: options?.customInstructions,
        ...(options?.replaceInstructions !== undefined ? { replaceInstructions: options.replaceInstructions } : {}),
        ...(options?.label !== undefined ? { label: options.label } : {}),
    };
    const hook = host.extensions ? await host.extensions.runSessionBeforeTree({ preparation, signal }, ctx) : {};
    if (hook.cancel || signal.aborted)
        return { cancelled: true };
    // pi: handlers may override the instructions, whether they replace the prompt, and the label.
    const replaceInstructions = hook.replaceInstructions ?? options?.replaceInstructions;
    const label = (hook.label ?? options?.label)?.trim();
    let summaryText = hook.summary?.summary;
    let summaryDetails = hook.summary?.details;
    let summaryUsage = hook.summary?.usage;
    if (!summaryText && options?.summarize !== false && entries.length > 0) {
        try {
            const generated = await generateBranchSummary(host.summaryTransport(), entries, {
                customInstructions: hook.customInstructions ?? options?.customInstructions,
                ...(replaceInstructions !== undefined ? { replaceInstructions } : {}),
                contextWindow: host.config.contextWindow,
                ...(host.summaryMaxTokens() !== undefined ? { maxTokens: host.summaryMaxTokens() } : {}),
                reserveTokens: host.branchSummaryReserveTokens ?? host.effectiveCompactionSettings().reserveTokens,
                signal,
                retry: host.summarizationRetry({ source: "branchSummary" }, options?.onEvent),
            });
            summaryText = generated.summary;
            summaryDetails = { readFiles: generated.readFiles, modifiedFiles: generated.modifiedFiles };
            summaryUsage = generated.usage;
        }
        catch (error) {
            // As in pi: an aborted summary generation cancels the navigation.
            if (error instanceof TauError && error.code === "aborted")
                return { cancelled: true };
            throw error;
        }
    }
    if (signal.aborted)
        return { cancelled: true };
    // pi: a user or custom message goes back to the editor; the leaf moves to its parent.
    let leafId = entryId;
    let editorText;
    if (targetEntry.type === "message" && targetEntry.message.role === "user") {
        leafId = targetEntry.parentId;
        editorText = userContentText(targetEntry.message.content);
    }
    else if (targetEntry.type === "custom_message") {
        leafId = targetEntry.parentId;
        editorText = userContentText(targetEntry.content);
    }
    const fromExtension = hook.summary !== undefined;
    const summaryEntry = await host.enqueueWrite(async () => {
        const entry = await host.session.moveTo(leafId, summaryText
            ? {
                summary: summaryText,
                details: summaryDetails,
                usage: summaryUsage && host.priced(summaryUsage),
                fromHook: fromExtension || undefined,
            }
            : undefined);
        // pi: the label goes on the summary entry, or on the target when there is no summary.
        if (label) {
            const labelled = summaryText ? entry?.id : entryId;
            if (labelled)
                await host.session.appendLabel(labelled, label);
        }
        await host.refresh();
        host.restoreToolsFromTranscript();
        return entry;
    });
    const newLeafId = await store.getLeafId();
    await host.extensions?.notifySessionTree({ newLeafId, oldLeafId, summaryEntry, fromExtension }, ctx);
    return editorText !== undefined ? { cancelled: false, editorText } : { cancelled: false };
}
