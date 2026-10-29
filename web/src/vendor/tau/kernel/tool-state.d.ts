import type { AgentInternals } from "./agent-internals.js";
import type { AgentMessage } from "./messages.js";
import { type Tool, type ToolInfo } from "./tools.js";
/** What the tool set reads from its agent (live values; the agent passes getters). */
export type ToolStateHost = Pick<AgentInternals, "activePath" | "baseTools" | "extensions" | "policy">;
export declare class ToolState {
    private readonly host;
    constructor(host: ToolStateHost);
    /** The active tools by name: declared to the model (pi agent.state.tools). */
    private activeNames;
    /** Each registered tool as last seen, to activate new registrations (pi _refreshToolRegistry). */
    private seenTools;
    /** Active tools restored from the transcript that are not registered yet (pi _pendingToolNames). */
    pendingTools: Set<string>;
    /** Set once a host or extension chose the active tools; the initial transcript restore then yields to it. */
    private toolsChosen;
    /** `+name`/`-name` entries the first restore applies to the transcript's tools (pi defaultToolModifiers). */
    private defaultToolModifiers;
    /** Whether the initial transcript restore ran (setDefaultTools after it restores again). */
    private initialToolsRestored;
    /** Every registered tool: base tools, then extension tools (which override same-name base tools, as in pi). */
    currentTools(): Map<string, Tool>;
    /**
     * Take new registrations into the active set (pi _refreshToolRegistry): a tool
     * restored from the transcript becomes active once registered; a new tool that is
     * activated on registration joins; a re-registered tool keeps its state unless it
     * newly became activated on registration (e.g. from `hidden` back to `direct`).
     */
    private syncRegistrations;
    /**
     * The active tools (declared to the model, in registration order); `hidden` tools
     * never are (pi), nor tools a whole-tool deny rule removes (Claude Code, P37a).
     */
    activeTools(): Map<string, Tool>;
    /** Names of the active tools, the ones declared to the model (pi getActiveTools). */
    getActiveTools(): string[];
    private toolInfo;
    /** Every registered tool, active or not (pi getAllTools). */
    getAllTools(): ToolInfo[];
    /**
     * Tools callable through `ctx.executeTool()` (pi _getCallableTools): the active
     * `direct` tools and every registered `codemode` or `deferred` tool.
     */
    callableTools(active?: ReadonlySet<string>): Map<string, Tool>;
    /** Tools callable through `ctx.executeTool()`, without `execute`. */
    getCallableTools(): ToolInfo[];
    /**
     * Set the active tools by name (pi setActiveTools): unknown and `hidden` tools are
     * ignored, and the change applies from the next request. Only registered tools can
     * be activated, so a permission mode that never registered a tool keeps it out
     * (D20). Deactivating any tool drops the names still pending from the transcript;
     * a change that only adds tools (tool_search loading some) keeps them (pi).
     */
    setActiveTools(toolNames: readonly string[]): void;
    /**
     * Set the host's default tools (pi usesDefaultTools, 1.4 #10785): unlike setActiveTools, the
     * tools a resumed session recorded still win, with `modifiers` (`--tools +name`/`-name`)
     * applied to them. A session that recorded none keeps these.
     */
    setDefaultTools(toolNames: readonly string[], modifiers?: readonly string[]): void;
    private applyActiveTools;
    /**
     * Restore the active tools the transcript declares, if it declares any (pi 1.4
     * _restoreToolsFromTranscript). Names not registered yet stay pending until their
     * tool registers (MCP servers still connecting) or the next run starts.
     */
    restoreToolsFromTranscript(options?: {
        initial?: boolean;
    }): void;
    /**
     * The declarations a request carries (pi _applyToolLoadout): the active tools with
     * the descriptions their prepareLoadout hooks set, and the names whose declarations
     * requests leave out. Hooks run in registration order; one that throws is reported
     * and skipped.
     */
    computeLoadout(): {
        declared: Tool[];
        hidden: Set<string>;
    };
    /**
     * The tools the model sees in the next request: active, with loadout descriptions,
     * without hidden declarations. Hosts build their system prompt's tool list from it.
     */
    getDeclaredTools(): ToolInfo[];
}
/**
 * Leave the declarations prepareLoadout hooks hide out of a request (pi
 * _installHiddenDeclarationsProjection). The whole transcript is filtered with the
 * current set, so the declarations stay consistent across requests and only change
 * when the loadout does. The log keeps them: the active set survives /tree and resume.
 */
export declare function withoutHiddenDeclarations(messages: AgentMessage[], hidden: ReadonlySet<string>): AgentMessage[];
