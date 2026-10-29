import { toError } from "./jsonrpc.js";
/** Listener bookkeeping shared by transports. `emitClose` fires at most once per transport. */
export class TransportEvents {
    messageListeners = new Set();
    errorListeners = new Set();
    closeListeners = new Set();
    closeEmitted = false;
    onMessage(listener) {
        this.messageListeners.add(listener);
        return () => this.messageListeners.delete(listener);
    }
    onError(listener) {
        this.errorListeners.add(listener);
        return () => this.errorListeners.delete(listener);
    }
    onClose(listener) {
        this.closeListeners.add(listener);
        return () => this.closeListeners.delete(listener);
    }
    emitMessage(message) {
        for (const listener of this.messageListeners)
            listener(message);
    }
    emitError(error) {
        const normalized = toError(error);
        for (const listener of this.errorListeners)
            listener(normalized);
    }
    emitClose() {
        if (this.closeEmitted)
            return;
        this.closeEmitted = true;
        for (const listener of this.closeListeners)
            listener();
    }
}
