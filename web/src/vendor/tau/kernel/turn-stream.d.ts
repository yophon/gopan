import type { AgentEvent } from "./agent.js";
import type { AgentInternals } from "./agent-internals.js";
import { TauError } from "./errors.js";
import type { ExtensionContext } from "./extensions.js";
import { type AssistantMessage } from "./messages.js";
import type { ChatStreamEvent } from "./openai.js";
/** What pulling a response reads of the agent. */
export type TurnStreamHost = Pick<AgentInternals, "extensions" | "pricingFor">;
/** The final message, or why there is none. */
export interface StreamedResponse {
    message?: AssistantMessage;
    failure?: TauError;
}
export declare function pullResponse(host: TurnStreamHost, stream: AsyncIterable<ChatStreamEvent>, partial: AssistantMessage, ctx: ExtensionContext, requestStart: number): AsyncGenerator<AgentEvent, StreamedResponse>;
