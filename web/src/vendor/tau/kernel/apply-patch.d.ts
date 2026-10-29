import type { FileSystem } from "./capabilities.js";
export interface PatchChunk {
    /** A line (usually a class or function definition) the chunk comes after. */
    changeContext?: string;
    oldLines: string[];
    newLines: string[];
    /** Index pairs of lines parsed as context, so they keep their own endings. */
    contextLineIndices: Array<[number, number]>;
    /** `oldLines` must be at the end of the file. */
    isEndOfFile: boolean;
}
export type PatchHunk = {
    kind: "add";
    path: string;
    contents: string;
} | {
    kind: "delete";
    path: string;
} | {
    kind: "update";
    path: string;
    movePath?: string;
    chunks: PatchChunk[];
};
/** A patch that does not parse; the message is Codex's. */
export declare class PatchParseError extends Error {
}
/** Parse a patch into hunks (Codex's StreamingPatchParser, run over the whole text). */
export declare function parsePatch(patch: string): PatchHunk[];
/** The paths a patch touches (move targets too), for permission checks; none when it does not parse. */
export declare function patchTargets(patch: unknown): string[];
/**
 * Where `pattern` starts in `lines`, at or after `start`: exact, then ignoring trailing
 * whitespace, then ignoring both ends, then with punctuation normalised. With `eof`, the end
 * of the file is tried first.
 */
export declare function seekSequence(lines: readonly string[], pattern: readonly string[], start: number, eof: boolean): number | undefined;
/** The new contents of a file after its chunks. */
export declare function applyChunks(original: string, path: string, chunks: readonly PatchChunk[]): string;
export interface AppliedFile {
    kind: "add" | "update" | "delete";
    path: string;
    /** Where an update moved the file. */
    movePath?: string;
    /** The numbered diff hosts show (the edit tool's format). */
    diff: string;
}
export interface PatchResult {
    /** Codex's summary: "Success. Updated the following files:" then A/M/D lines. */
    summary: string;
    files: AppliedFile[];
}
/** A failure after some files were already written: they stay (Codex), and are named. */
export declare class PatchApplyError extends Error {
    readonly applied: readonly AppliedFile[];
    constructor(message: string, applied: readonly AppliedFile[]);
}
/** Parse and apply a patch through `fs`, file by file in order. */
export declare function applyPatch(fs: FileSystem, patch: string): Promise<PatchResult>;
