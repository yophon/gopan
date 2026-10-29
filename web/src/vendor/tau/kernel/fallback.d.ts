import type { ChatTransport } from "./openai.js";
/** What a failure was, for deciding whether to switch. */
export type FallbackReason = "rate_limit" | "overloaded" | "network" | "auth" | "billing";
export declare const FALLBACK_REASONS: readonly FallbackReason[];
/** One way to answer: a transport and the model it is asked for. A second key is another route. */
export interface FallbackRoute {
    transport: ChatTransport;
    model: string;
    /** For the switch notice, e.g. "provider/model". */
    label: string;
}
export interface FallbackSwitch {
    from: string;
    to: string;
    reason: FallbackReason;
    /** The failure, as the provider put it. */
    message: string;
}
export interface FallbackOptions {
    /** The routes after the agent's own model, in order. */
    routes: readonly FallbackRoute[];
    /** The failures that switch (default: all of FALLBACK_REASONS); ["overloaded"] is Claude Code's choice. */
    on?: readonly FallbackReason[];
    onSwitch?: (event: FallbackSwitch) => void;
}
/** Classify a failure: an HTTP status when there is one, else the message. Undefined: do not switch. */
export declare function fallbackReasonOf(failure: unknown): FallbackReason | undefined;
/** A run's routes: the transport the run asks, and the switch the agent takes after a failure. */
export interface FallbackChain {
    /** Asks the current route, for its model. */
    readonly transport: ChatTransport;
    /**
     * A request failed and the agent will not retry it: move to the next route when the failure is
     * one to switch on and nothing of this run has come from a route yet. True when it moved.
     */
    fallBack(failure: unknown): boolean;
}
/** The routes of one run: `primary`, then `options.routes`. Make one per run. */
export declare function createFallbackChain(primary: FallbackRoute, options: FallbackOptions): FallbackChain;
