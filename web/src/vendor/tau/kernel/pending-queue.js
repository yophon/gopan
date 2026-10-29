// The steering and follow-up queues (P53c: split out of agent.ts when the structure gate's
// ceiling was reached), a port of pi's PendingMessageQueue.
/** Mirrors pi's PendingMessageQueue (packages/agent/src/agent.ts). Custom messages may queue too (sendMessage deliverAs). */
export class PendingMessageQueue {
    items = [];
    mode;
    constructor(mode) {
        this.mode = mode;
    }
    enqueue(message) {
        this.items.push(message);
    }
    hasItems() {
        return this.items.length > 0;
    }
    drain() {
        if (this.items.length === 0)
            return [];
        if (this.mode === "all")
            return this.items.splice(0);
        return this.items.splice(0, 1);
    }
    /** Put messages polled but never committed back at the front (an abort between poll and commit). */
    restore(messages) {
        this.items.unshift(...messages);
    }
    /** Empty the queue and return what was in it (pi clearQueue: hosts restore it to the editor). */
    clear() {
        return this.items.splice(0);
    }
}
