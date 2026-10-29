/**
 * The edit tool's display diff (P38b; pi core/tools/edit-diff.ts generateDiffString):
 * one line per change, `+`/`-`/` ` then the line number in the file, `...` where
 * unchanged lines are skipped, and the first changed line of the new file. pi diffs
 * the whole file with the `diff` package; the kernel has no dependencies, so it diffs
 * only the lines the edits touch (everything else is unchanged by construction) with
 * a small line LCS, which yields the same output for an edit call.
 */
/** A replacement at a known offset of the original content. */
export interface LocatedEdit {
    at: number;
    oldText: string;
    newText: string;
}
/** pi's generateDiffString for an edit call: numbered changes with up to `contextLines` around them. */
export declare function editDiff(content: string, edits: readonly LocatedEdit[], contextLines?: number): {
    diff: string;
    firstChangedLine: number | undefined;
};
