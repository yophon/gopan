/**
 * Repair malformed JSON string literals: escape raw control characters inside
 * strings and double backslashes that precede an invalid escape character.
 */
export declare function repairJson(json: string): string;
/**
 * The complete arguments text of a finished tool call (pi 1.3 parseToolCallArguments): text that
 * is not a JSON object leaves `arguments` empty and `argumentsError` says why, with the text the
 * model sent, so validation reports it back instead of running the call on a partial parse.
 */
export declare function parseToolCallArguments(json: string | undefined): {
    arguments: Record<string, unknown>;
    argumentsError?: string;
};
/** JSON.parse, retried once on the repaired text when the original does not parse. */
export declare function parseJsonWithRepair<T>(json: string): T;
