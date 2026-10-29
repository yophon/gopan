import { type ExtensionContext, type Platform, type Tool } from "@yophon/tau-kernel";
export declare const WAIT_FOR_MCP_SERVERS_TOOL = "wait_for_mcp_servers";
/** A promise that settles when `signal` aborts (never, without one). */
export declare function aborted(signal: ExtensionContext["signal"]): Promise<void>;
/** What the tool reads of a configured server. */
export interface WaitServer {
    config: {
        name: string;
    };
    connecting?: Promise<unknown>;
    client?: {
        connectionState: string;
    };
    tools: ReadonlySet<string>;
}
export declare function createWaitTool(servers: readonly WaitServer[], lastStatus: ReadonlyMap<string, {
    error?: string;
}>, sleep: Platform["sleep"]): Tool;
