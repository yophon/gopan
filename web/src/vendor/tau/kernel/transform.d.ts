import type { AgentMessage } from "./messages.js";
/** Which model a request is going to; assistant turns from any other lineage are "foreign". */
export interface TransformTarget {
    api: string;
    provider: string;
    model: string;
    /** What the model accepts (pi Model.input); without "image", images become placeholder text. Absent = images pass. */
    input?: readonly ("text" | "image")[];
    /** Rewrite a foreign tool call id for this wire (pi normalizeToolCallId); results follow their call. */
    normalizeToolCallId?: (id: string) => string;
}
/**
 * Port of pi's transformMessages (packages/ai, v0.80.3 → v1.4.0 semantics) shared by
 * transports (the vision downgrade follows a host-declared `input`, tau has no model registry):
 *
 * - thinking from a different model (api/provider/model mismatch) is not replayable —
 *   its signature or field name belongs to another protocol — and is dropped: sent as
 *   text, the new model took it for part of its reply (pi 1.3); text and tool calls of a
 *   different model lose their signatures (pi);
 * - errored/aborted assistant turns are skipped (dangling tool calls, partial
 *   thinking) so the model resumes from the last valid state;
 * - every tool call gets a result: orphaned calls receive a synthetic
 *   "No result provided" error result before the next assistant turn.
 */
export declare function transformMessages(messages: AgentMessage[], target: TransformTarget): AgentMessage[];
