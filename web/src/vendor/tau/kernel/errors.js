export class TauError extends Error {
    code;
    constructor(code, message, cause) {
        super(message, cause === undefined ? undefined : { cause });
        this.name = "TauError";
        this.code = code;
    }
}
export class HttpError extends TauError {
    status;
    bodyText;
    constructor(status, bodyText) {
        super("http_error", `HTTP ${status}: ${bodyText.slice(0, 2000)}`);
        this.name = "HttpError";
        this.status = status;
        this.bodyText = bodyText;
    }
}
export class FileError extends Error {
    code;
    path;
    constructor(code, message, path, cause) {
        super(message, cause === undefined ? undefined : { cause });
        this.name = "FileError";
        this.code = code;
        this.path = path;
    }
}
export class SessionError extends Error {
    code;
    constructor(code, message, cause) {
        super(message, cause === undefined ? undefined : { cause });
        this.name = "SessionError";
        this.code = code;
    }
}
export class ShellError extends Error {
    code;
    constructor(code, message, cause) {
        super(message, cause === undefined ? undefined : { cause });
        this.name = "ShellError";
        this.code = code;
    }
}
export function toError(value) {
    return value instanceof Error ? value : new Error(String(value));
}
/**
 * An error's message with its innermost cause appended (P23d): undici reports a
 * refused local port as "fetch failed" and keeps ECONNREFUSED two causes deep,
 * which is all a user needs to see. Falls back to the cause's `code` when its
 * message is empty (AggregateError).
 */
export function describeError(value) {
    const outer = toError(value);
    let inner = outer;
    for (let depth = 0; depth < 8; depth++) {
        const next = inner?.cause;
        if (next === undefined || next === null || next === inner)
            break;
        inner = next;
    }
    if (inner === outer)
        return outer.message;
    const innerError = inner;
    const detail = typeof innerError.message === "string" && innerError.message !== ""
        ? innerError.message
        : typeof innerError.code === "string"
            ? innerError.code
            : String(inner);
    return detail === "" || outer.message.includes(detail) ? outer.message : `${outer.message} (${detail})`;
}
