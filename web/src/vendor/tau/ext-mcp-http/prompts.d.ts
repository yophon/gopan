import type { ExtensionAPI, ImageContent } from "@yophon/tau-kernel";
import type { McpClient, McpGetPromptResult, McpPrompt } from "./client.js";
import type { McpExposure } from "./exposure.js";
import { type McpContentOptions } from "./result.js";
/**
 * The command name (Claude Code): characters of the server name outside A-Z, a-z,
 * 0-9, `_` and `-` become `_`. The prompt name is kept as the server declares it,
 * except characters a typed command cannot hold (tau: the same rule).
 */
export declare function mcpPromptCommandName(server: string, prompt: string): string;
/** `<required> [optional]` for each declared argument. */
export declare function promptUsage(prompt: McpPrompt): string;
export declare function promptCommandDescription(prompt: McpPrompt): string;
/**
 * The prompt's arguments from the words typed after the command (Claude Code splits on
 * whitespace and fills them in order). Words past the declared arguments join the last
 * one (tau: nothing is dropped). A missing required argument is an error.
 */
export declare function promptArguments(command: string, prompt: McpPrompt, args: string): {
    values: Record<string, string>;
} | {
    error: string;
};
/**
 * prompts/get's messages as one user message: text in order, images attached, embedded
 * resources and resource links as for tool results (P43). With assistant messages among
 * them, each message is labelled with its role.
 */
export declare function promptMessageContent(result: McpGetPromptResult, options: McpContentOptions): {
    text: string;
    images: ImageContent[];
};
/** What the prompt commands read of a configured server (P62: moved here from servers.ts). */
export interface PromptServer {
    config: {
        name: string;
        exposure?: McpExposure;
        timeoutMs?: number;
    };
    client?: McpClient;
    connecting?: Promise<unknown>;
    /** Prompts the server offers now, by command name. */
    prompts: Map<string, McpPrompt>;
}
export interface PromptCommandsHost<S extends PromptServer> {
    /** The extension API once the extension is loaded. */
    api(): ExtensionAPI | undefined;
    /** False: no prompt commands (an option of the host). */
    enabled: boolean;
    /** How long a listing or prompts/get may take. */
    timeoutMs: number;
    withClient<T>(server: S, call: (client: McpClient) => Promise<T>): Promise<T>;
    contentOptions(server: S): McpContentOptions;
}
/** The servers' prompts as commands: listed when a server connects or says its list changed. */
export declare function createPromptCommands<S extends PromptServer>(host: PromptCommandsHost<S>): {
    refresh: (server: S, client: McpClient) => Promise<void>;
};
