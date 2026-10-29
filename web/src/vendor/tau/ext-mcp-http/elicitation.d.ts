import { type TauAbortSignal, type UiCapability, type UiForm, type UiFormValue } from "@yophon/tau-kernel";
/** What the client declares: both modes (MCP 2025-11-25). */
export declare const ELICITATION_CAPABILITY: {
    readonly form: {};
    readonly url: {};
};
/** URLElicitationRequiredError (MCP 2025-11-25). */
export declare const URL_ELICITATION_REQUIRED = -32042;
export type McpElicitation = {
    mode: "form";
    server: string;
    message: string;
    requestedSchema: Record<string, unknown>;
    form: UiForm;
} | {
    mode: "url";
    server: string;
    message: string;
    url: string;
    /** 2025-11-25 only; a 2026-07-28 server learns the outcome when the call is retried. */
    elicitationId?: string;
};
export interface McpElicitResult {
    action: "accept" | "decline" | "cancel";
    content?: Record<string, UiFormValue>;
}
/** How the host answers an elicitation; `signal` aborts when the server cancels it. */
export type McpElicitHandler = (request: McpElicitation, context: {
    ui: UiCapability | undefined;
    signal: TauAbortSignal;
}) => Promise<McpElicitResult>;
/** A form schema (flat object of primitive properties) as a kernel form. */
export declare function elicitationForm(server: string, message: string, schema: Record<string, unknown>): UiForm;
/**
 * Check elicitation/create params (MCP 2025-11-25); invalid params throw McpError -32602.
 * `modern` (2026-07-28, inside input_required): URL requests carry no elicitationId.
 */
export declare function parseElicitation(server: string, params: unknown, options?: {
    modern?: boolean;
}): McpElicitation;
/** The URL elicitations an URLElicitationRequiredError carries (MCP 2025-11-25). */
export declare function requiredUrlElicitations(server: string, data: unknown): McpElicitation[];
/** About 8000 characters of plain URL (Claude Code). */
export declare const MAX_URL_ARGUMENT = 8000;
export declare function urlArgumentLength(url: string): number;
/** The host of a URL (for highlighting) and whether it looks spoofable (punycode). */
export declare function urlHost(url: string): {
    host: string;
    punycode: boolean;
};
/**
 * The dialog flow over UiCapability (MCP 2025-11-25 client requirements): the form
 * dialog (or field by field); for a URL, the full URL with its host, then consent
 * before `openUrl`. No UI = cancel.
 */
export declare function elicitWithUi(request: McpElicitation, ui: UiCapability | undefined, signal: TauAbortSignal, openUrl?: (url: string) => void): Promise<McpElicitResult>;
