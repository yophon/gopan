import type { Extension, Platform, RiskLevel } from "@yophon/tau-kernel";
import { type McpProgress, type McpProtocolNegotiation, type McpRoot } from "./client.js";
import { type McpElicitHandler } from "./elicitation.js";
import { type McpExposureConfig } from "./exposure.js";
import { type McpAuth } from "./http.js";
import type { McpTransport } from "./transport.js";
/** wait_for_mcp_servers: how long it waits by default and at most (s; tau's numbers, Claude Code's are not public). */
export { WAIT_FOR_MCP_SERVERS_TOOL } from "./wait-tool.js";
export interface McpServerConfig extends McpExposureConfig {
    /** Server name: tools register as `mcp__<name>__<tool>`. Names differing only in `-`/`_` clash. */
    name: string;
    /** Streamable HTTP endpoint. */
    url?: string;
    /** Extra HTTP request headers, e.g. `{ Authorization: "Bearer <token>" }` (D24). */
    headers?: Record<string, string>;
    /** Command of a stdio server (Node hosts, @yophon/tau-ext-mcp). */
    command?: string;
    args?: string[];
    cwd?: string;
    env?: Record<string, string>;
    /** Stdio: start from the host's environment under `env` (default true); false passes `env` alone. */
    inheritEnv?: boolean;
    /** One-line summary for the namespace and the `mcp_servers` section; default: the server's instructions. */
    description?: string;
    /**
     * Timeout for initialize/tools/list (default 10s; requires Platform.sleep,
     * disabled without it). tools/call has no default timeout: long-running tools
     * are legitimate, and abort is the backstop.
     */
    timeoutMs?: number;
    /**
     * Let this server's tool annotations raise risk: `destructiveHint: true` makes a
     * tool high risk. Hints are the server's own claims, so they never lower risk
     * (D20); off by default — opt in per server you trust to label its tools.
     */
    riskFromAnnotations?: boolean;
    /**
     * Risk per tool, by the server's tool name with `*` wildcards, e.g. `{ "get_*": "low" }`
     * (P32b). The host's own declaration, unlike annotations, may lower risk, so a host
     * should take it only from the user's configuration. A destructive hint still wins.
     */
    toolRisk?: Record<string, RiskLevel>;
    /** OAuth (P32b): the host's token provider for an HTTP server. */
    auth?: McpAuth;
}
/** The risk `toolRisk` declares for a tool: the first matching pattern, exact names first. */
export declare function declaredToolRisk(toolRisk: Record<string, RiskLevel> | undefined, tool: string): RiskLevel | undefined;
export interface McpServerStatus {
    server: string;
    state: "connecting" | "connected" | "offline";
    /** Number of tools offered (state "connected" only). */
    toolCount?: number;
    /** The protocol version in use (state "connected" only; P47). */
    protocolVersion?: string;
    /** The server offers resources (state "connected" only; P43). */
    resources?: boolean;
    /** Failure description (state "offline" only). */
    error?: string;
}
export interface McpServersOptions {
    servers: McpServerConfig[];
    /**
     * Timers for request timeouts, connect retries and the startup wait (Platform.sleep);
     * createUtf8Decoder decodes text-typed binary resources (P43).
     */
    platform: Pick<Platform, "sleep"> & Partial<Pick<Platform, "createUtf8Decoder">>;
    /** A fresh transport for one connection attempt; throwing marks the server offline. */
    createTransport(config: McpServerConfig): McpTransport;
    clientInfo: {
        name: string;
        version: string;
    };
    onStatus?: (status: McpServerStatus) => void;
    /**
     * Where the full text of a truncated result went (pi output files), or why it is
     * not saved. Default: not saved — a pure-Platform client has no file access.
     */
    saveFullOutput?: (text: string) => string;
    /**
     * Save a binary resource (base64) where the model can read it and return the path;
     * throw when it cannot (P43, pi output files). Default: not saved.
     */
    saveBinary?: (base64: string, extension: string) => string;
    /** Roots answered to servers' roots/list, e.g. the working directory as a file URI (P43, pi). Default: none. */
    roots?: McpRoot[];
    /** Register the resource tools when a server offers resources (default true; P43). */
    resourceTools?: boolean;
    /** Pass MCP images to the model (default true; P43, pi). False keeps the placeholder text used before. */
    images?: boolean;
    /** Where an unavailable server is reported when the session has no UI to notify. Default: nowhere (onStatus only). */
    reportUnavailable?: (message: string) => void;
    /** How long the first prompt waits for servers with direct tools, ms (default 10000; needs Platform.sleep). */
    startupWaitMs?: number;
    /**
     * Answer servers' elicitation/create (P44): given, the client declares elicitation
     * (form and URL modes). elicitWithUi is the plain dialog flow. Default: not declared.
     */
    elicit?: McpElicitHandler;
    /** Register servers' prompts as /mcp__<server>__<prompt> commands (default true; P44, Claude Code). */
    promptCommands?: boolean;
    /** Complete and attach `@server:uri` resource mentions (default true; P44, Claude Code). */
    resourceMentions?: boolean;
    /**
     * Register wait_for_mcp_servers while servers are connecting in the background (default
     * true; P46, Claude Code's WaitForMcpServers).
     */
    waitTool?: boolean;
    /**
     * "auto" asks each server for MCP 2026-07-28 first and falls back to the initialize
     * handshake (P47, Claude Code); "legacy" (default, as the official SDK) only shakes hands.
     */
    protocolNegotiation?: McpProtocolNegotiation;
    /**
     * Hold a subscriptions/listen stream open to 2026-07-28 servers for list changes (default
     * true; P47, Claude Code). A host that keeps no idle connection (mobile) turns it off.
     */
    listChanged?: (config: McpServerConfig) => boolean;
}
export interface McpServersHandle {
    extension: Extension;
    /** Connect every server not connected yet; never rejects for server failures. */
    connect(): Promise<McpServerStatus[]>;
    /** Close every connection, including ones still connecting. */
    close(): Promise<void>;
}
/** Progress as a line of the tool's live output (P44). */
export declare function progressLine(progress: McpProgress): string;
export declare function createMcpServers(options: McpServersOptions): McpServersHandle;
