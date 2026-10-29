import { toModelContent } from "./result.js";
/**
 * The command name (Claude Code): characters of the server name outside A-Z, a-z,
 * 0-9, `_` and `-` become `_`. The prompt name is kept as the server declares it,
 * except characters a typed command cannot hold (tau: the same rule).
 */
export function mcpPromptCommandName(server, prompt) {
    return `mcp__${server.replace(/[^A-Za-z0-9_-]/g, "_")}__${prompt.replace(/[^A-Za-z0-9_:-]/g, "_")}`;
}
/** `<required> [optional]` for each declared argument. */
export function promptUsage(prompt) {
    return (prompt.arguments ?? [])
        .map((argument) => (argument.required ? `<${argument.name}>` : `[${argument.name}]`))
        .join(" ");
}
export function promptCommandDescription(prompt) {
    const text = (prompt.description ?? prompt.title ?? "").trim();
    const usage = promptUsage(prompt);
    return `${text ? `${text} ` : ""}(MCP)${usage ? ` ${usage}` : ""}`;
}
/**
 * The prompt's arguments from the words typed after the command (Claude Code splits on
 * whitespace and fills them in order). Words past the declared arguments join the last
 * one (tau: nothing is dropped). A missing required argument is an error.
 */
export function promptArguments(command, prompt, args) {
    const declared = prompt.arguments ?? [];
    const words = args.trim().split(/\s+/).filter(Boolean);
    const values = {};
    declared.forEach((argument, index) => {
        const value = index === declared.length - 1 ? words.slice(index).join(" ") : words[index];
        if (value !== undefined && value !== "")
            values[argument.name] = value;
    });
    const missing = declared.filter((argument) => argument.required && values[argument.name] === undefined);
    if (missing.length > 0) {
        const usage = promptUsage(prompt);
        return {
            error: `Missing ${missing.map((argument) => argument.name).join(", ")}. Usage: /${command}${usage ? ` ${usage}` : ""}`,
        };
    }
    return { values };
}
/**
 * prompts/get's messages as one user message: text in order, images attached, embedded
 * resources and resource links as for tool results (P43). With assistant messages among
 * them, each message is labelled with its role.
 */
export function promptMessageContent(result, options) {
    const labelled = result.messages.some((message) => message.role === "assistant");
    const parts = [];
    const images = [];
    for (const message of result.messages) {
        const content = toModelContent([message.content], options);
        const text = content
            .flatMap((block) => (block.type === "text" ? [block.text] : []))
            .join("\n")
            .trim();
        images.push(...content.filter((block) => block.type === "image"));
        if (text === "")
            continue;
        parts.push(labelled ? `${message.role === "assistant" ? "Assistant" : "User"}: ${text}` : text);
    }
    return { text: parts.join("\n\n"), images };
}
/** The servers' prompts as commands: listed when a server connects or says its list changed. */
export function createPromptCommands(host) {
    const timeout = (server) => server.config.timeoutMs ?? host.timeoutMs;
    /** /mcp__<server>__<prompt>: prompts/get, then send its messages as a prompt (Claude Code). */
    const runPrompt = async (server, name, args) => {
        const prompt = server.prompts.get(name);
        if (!prompt)
            return `MCP prompt /${name} is no longer available.`;
        const parsed = promptArguments(name, prompt, args);
        if ("error" in parsed)
            return parsed.error;
        try {
            const result = await host.withClient(server, (client) => client.getPrompt(prompt.name, parsed.values, { timeoutMs: timeout(server) }));
            const { text, images } = promptMessageContent(result, host.contentOptions(server));
            if (text === "" && images.length === 0)
                return `MCP prompt /${name} returned no messages.`;
            return { action: "prompt", text, ...(images.length > 0 ? { images } : {}) };
        }
        catch (cause) {
            return `MCP prompt /${name} failed: ${cause instanceof Error ? cause.message : String(cause)}`;
        }
    };
    /** One command per prompt; commands of prompts the server dropped become hidden (P44). */
    const register = (target, server, prompts) => {
        const previous = new Set(server.prompts.keys());
        server.prompts = new Map(prompts.map((prompt) => [mcpPromptCommandName(server.config.name, prompt.name), prompt]));
        for (const [name, prompt] of server.prompts) {
            target.registerCommand(name, {
                description: promptCommandDescription(prompt),
                source: "mcp",
                handler: (args) => runPrompt(server, name, args),
            });
        }
        for (const name of previous) {
            if (server.prompts.has(name))
                continue;
            target.registerCommand(name, {
                description: "MCP prompt no longer offered",
                hidden: true,
                handler: () => runPrompt(server, name, ""),
            });
        }
    };
    /** List the server's prompts again (after it connected, or said the list changed). */
    const refresh = async (server, client) => {
        await server.connecting;
        const api = host.api();
        if (server.client !== client || !api || !host.enabled)
            return;
        if (client.serverCapabilities?.prompts === undefined)
            return;
        if ((server.config.exposure ?? "direct") === "hidden")
            return;
        try {
            const prompts = await client.listPrompts({ timeoutMs: timeout(server) });
            if (server.client === client)
                register(api, server, prompts);
        }
        catch {
            // A failed listing keeps the commands registered so far (Claude Code).
        }
    };
    return { refresh };
}
