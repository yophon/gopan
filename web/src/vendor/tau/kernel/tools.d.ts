import type { ExtensionToolContext } from "./extensions.js";
import type { ImageContent, JsonValue, TextContent, Usage } from "./messages.js";
import type { JsonSchema, ToolDefinition } from "./openai.js";
import type { TauAbortSignal } from "./platform.js";
import type { RiskLevel } from "./policy.js";
/**
 * What a tool returns — pi's AgentToolResult (P23b; was `{ output, isError }`).
 * `content` goes to the model as the tool result; `details` is kept on the
 * ToolResultMessage for logs and renderers, never sent.
 */
export interface ToolResult {
    content: (TextContent | ImageContent)[];
    /** Structured data for logs/UI. Deviation: pi types it as a required `details: T`; optional here. */
    details?: JsonValue;
    /** Report a failure without throwing; the model sees `content` as an error result. */
    isError?: boolean;
    /**
     * Hint that the agent should stop after the current tool batch. The run ends
     * early only when every finalized result of the batch sets it (pi).
     */
    terminate?: boolean;
    /** Usage of model calls the tool made itself (e.g. a subagent run); counted in session totals, not context (pi 0.81.0). */
    usage?: Usage;
    /**
     * Machine-readable result for tools that declare an `outputSchema` (pi 0.99.0). It
     * may come with an error result too. Never sent to the model: codemode scripts
     * receive it instead of the text content.
     */
    structuredContent?: JsonValue;
}
/**
 * How the model reaches a tool (pi ToolExposure). "Callable" means callable from
 * other tools through `ctx.executeTool()`, as the codemode tool does.
 *
 * - `direct`: declared to the model while active, and callable while active.
 * - `model-only`: declared to the model while active, never callable. For
 *   orchestrating or interactive tools.
 * - `codemode`: callable whenever registered. Not declared to the model unless
 *   explicitly activated. Codemode tools list it in their description.
 * - `deferred`: like `codemode`, but codemode tools do not list it; tool search can find it.
 * - `hidden`: registered but unreachable. Activating it has no effect.
 *
 * `direct` and `model-only` tools are activated when they are registered; the
 * others are not. The active tool set is the set declared to the model.
 */
export type ToolExposure = "direct" | "model-only" | "codemode" | "deferred" | "hidden";
/** A group of related tools, such as the tools of one MCP server (pi 1.2.0). Codemode tools list them together. */
export interface ToolNamespace {
    /** For example `mcp__docs`. */
    name: string;
    /** Other names tools look the namespace up by (codemode's describeNamespace()), for example `docs`. */
    aliases?: readonly string[];
    /** Short summary shown once with the group in model-facing tool listings. */
    description?: string;
    /** Longer usage guidance, such as MCP server instructions; returned on request, not listed. */
    instructions?: string;
}
/**
 * The tools of an agent as {@link Tool.prepareLoadout} sees them (pi ToolLoadout).
 * Deviation: entries are ToolInfo (no `execute`) instead of pi's AgentTool, so the
 * only way for a tool to run another is `ctx.executeTool()` and its gates (D20).
 */
