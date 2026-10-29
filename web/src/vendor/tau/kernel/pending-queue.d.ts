import type { CustomMessage, UserMessage } from "./messages.js";
/** How queued steering/follow-up messages are drained, as in pi. */
export type QueueMode = "one-at-a-time" | "all";
/** Mirrors pi's PendingMessageQueue (packages/agent/src/agent.ts). Custom messages may queue too (sendMessage deliverAs). */
export declare class PendingMessageQueue {
    private readonly items;
    readonly mode: QueueMode;
    constructor(mode: QueueMode);
    enqueue(message: UserMessage | CustomMessage): void;
    hasItems(): boolean;
    drain(): (UserMessage | CustomMessage)[];
    /** Put messages polled but never committed back at the front (an abort between poll and commit). */
    restore(messages: (UserMessage | CustomMessage)[]): void;
    /** Empty the queue and return what was in it (pi clearQueue: hosts restore it to the editor). */
    clear(): (UserMessage | CustomMessage)[];
}
