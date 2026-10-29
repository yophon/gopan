import { type Platform, type TauAbortSignal } from "@yophon/tau-kernel";
import { type JsonRpcId } from "./jsonrpc.js";
import type { McpTransport } from "./transport.js";
/** Protocol version tau offers in `initialize` (pi 1.2 LATEST_PROTOCOL_VERSION). */
export declare const MCP_PROTOCOL_VERSION = "2025-11-25";
/** The stateless revision (P47): no handshake, a `_meta` envelope on every request. */
export declare const MCP_MODERN_PROTOCOL_VERSION = "2026-07-28";
/** "modern": 2026-07-28 and later; "legacy": the initialize handshake (2025-11-25 and earlier). */
export type McpProtocolEra = "modern" | "legacy";
/** "legacy" shakes hands as before; "auto" asks with server/discover first (SDK versionNegotiation). */
export type McpProtocolNegotiation = "auto" | "legacy";
/** Versions the server may select in `initialize`; any other closes the connection (pi). */
export declare const SUPPORTED_PROTOCOL_VERSIONS: readonly string[];
export interface McpToolInfo {
    name: string;
    description?: string;
    inputSchema: Record<string, unknown>;
    outputSchema?: Record<string, unknown>;
    annotations?: {
        readOnlyHint?: boolean;
        destructiveHint?: boolean;
        idempotentHint?: boolean;
        openWorldHint?: boolean;
    };
}
/** One item of a resources/read result, or the resource of an embedded resource block. */
export interface McpResourceContents {
    uri: string;
    mimeType?: string;
    text?: string;
    /** base64 */
    blob?: string;
    _meta?: Record<string, unknown>;
}
export interface McpContentBlock {
    type: string;
    text?: string;
    data?: string;
    mimeType?: string;
    resource?: Partial<McpResourceContents>;
    uri?: string;
    name?: string;
    title?: string;
    description?: string;
    size?: number;
}
export interface CallToolResult {
    content: McpContentBlock[];
    /** Any JSON value, including arrays and `null` (pi 1.2: MCP 2026-07-28; earlier revisions allowed only objects). */
    structuredContent?: unknown;
    isError?: boolean;
}
/** A resource a server lists (pi Resource; `name` falls back to the URI). */
export interface McpResource {
    uri: string;
    name: string;
    title?: string;
    description?: string;
    mimeType?: string;
    size?: number;
    _meta?: Record<string, unknown>;
    [key: string]: unknown;
}
/** A parameterized resource (RFC 6570 URI template; `name` falls back to the template). */
export interface McpResourceTemplate {
    uriTemplate: string;
    name: string;
    title?: string;
    description?: string;
    mimeType?: string;
    _meta?: Record<string, unknown>;
    [key: string]: unknown;
}
export interface McpReadResourceResult {
    contents: McpResourceContents[];
}
/** A root the client exposes to servers (roots/list). */
export interface McpRoot {
    uri: string;
    name?: string;
}
/** A prompt a server offers (prompts/list). */
export interface McpPrompt {
    name: string;
    title?: string;
    description?: string;
    arguments?: McpPromptArgument[];
    [key: string]: unknown;
}
export interface McpPromptArgument {
    name: string;
    description?: string;
    required?: boolean;
}
/** prompts/get: the messages the prompt expands to. */
export interface McpGetPromptResult {
    description?: string;
    messages: Array<{
        role: "user" | "assistant";
        content: McpContentBlock;
    }>;
}
/** A notifications/progress payload (pi ProgressNotification params). */
export interface McpProgress {
    progressToken: JsonRpcId;
    progress: number;
    total?: number;
    message?: string;
}
export interface InitializeResult {
    protocolVersion: string;
    capabilities: Record<string, unknown>;
    serverInfo?: {
        name: string;
        version?: string;
    };
    instructions?: string;
}
export interface McpClientOptions {
    name: string;
    version: string;
    /** Timers for request timeouts; without `sleep`, requests never time out. */
    platform?: Pick<Platform, "sleep">;
    /** Default timeout of every request, ms; undefined = none. */
    requestTimeoutMs?: number;
    /** Roots answered to roots/list; given, the roots capability is declared (pi). */
    roots?: readonly McpRoot[];
    /** Client capabilities declared in `initialize`, besides `roots` (pi), e.g. `elicitation`. */
    capabilities?: Record<string, unknown>;
    /** P47: "legacy" (default, as the official SDK) skips the probe; "auto" probes for 2026-07-28. */
    protocolNegotiation?: McpProtocolNegotiation;
    /** Rounds of input_required one call may take (default 10, as the official SDK). */
    maxInputRounds?: number;
}
/** subscriptions/listen: which change notifications to receive (MCP 2026-07-28). */
export interface McpSubscriptionFilter {
    toolsListChanged?: boolean;
    promptsListChanged?: boolean;
    resourcesListChanged?: boolean;
    resourceSubscriptions?: string[];
}
/** How a subscription ended: closed here, ended by the server, or dropped. */
export type McpSubscriptionEnd = "local" | "graceful" | "remote";
export interface McpSubscription {
    /** What the server agreed to send (notifications/subscriptions/acknowledged). */
    honored: McpSubscriptionFilter;
    /** Settles once, never rejects. */
    closed: Promise<McpSubscriptionEnd>;
    close(): void;
}
/** listen() failed before the acknowledgement: "graceful" (refused) or "remote" (dropped). */
export declare class McpSubscriptionError extends Error {
    readonly end: McpSubscriptionEnd;
    constructor(end: McpSubscriptionEnd);
}
export interface McpConnectOptions extends McpRequestOptions {
    /** The era an earlier connection to this server found: no probe ("modern" still asks server/discover). */
    era?: McpProtocolEra;
}
export interface McpRequestOptions {
    signal?: TauAbortSignal;
    timeoutMs?: number;
    /** Asks the server for progress (pi): each notification re-arms the timeout, then calls this. */
    onProgress?: (progress: McpProgress) => void;
}
type ClientState = "idle" | "connecting" | "connected" | "closed";
type NotificationListener = (params: unknown) => void;
/** Answers a server request; `signal` aborts when the server cancels it (notifications/cancelled). */
export type McpRequestHandler = (params: unknown, context: {
    signal: TauAbortSignal;
}) => unknown | Promise<unknown>;
export declare class McpClient {
    readonly options: Readonly<McpClientOptions>;
    private state;
    private transport;
    private nextRequestId;
    private instructionsValue;
    private protocolVersionValue;
    private serverCapabilitiesValue;
    private eraValue;
    private serverInfoValue;
    private eraRejectedValue;
    /** Requests given up on whose late answers are expected (the probe), dropped quietly. */
    private ignoredResponses;
    /** x-mcp-header parameters by tool, from the last tools/list (2026-07-28 over HTTP). */
    private paramHeaderMap;
    private excludedToolsValue;
    private pending;
    /** progressToken → request id of the requests that asked for progress. */
    private progressRequests;
    /** Server requests being answered, to abort on notifications/cancelled. */
    private incoming;
    private requestHandlers;
    private notificationListeners;
    private errorListeners;
    private closeListeners;
    private disposers;
    constructor(options: McpClientOptions);
    get connectionState(): ClientState;
    /** Server instructions from `initialize`, describing its tools as a group. */
    get instructions(): string | undefined;
    get protocolVersion(): string | undefined;
    /** What the server offers (`initialize`): `tools`, `resources`, `prompts`, … */
    get serverCapabilities(): Record<string, unknown> | undefined;
    /** The era connect() settled on (P47). */
    get protocolEra(): McpProtocolEra | undefined;
    /** The server's self-reported name and version (initialize, or the 2026-07-28 result `_meta`). */
    get serverInfo(): {
        name: string;
        version?: string;
    } | undefined;
    /** Tools the last tools/list left out because their x-mcp-header marks are invalid (spec). */
    get excludedTools(): ReadonlyArray<{
        name: string;
        reason: string;
    }>;
    /** A 2026-07-28 server stopped accepting the revision mid-connection: probe again next time. */
    get eraRejected(): boolean;
    connect(transport: McpTransport, options?: McpConnectOptions): Promise<InitializeResult>;
    /** The legacy handshake (pi). */
    private initialize;
    /**
     * server/discover with the 2026-07-28 envelope (P47). Resolves the connection for a modern
     * server and undefined for a legacy one (any reply that is not modern evidence; on stdio
     * also silence). `pinned` (a remembered "modern") turns a legacy answer into an error.
     */
    private discover;
    /** A modern server without 2026-07-28: shake hands when it lists a version tau speaks there. */
    private legacyFromSupported;
    /** Capabilities declared in initialize and in every 2026-07-28 envelope. */
    private clientCapabilities;
    request<Result = unknown>(method: string, params?: Record<string, unknown>, options?: McpRequestOptions): Promise<Result>;
    private send;
    /**
     * A 2026-07-28 request: reissued once under a new id when its HTTP stream broke (spec),
     * `resultType` checked (absent means complete). A -32022 means the server dropped the
     * revision: the connection closes and the next one probes again.
     */
    private requestModern;
    /** Answer each input request through the handler set for its method (MRTR). */
    private fulfilInputs;
    /**
     * One 2026-07-28 round trip: reissued once under a new id when its HTTP stream broke
     * (spec). A -32022 means the server dropped the revision: the connection closes and the
     * next one probes again.
     */
    private sendOnceModern;
    /**
     * subscriptions/listen (2026-07-28): resolves once the server acknowledges the stream.
     * Its notifications reach onNotification listeners like unsolicited 2025 ones.
     */
    listen(filter: McpSubscriptionFilter): Promise<McpSubscription>;
    notify(method: string, params?: Record<string, unknown>): Promise<void>;
    /** Answer server requests of `method` (pi); returns a disposer. */
    setRequestHandler(method: string, handler: McpRequestHandler): () => void;
    onNotification(method: string, listener: NotificationListener): () => void;
    onError(listener: (error: Error) => void): () => void;
    /** Called once when the connection closes, whether the transport dropped or `close()` was called. */
    onClose(listener: () => void): () => void;
    /** Every tool, following `nextCursor` through all pages. */
    listTools(options?: McpRequestOptions): Promise<McpToolInfo[]>;
    private listAllTools;
    /** Every resource, following `nextCursor` through all pages (pi). */
    listResources(options?: McpRequestOptions): Promise<McpResource[]>;
    /** One page of resources, starting at `cursor` (pi). */
    listResourcesPage(cursor?: string, options?: McpRequestOptions): Promise<{
        resources: McpResource[];
        nextCursor?: string;
    }>;
    /** Every resource template, following `nextCursor` through all pages (pi). */
    listResourceTemplates(options?: McpRequestOptions): Promise<McpResourceTemplate[]>;
    /** One page of resource templates, starting at `cursor` (pi). */
    listResourceTemplatesPage(cursor?: string, options?: McpRequestOptions): Promise<{
        resourceTemplates: McpResourceTemplate[];
        nextCursor?: string;
    }>;
    readResource(uri: string, options?: McpRequestOptions): Promise<McpReadResourceResult>;
    /** Every prompt, following `nextCursor` through all pages (P44). */
    listPrompts(options?: McpRequestOptions): Promise<McpPrompt[]>;
    getPrompt(name: string, args?: Record<string, string>, options?: McpRequestOptions): Promise<McpGetPromptResult>;
    private listPage;
    /** Every item of a paginated list method (pi listAll). */
    private listAll;
    callTool(name: string, args?: Record<string, unknown>, options?: McpRequestOptions): Promise<CallToolResult>;
    close(): Promise<void>;
    private requestInternal;
    /** (Re)start the request's timeout (pi armTimeout); a timeout cancels the request on the server too. */
    private armTimeout;
    /** Reject the request and, when `notifyServer`, tell the server with notifications/cancelled (pi). */
    private cancelPending;
    private notifyInternal;
    private requireTransport;
    private handleMessage;
    private handleResponse;
    /** Answer a server request through its handler, or method-not-found so the server never waits forever. */
    private handleRequest;
    private handleNotification;
    /** Progress for a request that asked for it: re-arm its timeout, then report (pi). */
    private handleProgress;
    private failPending;
    private removePending;
    /** Idempotent: rejects in-flight requests and flips the state. */
    private markClosed;
    private emitError;
}
export {};
