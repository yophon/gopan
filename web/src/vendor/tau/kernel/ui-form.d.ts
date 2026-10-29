/**
 * Forms (P44, tau): a flat list of fields a host shows in one dialog, first for MCP
 * elicitation's form mode. The field types are what that mode allows (MCP
 * 2025-11-25: strings with formats, numbers, booleans, single and multi select).
 * Hosts with a form dialog implement UiCapability.form; askFormStepByStep asks the
 * same fields one at a time through select/input for hosts without one. Pure ES.
 */
import type { UiCapability } from "./extensions.js";
import type { TauAbortSignal } from "./platform.js";
export interface UiFormOption {
    value: string;
    label: string;
}
export type UiFormValue = string | number | boolean | string[];
export interface UiFormField {
    name: string;
    title: string;
    description?: string;
    required: boolean;
    type: "string" | "number" | "integer" | "boolean" | "select" | "multiselect";
    /** The choices of `select` and `multiselect`. */
    options?: UiFormOption[];
    default?: UiFormValue;
    minLength?: number;
    maxLength?: number;
    pattern?: string;
    format?: "email" | "uri" | "date" | "date-time";
    minimum?: number;
    maximum?: number;
    minItems?: number;
    maxItems?: number;
}
export interface UiForm {
    /** Who asks, e.g. `MCP server "github"`. */
    title: string;
    message?: string;
    fields: UiFormField[];
}
/** Submitted values, or an explicit decline; a host returns undefined when the user cancels. */
export type UiFormResult = {
    action: "submit";
    values: Record<string, UiFormValue>;
} | {
    action: "decline";
};
/** Whether a value counts as filled in. */
export declare function hasFormValue(value: UiFormValue | undefined): value is UiFormValue;
/** Why `value` is not acceptable for the field, or undefined when it is. */
export declare function formFieldError(field: UiFormField, value: UiFormValue | undefined): string | undefined;
/** Errors by field name; empty when the form can be submitted. */
export declare function formErrors(form: UiForm, values: Record<string, UiFormValue | undefined>): Record<string, string>;
/** The values a form starts with: each field's default. */
export declare function initialFormValues(form: UiForm): Record<string, UiFormValue>;
/** Typed text for a string or number field: the value, or why it is not one. */
export declare function parseFormText(field: UiFormField, text: string): {
    value: UiFormValue | undefined;
} | {
    error: string;
};
/** How a value reads in a list of fields. */
export declare function formValueText(field: UiFormField, value: UiFormValue | undefined): string;
/** A field's prompt line: title, `*` when required, then its description. */
export declare function formFieldTitle(field: UiFormField): string;
/**
 * A form on a host without a form dialog: Fill in or Decline first, then each field
 * in turn (select, selectMany, input), asking again until the value is acceptable.
 * Cancelling any step cancels the form (undefined).
 */
export declare function askFormStepByStep(ui: UiCapability, form: UiForm, signal?: TauAbortSignal): Promise<UiFormResult | undefined>;
