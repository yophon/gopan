import { isMcpAppResource } from "./resources.js";
/** Resource items listed at most for one `@` query; typing narrows them. */
export const MENTION_LIMIT = 200;
/** The `@` token before the cursor (as the file completion reads it). */
const TOKEN_BEFORE_CURSOR = /(?:^|\s)@([^\s@"]*)$/;
export function mentionValue(server, uri) {
    return `@${server}:${uri}`;
}
/** Prefix > substring > ordered subsequence, on `server:uri` and the name; -1 = no match. */
function score(resource, query) {
    if (query === "")
        return 1;
    const q = query.toLowerCase();
    let best = -1;
    for (const candidate of [`${resource.server}:${resource.uri}`, resource.uri, resource.title ?? "", resource.name]) {
        const c = candidate.toLowerCase();
        if (c === "")
            continue;
        if (c.startsWith(q))
            best = Math.max(best, 1000 - c.length);
        else if (c.includes(q))
            best = Math.max(best, 600 - c.indexOf(q) - c.length);
        else {
            let i = 0;
            for (const ch of c) {
                if (ch === q[i])
                    i++;
                if (i === q.length) {
                    best = Math.max(best, 300 - c.length);
                    break;
                }
            }
        }
    }
    return best;
}
/** Resource items for `query` (fuzzy, best first). */
export function mentionItems(resources, query) {
    return resources
        .map((resource) => ({ resource, score: score(resource, query) }))
        .filter((entry) => entry.score >= 0)
        .sort((a, b) => b.score - a.score)
        .slice(0, MENTION_LIMIT)
        .map(({ resource }) => {
        const description = resource.title ?? (resource.name !== resource.uri ? resource.name : undefined);
        return {
            value: mentionValue(resource.server, resource.uri),
            label: mentionValue(resource.server, resource.uri),
            ...(description ? { description } : {}),
        };
    });
}
/**
 * Stack resource items onto the host's `@` completion: the host's items (files) first,
 * then the resources. Accepting a resource puts `@server:uri ` in place of the token.
 */
export function createMentionProvider(current, resources) {
    const ours = new Set();
    return {
        ...(current.triggerCharacters ? { triggerCharacters: current.triggerCharacters } : {}),
        async getSuggestions(lines, cursorLine, cursorCol, options) {
            const base = await current.getSuggestions(lines, cursorLine, cursorCol, options);
            const before = (lines[cursorLine] ?? "").slice(0, cursorCol);
            const token = TOKEN_BEFORE_CURSOR.exec(before);
            if (!token)
                return base;
            let listed = [];
            try {
                listed = await resources(options.signal);
            }
            catch {
                return base;
            }
            const items = mentionItems(listed, token[1] ?? "");
            for (const item of items)
                ours.add(item.value);
            if (items.length === 0)
                return base;
            return { items: [...(base?.items ?? []), ...items], prefix: `@${token[1] ?? ""}` };
        },
        applyCompletion(lines, cursorLine, cursorCol, item, prefix) {
            if (!ours.has(item.value))
                return current.applyCompletion(lines, cursorLine, cursorCol, item, prefix);
            const line = lines[cursorLine] ?? "";
            const start = cursorCol - prefix.length;
            const inserted = `${item.value} `;
            const next = [...lines];
            next[cursorLine] = line.slice(0, start) + inserted + line.slice(cursorCol);
            return { lines: next, cursorLine, cursorCol: start + inserted.length };
        },
        ...(current.shouldTriggerFileCompletion
            ? { shouldTriggerFileCompletion: current.shouldTriggerFileCompletion.bind(current) }
            : {}),
    };
}
/** `@server:uri` tokens in a message whose server is one of `servers`. Trailing sentence punctuation is not part of the URI. */
export function findMentions(text, servers) {
    const found = [];
    const seen = new Set();
    for (const match of text.matchAll(/(?:^|\s)@([A-Za-z0-9_.-]+):(\S+)/g)) {
        const server = match[1] ?? "";
        const uri = (match[2] ?? "").replace(/[.,;!?]+$/, "");
        const key = `${server}:${uri}`;
        if (!servers.has(server) || uri === "" || seen.has(key))
            continue;
        seen.add(key);
        found.push({ server, uri });
    }
    return found;
}
/** A read resource's text, as attached to the message. */
export function mentionBlock(server, uri, text) {
    const attribute = (value) => value.replace(/&/g, "&amp;").replace(/"/g, "&quot;");
    return `<mcp_resource server="${attribute(server)}" uri="${attribute(uri)}">\n${text}\n</mcp_resource>`;
}
/**
 * Resources of these servers for `@` completion, MCP App resources left out. Each server's listing
 * is cached on it (`resourceList`, dropped on resources/list_changed); a failed one is not kept.
 */
export async function listMentionResources(servers, list) {
    const lists = await Promise.all(servers.map((server) => {
        server.resourceList ??= list(server)
            .then((resources) => resources
            .filter((resource) => !isMcpAppResource(resource))
            .map((resource) => ({
            server: server.config.name,
            uri: resource.uri,
            name: resource.name,
            ...(typeof resource.title === "string" ? { title: resource.title } : {}),
        })))
            .catch((error) => {
            server.resourceList = undefined;
            throw error;
        });
        return server.resourceList.catch(() => []);
    }));
    return lists.flat();
}
