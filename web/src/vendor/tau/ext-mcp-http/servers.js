import { AbortHandle } from "@yophon/tau-kernel";
import { McpClient, McpSubscriptionError, } from "./client.js";
import { ELICITATION_CAPABILITY, parseElicitation, requiredUrlElicitations, URL_ELICITATION_REQUIRED, } from "./elicitation.js";
import { configuredExposures, createMcpResultSchema, getMcpToolExposure, reachesServer, renderServersSection, toToolExposure, } from "./exposure.js";
import { McpHttpError, McpSessionExpiredError } from "./http.js";
import { McpAbortError, McpConnectionClosedError, McpError, McpProbeExitedError, McpTimeoutError } from "./jsonrpc.js";
import { createMentionProvider, findMentions, listMentionResources, mentionBlock, } from "./mentions.js";
import { createMcpToolName, mcpNamespace } from "./naming.js";
import { createPromptCommands } from "./prompts.js";
import { createMcpResourceTools, mcpResourceServer, RESOURCE_TOOL_NAMES } from "./resources.js";
import { convertMcpResult, limitMcpContent, toModelContent } from "./result.js";
import { aborted, createWaitTool } from "./wait-tool.js";
const DEFAULT_HANDSHAKE_TIMEOUT_MS = 10_000;
/** For calls made without a signal: an elicitation then waits for the user alone. */
const neverAborted = { aborted: false, addEventListener: () => { }, removeEventListener: () => { } };
const DEFAULT_STARTUP_WAIT_MS = 10_000;
/** wait_for_mcp_servers: how long it waits by default and at most (s; tau's numbers, Claude Code's are not public). */
export { WAIT_FOR_MCP_SERVERS_TOOL } from "./wait-tool.js";
/** Retries of a failed HTTP connect (pi CONNECT_RETRY_DELAYS_MS). */
const CONNECT_RETRY_DELAYS_MS = [250, 1_000];
/** Reopening a dropped subscriptions/listen stream (the GET stream's numbers, P47). */
const LISTEN_INITIAL_DELAY_MS = 1_000;
const LISTEN_MAX_DELAY_MS = 30_000;
const LISTEN_MAX_FAILURES = 5;
/** The risk `toolRisk` declares for a tool: the first matching pattern, exact names first. */
export function declaredToolRisk(toolRisk, tool) {
    if (!toolRisk)
        return undefined;
    if (toolRisk[tool])
        return toolRisk[tool];
    for (const [pattern, risk] of Object.entries(toolRisk)) {
        if (!pattern.includes("*"))
            continue;
        const source = pattern
            .split("*")
            .map((part) => part.replace(/[.+?^${}()|[\]\\]/g, "\\$&"))
            .join(".*");
        if (new RegExp(`^${source}$`).test(tool))
            return risk;
    }
    return undefined;
}
/** Progress as a line of the tool's live output (P44). */
export function progressLine(progress) {
    const amount = progress.total !== undefined ? `${progress.progress}/${progress.total}` : `${progress.progress}`;
    return `Progress ${amount}${progress.message ? ` · ${progress.message}` : ""}\n`;
}
function messageOf(cause) {
    return cause instanceof Error ? cause.message : String(cause);
}
/**
 * Network failures and overloaded or restarting servers, worth another connect
 * attempt (pi isTransientError). Errors the Platform's fetch throws count as
 * network failures; protocol, session, timeout and abort errors do not.
 */
