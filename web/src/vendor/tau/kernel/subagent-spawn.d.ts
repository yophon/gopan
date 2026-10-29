import type { AgentInternals } from "./agent-internals.js";
import { type AgentRunResult, type AgentSpawnOptions } from "./extensions.js";
import type { TauAbortSignal } from "./platform.js";
/** What a spawn reads from the parent agent (live: the agent passes getters). */
export type SubagentHost = Pick<AgentInternals, "baseTools" | "capabilities" | "compactionSettings" | "config" | "createAgent" | "currentThinkingLevel" | "currentTools" | "extensionContext" | "extensions" | "followUpMode" | "imageProcessor" | "isProjectTrusted" | "maxTurnsPerPrompt" | "onApproval" | "permissionMode" | "platform" | "policy" | "preflightLock" | "pricing" | "retrySettings" | "steeringMode" | "systemPrompt" | "toolExecution" | "transport" | "ui">;
/** Run a prompt in a child agent (ctx.runSubagent). */
export declare function runSubagent(host: SubagentHost, prompt: string, options?: AgentSpawnOptions, signal?: TauAbortSignal): Promise<AgentRunResult>;
