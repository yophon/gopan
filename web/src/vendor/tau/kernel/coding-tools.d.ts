import type { BackgroundShell, FileSystem, ImageProcessor, Shell } from "./capabilities.js";
import type { PermissionMode } from "./policy.js";
import { type Tool } from "./tools.js";
/** Structured bash output for programmatic callers is capped at 1 MiB (pi STRUCTURED_OUTPUT_MAX_BYTES). */
export declare const BASH_STRUCTURED_OUTPUT_MAX_BYTES: number;
/**
 * Keep the first and last half of `maxBytes` (UTF-8) of `text`, cut at character
 * boundaries, with pi's `[... N bytes omitted ...]` marker between (pi readFullOutput).
 */
export declare function limitOutputBytes(text: string, maxBytes: number): {
    content: string;
    truncated: boolean;
};
export declare function mutationQueueKey(path: string, cwd?: string): string;
/** pi read's note for a model without image input. */
export declare const NON_VISION_IMAGE_NOTE = "[Current model does not support images. The image will be omitted from this request.]";
/** The image MIME type when the bytes start with that format's signature (pi detectSupportedImageMimeType). */
export declare function imageMimeType(bytes: Uint8Array): string | undefined;
/**
 * A path as the model wrote it, without a leading "@" (pi stripAtPrefix: the model
 * copies `@file` references) and without the quotes a terminal adds around a
 * dropped file with spaces ("C:\My Shots\a.png", a tau addition, P36b).
 */
export declare function cleanReadPath(path: string): string;
interface TextEdit {
    oldText: string;
    newText: string;
}
/**
 * Apply edits to the original content (pi 1.x): every oldText must match exactly
 * one place, edits must not overlap, and nothing is written unless all succeed.
 */
export declare function applyEdits(content: string, edits: readonly TextEdit[], path: string): string;
/** What bash returns for a command started in the background (Claude Code's wording). */
export declare function backgroundStartedText(id: string, outputPath: string): string;
/** What bash returns for a foreground command moved to the background (P39d, Claude Code's wording). */
export declare function backgroundMovedText(reason: "user" | "timeout", timeoutSeconds: number, id: string, outputPath: string): string;
/**
 * Build the default coding tool set from whatever capabilities the host
 * provides. Missing capabilities simply mean fewer tools — the kernel never
 * assumes a filesystem or shell exists. Under mode "read-only" the mutating
 * tools (write/edit/bash) are not registered at all, so they never reach the
 * request wire; the Agent's policy gate stays as the backstop for tools
 * registered by other paths.
 */
export declare function createCodingTools(capabilities: {
    fs?: FileSystem;
    shell?: Shell;
    background?: BackgroundShell;
    images?: ImageProcessor;
    /** P40b (pi's powershell tool): a shell that runs PowerShell, and its view of the background tasks. */
    powershell?: {
        shell: Shell;
        background?: BackgroundShell;
    };
}, options?: {
    mode?: PermissionMode;
    supportsImages?: () => boolean;
}): Tool[];
export {};