function isTransientError(error) {
    if (error instanceof McpHttpError) {
        return error.status === 408 || error.status === 429 || (error.status >= 500 && error.status !== 501);
    }
    return !(error instanceof McpError ||
        error instanceof McpTimeoutError ||
        error instanceof McpAbortError ||
        error instanceof McpConnectionClosedError);
}
export function createMcpServers(options) {
    const states = [];
    for (const config of options.servers) {
        // Names that differ only in `-` and `_` would share a namespace (pi config.ts).
        const clash = states.find((state) => mcpNamespace(state.config.name) === mcpNamespace(config.name));
        states.push({
            config,
            tools: new Set(),
            hasResources: false,
            prompts: new Map(),
            activeCalls: [],
            openedUrls: new Set(),
            excluded: new Set(),
            ...(clash ? { conflict: `server "${config.name}" conflicts with "${clash.config.name}"` } : {}),
        });
    }
    /** Owner (`<server>\0<tool>`) of every tool name this extension registered. */
    const toolOwners = new Map();
    /** Last definition registered under each tool name, to re-register withdrawn tools as hidden. */
    const definitions = new Map();
    /** Clients of this generation, connected or still connecting, so shutdown closes them all. */
    const clients = new Set();
    let generation = 0;
    let api;
    /** The session_start context, to notify about unavailable servers. */
    let sessionCtx;
    let waitedForStartup = false;
    /** The last status of each server, for /mcp. */
    const lastStatus = new Map();
    const report = (status) => {
        lastStatus.set(status.server, status);
        options.onStatus?.(status);
        return status;
    };
    /** One /mcp line per server (pi formatStatus). */
    const describe = (state) => {
        const name = state.config.name;
        const transport = state.config.url ? "http" : "stdio";
        if (state.conflict)
            return `✗ ${name} (${transport}) · offline: ${state.conflict}`;
        if (state.connecting)
            return `◐ ${name} (${transport}) · connecting`;
        if (state.client?.connectionState === "connected") {
            const resources = state.hasResources ? " · resources" : "";
            const version = state.client.protocolVersion ? ` · MCP ${state.client.protocolVersion}` : "";
            return `● ${name} (${transport}) · connected${version} · ${state.tools.size} tool${state.tools.size === 1 ? "" : "s"}${resources}`;
        }
        const last = lastStatus.get(name);
        return last?.state === "offline"
            ? `✗ ${name} (${transport}) · offline: ${last.error ?? "unknown error"}`
            : `○ ${name} (${transport}) · not connected`;
    };
    const reportUnavailable = (server, error) => {
        const message = `MCP server "${server}" unavailable: ${error}`;
        if (sessionCtx?.ui)
            sessionCtx.ui.notify(message, "warning");
        else
            options.reportUnavailable?.(message);
    };
    const namespaceOf = (state) => {
        const description = state.config.description?.trim();
        return {
            name: mcpNamespace(state.config.name),
            // describeNamespace("docs") finds mcp__docs.
            aliases: [state.config.name],
            ...(description ? { description } : {}),
            ...(state.instructions ? { instructions: state.instructions } : {}),
        };
    };
    /** Open one connection (and list its tools); the client is tracked until closed. */
    const openOnce = async (state, list, era = state.era) => {
        const client = new McpClient({
            ...options.clientInfo,
            platform: options.platform,
            ...(options.roots ? { roots: options.roots } : {}),
            ...(options.elicit ? { capabilities: { elicitation: ELICITATION_CAPABILITY } } : {}),
            ...(options.protocolNegotiation ? { protocolNegotiation: options.protocolNegotiation } : {}),
        });
        clients.add(client);
        client.onClose(() => {
            clients.delete(client);
            if (state.client === client)
                state.client = undefined;
            // The server dropped 2026-07-28 mid-connection: probe again next time.
            if (client.eraRejected)
                state.era = undefined;
        });
        if (options.elicit) {
            client.setRequestHandler("elicitation/create", (params, { signal }) => elicit(state, params, signal, client.protocolEra === "modern"));
            client.onNotification("notifications/elicitation/complete", (params) => {
                const id = typeof params === "object" && params !== null
                    ? params.elicitationId
                    : undefined;
                // Unknown or already completed ids are ignored (MCP).
                if (typeof id !== "string" || !state.openedUrls.delete(id))
                    return;
                uiFor(state)?.notify(`MCP server "${state.config.name}": the step in the browser is done.`, "info");
            });
        }
        try {
            const timeoutMs = state.config.timeoutMs ?? DEFAULT_HANDSHAKE_TIMEOUT_MS;
            await client.connect(options.createTransport(state.config), { timeoutMs, ...(era ? { era } : {}) });
            state.era = client.protocolEra;
            client.onNotification("notifications/tools/list_changed", () => {
                void refreshTools(state, client);
            });
            client.onNotification("notifications/prompts/list_changed", () => {
                void promptCommands.refresh(state, client);
            });
            client.onNotification("notifications/resources/list_changed", () => {
                state.resourceList = undefined;
            });
            state.instructions = client.instructions;
            state.hasResources = client.serverCapabilities?.resources !== undefined;
            // Servers without the tools capability (resources or prompts only) do not answer tools/list (pi).
            const tools = list && client.serverCapabilities?.tools !== undefined ? await client.listTools({ timeoutMs }) : [];
            reportExcluded(state, client);
            if (client.protocolEra === "modern" && (options.listChanged?.(state.config) ?? true)) {
                void keepListening(state, client);
            }
            return { client, tools };
        }
        catch (error) {
            await client.close().catch(() => { });
            // A remembered era that no longer holds is probed for again (spec).
            state.era = undefined;
            throw error;
        }
    };
    /** Open a connection; HTTP servers get two more tries on network or server errors (pi). */
    const openClient = async (state, list) => {
        const retries = state.config.url && options.platform.sleep ? CONNECT_RETRY_DELAYS_MS : [];
        const startedIn = generation;
        for (let attempt = 0;; attempt++) {
            try {
                return await openOnce(state, list).catch((error) => {
                    // The probe knocked a stdio server over: a fresh process on the legacy handshake.
                    if (error instanceof McpProbeExitedError)
                        return openOnce(state, list, "legacy");
                    throw error;
                });
            }
            catch (error) {
                const delay = retries[attempt];
                if (delay === undefined || startedIn !== generation || !isTransientError(error))
                    throw error;
                await options.platform.sleep?.(delay);
            }
        }
    };
    /** Current client of the server, reconnecting when it dropped (a call is the retry trigger). */
    const clientFor = async (state) => {
        if (state.client?.connectionState === "connected")
            return state.client;
        const { client } = await openClient(state, false);
        state.client = client;
        return client;
    };
    const refreshTools = async (state, client) => {
        // A notification can arrive before the connect that opened this client finished.
        await state.connecting;
        if (state.client !== client || !api)
            return;
        try {
            if (client.serverCapabilities?.tools === undefined)
                return;
            const tools = await client.listTools({ timeoutMs: state.config.timeoutMs ?? DEFAULT_HANDSHAKE_TIMEOUT_MS });
            reportExcluded(state, client);
            if (state.client === client)
                registerTools(api, state, tools);
        }
        catch {
            // A failed refresh keeps the tools registered so far.
        }
    };
    /** The UI to ask through for this server: its latest tool call's, else the session's (P44). */
    const uiFor = (state) => state.activeCalls[state.activeCalls.length - 1]?.ui ?? sessionCtx?.ui;
    /** elicitation/create: check the request, then let the host ask (P44). */
    const elicit = async (state, params, signal, modern = false) => {
        const request = parseElicitation(state.config.name, params, { modern });
        const handler = options.elicit;
        if (!handler)
            return { action: "cancel" };
        const result = await handler(request, { ui: uiFor(state), signal });
        if (request.mode === "url" && result.action === "accept" && request.elicitationId) {
            state.openedUrls.add(request.elicitationId);
        }
        return result.action === "accept" && request.mode === "form" ? result : { action: result.action };
    };
    /** Tools left out for invalid x-mcp-header marks, reported once each (spec: log a warning). */
    const reportExcluded = (state, client) => {
        for (const { name, reason } of client.excludedTools) {
            if (state.excluded.has(name))
                continue;
            state.excluded.add(name);
            const message = `MCP server "${state.config.name}": tool "${name}" left out: ${reason}`;
            if (sessionCtx?.ui)
                sessionCtx.ui.notify(message, "warning");
            else
                options.reportUnavailable?.(message);
        }
    };
    /**
     * A 2026-07-28 server's list changes come on a subscriptions/listen stream: opened for the
     * lists it says change, reopened when it drops (Claude Code) with the GET stream's backoff
     * (1 s doubling to 30 s, 5 failures in a row); not when the server ended it or never
     * acknowledged it (the Inspector).
     */
    const keepListening = async (state, client) => {
        const capabilities = client.serverCapabilities ?? {};
        const changes = (key) => {
            const capability = capabilities[key];
            return (typeof capability === "object" &&
                capability !== null &&
                capability.listChanged === true);
        };
        const filter = {
            ...(changes("tools") ? { toolsListChanged: true } : {}),
            ...(changes("prompts") ? { promptsListChanged: true } : {}),
            ...(changes("resources") ? { resourcesListChanged: true } : {}),
        };
        if (Object.keys(filter).length === 0)
            return;
        const sleep = options.platform.sleep;
        for (let failures = 0; client.connectionState === "connected";) {
            let end;
            try {
                const subscription = await client.listen(filter);
                failures = 0;
                end = await subscription.closed;
            }
            catch (error) {
                end = error instanceof McpSubscriptionError ? error.end : "remote";
            }
            if (end !== "remote" || client.connectionState !== "connected")
                return;
            if (failures >= LISTEN_MAX_FAILURES || !sleep) {
                uiFor(state)?.notify(`MCP server "${state.config.name}": the change notification stream dropped`, "warning");
                return;
            }
            await sleep(Math.min(LISTEN_INITIAL_DELAY_MS * 2 ** failures++, LISTEN_MAX_DELAY_MS));
        }
    };
    /** The servers' prompts as commands (P44; prompts.ts). */
    const promptCommands = createPromptCommands({
        api: () => api,
        get enabled() {
            return options.promptCommands !== false;
        },
        timeoutMs: DEFAULT_HANDSHAKE_TIMEOUT_MS,
        withClient: (state, call) => withClient(state, call),
        contentOptions: (state) => contentOptions(state),
    });
    /** Resources of every connected server with resources, for `@` completion (listed once, cached). */
    const mentionResources = () => listMentionResources(mentionStates(), (state) => withClient(state, (client) => client.listResources({ timeoutMs: state.config.timeoutMs ?? DEFAULT_HANDSHAKE_TIMEOUT_MS })));
    /** Connected servers whose resources `@` reaches: resources offered, not hidden. */
    const mentionStates = () => resourceStates().filter((state) => state.client?.connectionState === "connected");
    /** Activate the tool that reaches indirect MCP tools: codemode, or tool_search for deferred ones (pi). */
    const activateReachingTools = (target, state) => {
        const exposures = configuredExposures(state.config);
        if (!exposures.has("codemode") && !exposures.has("deferred"))
            return;
        const registered = new Set(target.getAllTools().map((tool) => tool.name));
        const active = target.getActiveTools();
        const wanted = [
            ...(exposures.has("codemode") && registered.has("codemode") ? ["codemode"] : []),
            ...(exposures.has("deferred") && registered.has("tool_search") ? ["tool_search"] : []),
        ].filter((name) => !active.includes(name));
        if (wanted.length > 0)
            target.setActiveTools([...active, ...wanted]);
    };
    const registerTools = (target, state, tools) => {
        const server = state.config.name;
        const namespace = namespaceOf(state);
        const previous = state.tools;
        const current = new Set();
        // Like Codex, all tools whose names sanitize to the same name get the hash suffix, so
        // which one would keep the plain name does not depend on the order of the list (pi).
        const plain = [...new Set(tools.map((tool) => tool.name))].map((tool) => createMcpToolName(server, tool));
        for (const info of tools) {
            const owner = `${server}\0${info.name}`;
            const name = createMcpToolName(server, info.name, (candidate) => {
                const existing = toolOwners.get(candidate);
                return ((existing !== undefined && existing !== owner) ||
                    current.has(candidate) ||
                    plain.indexOf(candidate) !== plain.lastIndexOf(candidate));
            });
            current.add(name);
            toolOwners.set(name, owner);
            const definition = createTauTool(state, name, info, namespace);
            definitions.set(name, definition);
            const destructive = state.config.riskFromAnnotations === true && info.annotations?.destructiveHint === true;
            const declared = declaredToolRisk(state.config.toolRisk, info.name);
            const risk = destructive ? "high" : declared;
            target.registerTool(definition, risk ? { risk } : undefined);
        }
        state.tools = current;
        // Tools cannot be unregistered: tools the server dropped become hidden. Offered again,
        // they are registered with their configured exposure above (pi).
        for (const name of previous) {
            const definition = definitions.get(name);
            if (!current.has(name) && definition)
                target.registerTool({ ...definition, exposure: "hidden" });
        }
        activateReachingTools(target, state);
        syncResourceTools(target);
    };
    /** How a server's content reaches the model (P43). */
    const contentOptions = (state) => ({
        server: state.config.name,
        readableResources: resourceStates().includes(state),
        ...(options.images === false ? { images: false } : {}),
        ...(options.saveBinary ? { saveBinary: options.saveBinary } : {}),
        ...(options.platform.createUtf8Decoder ? { createUtf8Decoder: options.platform.createUtf8Decoder } : {}),
    });
    const where = options.saveFullOutput ?? (() => "[Full output not saved: this MCP client has no file access]");
    /** Run `call` on the server's client; a lost session reconnects once and replays (LAN servers restart routinely). */
    const withClient = async (state, call) => {
        try {
            return await call(await clientFor(state));
        }
        catch (cause) {
            if (!(cause instanceof McpSessionExpiredError))
                throw cause;
            await state.client?.close();
            return call(await clientFor(state));
        }
    };
    /** Servers with resources whose exposure is not `hidden`, which the resource tools reach (pi). */
    const resourceStates = () => states.filter((state) => state.hasResources && !state.conflict && (state.config.exposure ?? "direct") !== "hidden");
    /** Exposure the resource tools were last registered with; undefined until a server has resources. */
    let resourceToolsExposure;
    /**
     * Register the resource tools with the widest exposure of the servers they reach:
     * `direct` when one of them is direct, and so on; hidden when none has resources (pi).
     */
    const syncResourceTools = (target) => {
        if (options.resourceTools === false)
            return;
        const exposures = new Set(resourceStates().map((state) => state.config.exposure ?? "direct"));
        const next = ["direct", "codemode", "deferred"].find((candidate) => exposures.has(candidate)) ?? "hidden";
        if (next === resourceToolsExposure || (resourceToolsExposure === undefined && next === "hidden"))
            return;
        const wasDirect = resourceToolsExposure === "direct";
        resourceToolsExposure = next;
        const tools = createMcpResourceTools({
            exposure: toToolExposure(next),
            servers: () => resourceStates().map((state) => mcpResourceServer(state.config.name, state.config.timeoutMs ?? DEFAULT_HANDSHAKE_TIMEOUT_MS, (call) => withClient(state, call))),
            content: {
                ...(options.images === false ? { images: false } : {}),
                ...(options.saveBinary ? { saveBinary: options.saveBinary } : {}),
                ...(options.platform.createUtf8Decoder ? { createUtf8Decoder: options.platform.createUtf8Decoder } : {}),
            },
            where,
        });
        // Read only, like reading a file (pi readOnlyHint).
        for (const tool of tools)
            target.registerTool(tool, { risk: "low" });
        if (wasDirect)
            target.setActiveTools(target.getActiveTools().filter((name) => !RESOURCE_TOOL_NAMES.has(name)));
    };
    const createTauTool = (state, name, info, namespace) => ({
        name,
        description: info.description ?? `MCP tool ${info.name} from ${state.config.name}`,
        parameters: info.inputSchema,
        outputSchema: createMcpResultSchema(info.outputSchema),
        exposure: toToolExposure(getMcpToolExposure(state.config, info.name)),
        namespace,
        execute: async (args, signal, onUpdate, ctx) => {
            if (signal?.aborted)
                return { content: [{ type: "text", text: "MCP tool call aborted" }], isError: true };
            if (!state.tools.has(name)) {
                return {
                    content: [{ type: "text", text: `MCP tool "${state.config.name}/${info.name}" is no longer available.` }],
                    isError: true,
                };
            }
            if (ctx)
                state.activeCalls.push(ctx);
            try {
                const result = await withClient(state, (client) => client.callTool(info.name, args, {
                    ...(signal ? { signal } : {}),
                    ...(onUpdate ? { onProgress: (progress) => onUpdate(progressLine(progress)) } : {}),
                }));
                const content = limitMcpContent(convertMcpResult(result, info.name, contentOptions(state)), where);
                // Scripts get the whole CallToolResult (pi 1.2.0), never truncated; `_meta` is dropped.
                const { _meta: _ignored, ...scriptResult } = result;
                return {
                    content,
                    // The server's structuredContent is plain JSON.
                    structuredContent: scriptResult,
                    isError: result.isError === true,
                };
            }
            catch (cause) {
                if (cause instanceof McpError && cause.code === URL_ELICITATION_REQUIRED) {
                    return { content: [{ type: "text", text: await urlRequired(state, cause, signal) }], isError: true };
                }
                const text = cause instanceof McpAbortError ? "MCP tool call aborted" : messageOf(cause);
                return { content: [{ type: "text", text }], isError: true };
            }
            finally {
                if (ctx)
                    state.activeCalls.splice(state.activeCalls.lastIndexOf(ctx), 1);
            }
        },
    });
    /**
     * URLElicitationRequiredError (MCP 2025-11-25): show each link it carries, then tell
     * the model what happened so it retries once the user has finished in the browser.
     */
    const urlRequired = async (state, error, signal) => {
        const server = state.config.name;
        const requests = requiredUrlElicitations(server, error.data);
        if (requests.length === 0 || !options.elicit)
            return `MCP server "${server}": ${error.message}`;
        const lines = [`MCP server "${server}" needs the user to finish a step in the browser first: ${error.message}`];
        for (const request of requests) {
            const result = await options.elicit(request, { ui: uiFor(state), signal: signal ?? neverAborted });
            if (request.mode === "url" && result.action === "accept" && request.elicitationId) {
                state.openedUrls.add(request.elicitationId);
            }
            const outcome = result.action === "accept"
                ? "the user opened the link"
                : result.action === "decline"
                    ? "the user declined"
                    : "the user dismissed it";
            lines.push(`- ${request.message} (${request.mode === "url" ? request.url : ""}): ${outcome}`);
        }
        lines.push("Retry the tool once the user has finished.");
        return lines.join("\n");
    };
    const connectOne = (state) => {
        const server = state.config.name;
        if (state.conflict)
            return Promise.resolve(report({ server, state: "offline", error: state.conflict }));
        if (state.client?.connectionState === "connected") {
            return Promise.resolve({ server, state: "connected", toolCount: state.tools.size });
        }
        if (state.connecting)
            return state.connecting;
        const startedIn = generation;
        report({ server, state: "connecting" });
        const attempt = (async () => {
            try {
                const { client, tools } = await openClient(state, true);
                if (startedIn !== generation) {
                    // Shut down while connecting: do not register into a dead session.
                    await client.close();
                    return { server, state: "offline", error: "shut down while connecting" };
                }
                state.client = client;
                if (api)
                    registerTools(api, state, tools);
                void promptCommands.refresh(state, client);
                return report({
                    server,
                    state: "connected",
                    toolCount: tools.length,
                    ...(client.protocolVersion ? { protocolVersion: client.protocolVersion } : {}),
                    ...(state.hasResources ? { resources: true } : {}),
                });
            }
            catch (cause) {
                const status = report({ server, state: "offline", error: messageOf(cause) });
                if (startedIn === generation)
                    reportUnavailable(server, status.error ?? "");
                return status;
            }
        })().finally(() => {
            state.connecting = undefined;
        });
        state.connecting = attempt;
        return attempt;
    };
    const connect = async () => {
        if (!api) {
            throw new Error("connect() called before the extension was loaded — pass it through ExtensionRegistry.load first");
        }
        // In parallel: one slow or unreachable server must not hold up the others (HF-12).
        return Promise.all(states.map(connectOne));
    };
    const close = async () => {
        generation++;
        const closing = [...clients];
        clients.clear();
        for (const state of states)
            state.client = undefined;
        await Promise.allSettled(closing.map((client) => client.close()));
    };
    /**
     * wait_for_mcp_servers (P46, Claude Code's WaitForMcpServers): registered once some server
     * is still connecting, then kept for the session (Claude Code keeps it listed).
     */
    let waitToolRegistered = false;
    const registerWaitTool = (target) => {
        if (waitToolRegistered || options.waitTool === false)
            return;
        waitToolRegistered = true;
        target.registerTool(createWaitTool(states, lastStatus, options.platform.sleep), { risk: "low" });
    };
    /** Wait for these servers' connections in flight, until `signal` aborts. */
    const waitFor = async (waiting, signal) => {
        const pending = waiting.flatMap((state) => (state.connecting ? [state.connecting] : []));
        if (pending.length > 0)
            await Promise.race([Promise.all(pending), aborted(signal)]);
    };
    const extension = (extensionApi) => {
        api = extensionApi;
        // One `@` provider per host UI, however many sessions start in it (P44).
        const completing = new WeakSet();
        extensionApi.on("session_start", (_event, ctx) => {
            sessionCtx = ctx;
            waitedForStartup = false;
            const ui = ctx.ui;
            if (options.resourceMentions !== false && ui?.addAutocompleteProvider && !completing.has(ui)) {
                completing.add(ui);
                ui.addAutocompleteProvider((current) => createMentionProvider(current, () => mentionResources()));
            }
            // Exposure is known from the config: the tool that reaches indirect servers is
            // active before the first request, not only once a slow server has connected.
            for (const state of states)
                if (!state.conflict)
                    activateReachingTools(extensionApi, state);
            void connect();
            // Servers reached through tool_search are waited for there (Claude Code: no WaitForMcpServers with tool search).
            if (states.some((state) => state.connecting && configuredExposures(state.config).has("direct"))) {
                registerWaitTool(extensionApi);
            }
        });
        // The first prompt waits for servers whose tools are declared to the model, so they are
        // declared in its first request, but not indefinitely (pi waitForDirectServers). Every
        // prompt lists the indirect servers in the mcp_servers section as they are when it starts.
        extensionApi.on("before_agent_start", async (event, ctx) => {
            if (!waitedForStartup) {
                waitedForStartup = true;
                const direct = states.filter((state) => state.connecting && configuredExposures(state.config).has("direct"));
                const sleep = options.platform.sleep;
                if (direct.length > 0 && sleep) {
                    // Stopped once the servers are in: a pending timer would keep `tau -p` alive for the rest of it.
                    const timer = new AbortHandle();
                    const finished = await Promise.race([
                        Promise.all(direct.map((state) => state.connecting)).then(() => true),
                        sleep(options.startupWaitMs ?? DEFAULT_STARTUP_WAIT_MS, timer.signal).then(() => false, () => false),
                    ]);
                    timer.abort("settled");
                    if (!finished) {
                        ctx.ui?.notify("MCP servers are still connecting; their tools become available once connected.", "info");
                    }
                }
            }
            const section = renderServersSection(states
                .filter((state) => !state.conflict)
                .map((state) => ({ name: state.config.name, config: state.config, instructions: state.instructions })));
            return section ? { systemPrompt: `${event.systemPrompt}\n\n${section}` } : undefined;
        });
        // A tool that reaches other tools waits for the servers it may reach: a codemode script
        // for the servers it names (or all, when it searches), tool_search for every server.
        // A direct MCP call waits only for its own server (pi).
        extensionApi.on("tool_call", async (event, ctx) => {
            const tool = extensionApi.getAllTools().find((candidate) => candidate.name === event.toolName);
            if (!tool)
                return undefined;
            const reach = tool.reachesTools?.(event.input);
            const owner = toolOwners.get(event.toolName)?.split("\0", 1)[0];
            // The resource tools reach every server with resources, known only once connected (pi).
            const waiting = reach !== undefined
                ? states.filter((state) => reachesServer(reach, state.config.name))
                : RESOURCE_TOOL_NAMES.has(event.toolName)
                    ? states
                    : states.filter((state) => state.config.name === owner);
            await waitFor(waiting, ctx.signal);
            return undefined;
        });
        // `@server:uri` in a message: read each resource and attach it (P44, Claude Code).
        extensionApi.on("input", async (event, ctx) => {
            if (options.resourceMentions === false || !event.text.includes("@"))
                return undefined;
            const named = new Set(states.filter((state) => !state.conflict).map((state) => state.config.name));
            const mentions = findMentions(event.text, named);
            if (mentions.length === 0)
                return undefined;
            const involved = states.filter((state) => mentions.some((mention) => mention.server === state.config.name));
            await waitFor(involved, ctx.signal);
            const blocks = [];
            const images = [...(event.images ?? [])];
            for (const mention of mentions) {
                const state = states.find((candidate) => candidate.config.name === mention.server);
                if (!state || !resourceStates().includes(state))
                    continue;
                try {
                    const result = await withClient(state, (client) => client.readResource(mention.uri, { timeoutMs: state.config.timeoutMs ?? DEFAULT_HANDSHAKE_TIMEOUT_MS }));
                    const content = toModelContent(result.contents.map((contents) => ({ type: "resource", resource: contents })), contentOptions(state));
                    const text = content.flatMap((block) => (block.type === "text" ? [block.text] : [])).join("\n");
                    images.push(...content.filter((block) => block.type === "image"));
                    blocks.push(mentionBlock(mention.server, mention.uri, text));
                }
                catch (cause) {
                    ctx.ui?.notify(`Could not read @${mention.server}:${mention.uri}: ${messageOf(cause)}`, "warning");
                }
            }
            if (blocks.length === 0 && images.length === (event.images ?? []).length)
                return undefined;
            return {
                action: "transform",
                text: [event.text, ...blocks].join("\n\n"),
                ...(images.length > 0 ? { images } : {}),
            };
        });
        extensionApi.registerCommand("mcp", {
            description: "Show MCP servers; reconnect <server> reconnects one",
            getArgumentCompletions: (prefix) => {
                const [action = "", server, ...rest] = prefix.trimStart().split(/\s+/);
                if (rest.length > 0)
                    return null;
                if (server === undefined) {
                    return "reconnect".startsWith(action) ? [{ value: "reconnect ", label: "reconnect" }] : null;
                }
                if (action !== "reconnect")
                    return null;
                return states
                    .filter((state) => state.config.name.startsWith(server))
                    .map((state) => ({
                    value: `reconnect ${state.config.name}`,
                    label: state.config.name,
                    description: describe(state),
                }));
            },
            handler: async (args) => {
                const [action, name, ...extra] = args.trim().split(/\s+/).filter(Boolean);
                if (action === undefined) {
                    if (states.length === 0)
                        return "No MCP servers configured.";
                    return [`MCP servers (${states.length})`, ...states.map(describe)].join("\n");
                }
                if (action !== "reconnect" || extra.length > 0)
                    return "Usage: /mcp [reconnect <server>]";
                const targets = name === undefined
                    ? states.filter((state) => state.client?.connectionState !== "connected")
                    : states.filter((state) => state.config.name === name);
                if (name !== undefined && targets.length === 0)
                    return `No MCP server named "${name}".`;
                if (targets.length === 0)
                    return "Every MCP server is connected.";
                for (const state of targets) {
                    if (state.connecting)
                        await state.connecting;
                    const client = state.client;
                    state.client = undefined;
                    await client?.close().catch(() => { });
                }
                const statuses = await Promise.all(targets.map(connectOne));
                return statuses
                    .map((status) => status.state === "connected"
                    ? `Reconnected to "${status.server}" (${status.toolCount ?? 0} tools).`
                    : `"${status.server}" is offline: ${status.error ?? "unknown error"}`)
                    .join("\n");
            },
        });
        extensionApi.on("session_shutdown", async () => {
            // Registered tools stay (they resolve the live client per call); a later
            // connect() reconnects and registers them again.
            sessionCtx = undefined;
            await close();
        });
    };
    return { extension, connect, close };
}
