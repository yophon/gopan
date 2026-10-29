import type { AutocompleteItem, AutocompleteProvider, TauAbortSignal } from "@yophon/tau-kernel";
import type { McpResource } from "./client.js";
export interface MentionResource {
    server: string;
    uri: string;
    name: string;
    title?: string;
}
/** Resource items listed at most for one `@` query; typing narrows them. */
export declare const MENTION_LIMIT = 200;
export declare function mentionValue(server: string, uri: string): string;
/** Resource items for `query` (fuzzy, best first). */
export declare function mentionItems(resources: readonly MentionResource[], query: string): AutocompleteItem[];
/**
 * Stack resource items onto the host's `@` completion: the host's items (files) first,
 * then the resources. Accepting a resource puts `@server:uri ` in place of the token.
 */
export declare function createMentionProvider(current: AutocompleteProvider, resources: (signal: TauAbortSignal) => Promise<readonly MentionResource[]>): AutocompleteProvider;
/** `@server:uri` tokens in a message whose server is one of `servers`. Trailing sentence punctuation is not part of the URI. */
export declare function findMentions(text: string, servers: ReadonlySet<string>): Array<{
    server: string;
    uri: string;
}>;
/** A read resource's text, as attached to the message. */
export declare function mentionBlock(server: string, uri: string, text: string): string;
/**
 * Resources of these servers for `@` completion, MCP App resources left out. Each server's listing
 * is cached on it (`resourceList`, dropped on resources/list_changed); a failed one is not kept.
 */
export declare function listMentionResources<S extends {
    config: {
        name: string;
    };
    resourceList?: Promise<MentionResource[]>;
}>(servers: readonly S[], list: (server: S) => Promise<McpResource[]>): Promise<MentionResource[]>;
