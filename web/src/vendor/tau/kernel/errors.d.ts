export type TauErrorCode = "platform_missing" | "http_error" | "network_error" | "stream_error" | "timeout" | "aborted" | "max_turns" | "no_host" | "no_session" | "busy"
/** A seam was asked for something its implementation does not support (e.g. headers on a custom transport). */
 | "unsupported"
/** An extension handler cancelled the operation (e.g. session_before_compact). */
 | "cancelled" | "compaction_failed" | "invalid_response" | "invalid_tool"
/** An extension registered something malformed (a command without handler, a flag default of the wrong type). */
 | "invalid_extension"
/** A captured extension api/ctx was used after its registry was replaced or its setup failed. */
 | "stale_extension"
/** Kernel invariant broken (e.g. the request diverged from the session log, P22): a bug, not a runtime condition. */
 | "invalid_state";
export declare class TauError extends Error {
    readonly code: TauErrorCode;
    constructor(code: TauErrorCode, message: string, cause?: unknown);
}
export declare class HttpError extends TauError {
    readonly status: number;
    readonly bodyText: string;
    constructor(status: number, bodyText: string);
}
export type FileErrorCode = "not_found" | "permission_denied" | "is_directory" | "not_directory" | "invalid" | "unknown";
export declare class FileError extends Error {
    readonly code: FileErrorCode;
    readonly path?: string;
    constructor(code: FileErrorCode, message: string, path?: string, cause?: unknown);
}
export type SessionErrorCode = "invalid_session" | "invalid_entry" | "invalid_fork_target" | "not_found";
export declare class SessionError extends Error {
    readonly code: SessionErrorCode;
    constructor(code: SessionErrorCode, message: string, cause?: unknown);
}
export type ShellErrorCode = "shell_unavailable" | "spawn_error" | "timeout" | "aborted";
export declare class ShellError extends Error {
    readonly code: ShellErrorCode;
    constructor(code: ShellErrorCode, message: string, cause?: unknown);
}
export declare function toError(value: unknown): Error;
/**
 * An error's message with its innermost cause appended (P23d): undici reports a
 * refused local port as "fetch failed" and keeps ECONNREFUSED two causes deep,
 * which is all a user needs to see. Falls back to the cause's `code` when its
 * message is empty (AggregateError).
 */
export declare function describeError(value: unknown): string;