export interface ToolLoadout {
    /** Tools declared to the model (the active tools), in order, with their original descriptions. */
    readonly declared: readonly ToolInfo[];
    /** Tools callable through `ctx.executeTool()`. */
    readonly callable: readonly ToolInfo[];
    /** Every registered tool. */
    readonly registered: readonly ToolInfo[];
    getExposure(name: string): ToolExposure;
    getNamespace(name: string): ToolNamespace | undefined;
    /** A tool's `promptGuidelines`. */
    getPromptGuidelines(name: string): readonly string[];
}
/** Changes {@link Tool.prepareLoadout} makes to what the model sees (pi ToolLoadoutChanges). */
export interface ToolLoadoutChanges {
    /** Model-facing descriptions of declared tools, by tool name. */
    descriptions?: Readonly<Record<string, string>>;
    /**
     * Declared tools whose declarations requests leave out. They stay active and
     * callable, and the transcript still declares them, so the active set survives
     * `/tree` and resume.
     */
    hiddenDeclarations?: readonly string[];
}
/** A tool without `execute`, as extensions see it: getAllTools(), ToolLoadout, ctx.tools (pi ToolInfo). */
export interface ToolInfo {
    name: string;
    description: string;
    parameters: JsonSchema;
    /** Deviation: pi's ToolInfo has no outputSchema; tau hands out ToolInfo where pi hands out AgentTool. */
    outputSchema?: JsonSchema;
    exposure: ToolExposure;
    namespace?: ToolNamespace;
    promptSnippet?: string;
    promptGuidelines?: readonly string[];
    reachesTools?: (input: Record<string, unknown>) => "all" | readonly string[];
    source: "builtin" | "extension";
}
export type ToolUpdateStream = "stdout" | "stderr";
/** Mirrors pi's ToolExecutionMode (agent/types.ts). */
export type ToolExecutionMode = "sequential" | "parallel";
export interface Tool extends ToolDefinition {
    /**
     * Self-declared risk consulted by the default policy for tools without
     * built-in rules (extension tools default to medium when absent). Built-in
     * tool names (read/write/edit/bash) are classified by the policy itself.
     */
    risk?: RiskLevel;
    /**
     * pi semantics: "sequential" means this tool must execute one at a time —
     * any such tool in a batch downgrades the whole batch to sequential
     * execution (pi agent-loop.ts dispatch). Absent = follows the agent's
     * toolExecution mode (default parallel). Built-in tools stay unmarked,
     * mirroring pi: write/edit rely on the per-file mutation queue instead.
     */
    executionMode?: ToolExecutionMode;
    /**
     * JSON Schema of `structuredContent` in successful results (pi 0.99.0). Tools that
     * declare it should always set `structuredContent`.
     */
    outputSchema?: JsonSchema;
    /** How the model reaches the tool. Default: `"direct"`. See {@link ToolExposure}. */
    exposure?: ToolExposure;
    /** Group the tool belongs to, for example its MCP server. */
    namespace?: ToolNamespace;
    /**
     * For tools that reach other tools (codemode calls them, tool_search loads them):
     * which tools a call with `input` may reach — `"all"`, or words of the input
     * that may name a tool or namespace. Extensions that register tools late (MCP
     * servers still connecting) use it from their tool_call handler.
     */
    reachesTools?: (input: Record<string, unknown>) => "all" | readonly string[];
    /**
     * Whether registering the tool activates it. Default: true for `direct` and
     * `model-only` tools; other exposures are never activated on registration.
     */
    defaultActive?: boolean;
    /**
     * Adjust how the loadout is presented to the model while this tool is active
     * (pi 0.99.0). Called whenever the request's tools are assembled; tools that
     * orchestrate others list the callable tools in their own description.
     */
    prepareLoadout?: (loadout: ToolLoadout) => ToolLoadoutChanges | undefined;
    /** One-line snippet for a host's tool list in the system prompt (pi). */
    promptSnippet?: string;
    /** Guideline bullets a host adds to the system prompt while the tool is declared (pi). */
    promptGuidelines?: string[];
    execute(args: Record<string, unknown>, signal?: TauAbortSignal, onUpdate?: (partialOutput: string, stream?: ToolUpdateStream) => void, ctx?: ExtensionToolContext): Promise<ToolResult>;
}
/** A tool's exposure; absent = `"direct"` (pi). */
export declare function toolExposure(tool: Pick<Tool, "exposure">): ToolExposure;
/** Whether activating the tool declares it to the model (pi _isDeclarable). */
export declare function isDeclarable(tool: Pick<Tool, "exposure">): boolean;
/** Whether registering the tool activates it (pi _isActivatedOnRegistration). */
export declare function isActivatedOnRegistration(tool: Pick<Tool, "exposure" | "defaultActive">): boolean;
/**
 * Apply the `+name` and `-name` entries of `entries` to `base` in order (pi applyToolModifiers):
 * `+name` adds a tool and `-name` removes one. Other entries are ignored.
 */
export declare function applyToolModifiers(base: readonly string[], entries: readonly string[]): string[];
/** Tool information without `execute` (a copy: extensions cannot mutate the registered tool through it). */
export declare function toToolInfo(tool: Tool, source: ToolInfo["source"]): ToolInfo;
/**
 * Keep the start and the end of `content`, about half of `maxBytes` (UTF-8) each,
 * and replace the middle with a `…N chars truncated…` marker — pi's
 * truncateMiddle (Codex's format). Cuts only at character boundaries.
 */
export declare function truncateMiddle(content: string, maxBytes: number): {
    content: string;
    truncated: boolean;
    removedChars: number;
    totalBytes: number;
    totalLines: number;
};
/**
 * Model-facing text held to `maxBytes`, in pi's limitMcpContent format: a
 * warning header, the middle-truncated text, then `where` (the full output's
 * location, or why there is none). Untruncated text comes back as is.
 */
export declare function limitToolText(text: string, maxBytes: number, where: (full: string) => string): string;
/** A plain text result. */
export declare function textResult(text: string, details?: JsonValue): ToolResult;
export declare function errorResult(message: string): ToolResult;
/** A result's content as text (images as a marker): for renderers and hosts that print results. */
export declare function toolResultText(result: Pick<ToolResult, "content">): string;
export declare function requireString(args: Record<string, unknown>, key: string): string;
export declare function optionalNumber(args: Record<string, unknown>, key: string): number | undefined;
/** True when the tool carries an object JSON Schema the wire and validator can use. */
export declare function hasToolSchema(tool: Pick<Tool, "parameters">): boolean;
/**
 * Reject a tool without an object `parameters` schema (pi 0.86.0 rejects
 * schemaless extension tools at registration). Without one, a model call to the
 * tool threw a TypeError out of prompt() and left an unpaired tool_call in the
 * session, so every later request was refused by the endpoint (HF-8).
 */
export declare function assertToolSchema(tool: Pick<Tool, "name" | "parameters">): void;
/**
 * Validate tool-call arguments against a JSON Schema subset: type (incl.
 * arrays of types), properties, required, enum, items, and the basic scalar
 * types. Unknown keywords are ignored and extra properties are permitted —
 * the goal is catching malformed model output so it can self-correct, not
 * full JSON Schema conformance. Returns problem descriptions; empty = valid.
 */
export declare function validateToolArgs(schema: JsonSchema, args: Record<string, unknown>): string[];
