// MCP 2026-07-28 Streamable HTTP request metadata (spec "Request Metadata", SEP-2243): the
// standard Mcp-Method / Mcp-Name headers, and tool parameters a server marks with
// `x-mcp-header` mirrored as Mcp-Param-{Name}. Values that are not plain header-safe ASCII
// travel as the spec's Base64 sentinel `=?base64?…?=`.
import { encodeBase64 } from "@yophon/tau-kernel";
import { isObject } from "./jsonrpc.js";
/** Methods whose `params.name` (or `params.uri`) is mirrored into Mcp-Name. */
const NAMED_METHODS = {
    "tools/call": "name",
    "prompts/get": "name",
    "resources/read": "uri",
};
const SENTINEL = /^=\?base64\?[\s\S]*\?=$/;
/** Visible ASCII, space and tab (RFC 9110 field values), without leading or trailing whitespace. */
const PLAIN = /^(?:[\x21-\x7e](?:[\x20-\x7e\t]*[\x21-\x7e])?)?$/;
/** RFC 9110 token (`1*tchar`): what an `x-mcp-header` name may be. */
const TOKEN = /^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/;
const HEADER_TYPES = new Set(["string", "integer", "boolean"]);
function encodeUtf8(text) {
    const bytes = [];
    for (const char of text) {
        const code = char.codePointAt(0) ?? 0;
        if (code < 0x80)
            bytes.push(code);
        else if (code < 0x800)
            bytes.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f));
        else if (code < 0x10000)
            bytes.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
        else {
            bytes.push(0xf0 | (code >> 18), 0x80 | ((code >> 12) & 0x3f), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
        }
    }
    return Uint8Array.from(bytes);
}
/** A header value as the spec sends it: plain when safe, otherwise the Base64 sentinel. */
export function encodeHeaderValue(value) {
    return PLAIN.test(value) && !SENTINEL.test(value) ? value : `=?base64?${encodeBase64(encodeUtf8(value))}?=`;
}
/** Mcp-Method, and Mcp-Name for tools/call, prompts/get and resources/read. */
export function standardHeaders(method, params) {
    const headers = { "mcp-method": method };
    const key = NAMED_METHODS[method];
    const name = key && isObject(params) ? params[key] : undefined;
    if (typeof name === "string")
        headers["mcp-name"] = encodeHeaderValue(name);
    return headers;
}
/**
 * The `x-mcp-header` parameters of a tool's input schema, or why the tool is invalid
 * (spec: the client excludes such a tool). Annotations must sit on string, integer or
 * boolean properties reachable from the root through `properties` keys alone, and their
 * names must be RFC 9110 tokens, unique regardless of case.
 */
export function paramHeaders(schema) {
    const found = [];
    const seen = new Set();
    let error;
    const visit = (node, path, where) => {
        if (error !== undefined)
            return;
        if (Array.isArray(node)) {
            for (const [index, item] of node.entries())
                visit(item, undefined, `${where}/${index}`);
            return;
        }
        if (!isObject(node))
            return;
        if ("x-mcp-header" in node) {
            const name = node["x-mcp-header"];
            if (path === undefined || path.length === 0) {
                error = `x-mcp-header at ${where || "/"} is not reachable through properties alone`;
                return;
            }
            if (typeof name !== "string" || !TOKEN.test(name)) {
                error = `x-mcp-header at ${where} is not a valid header name`;
                return;
            }
            if (typeof node.type !== "string" || !HEADER_TYPES.has(node.type)) {
                error = `x-mcp-header at ${where} is on a parameter that is not a string, integer or boolean`;
                return;
            }
            if (seen.has(name.toLowerCase())) {
                error = `x-mcp-header "${name}" appears more than once`;
                return;
            }
            seen.add(name.toLowerCase());
            found.push({ path, name });
        }
        for (const [key, value] of Object.entries(node)) {
            if (key === "properties" && isObject(value)) {
                // A property named "x-mcp-header" is a property, not an annotation.
                for (const [property, child] of Object.entries(value)) {
                    visit(child, path === undefined ? undefined : [...path, property], `${where}/properties/${property}`);
                }
            }
            else {
                visit(value, undefined, `${where}/${key}`);
            }
        }
    };
    visit(schema, [], "");
    return error === undefined ? found : { error };
}
/** The Mcp-Param-{Name} headers for one call; a missing or null value sends no header. */
export function mirroredParams(params, args) {
    const headers = {};
    for (const { path, name } of params) {
        let value = args;
        for (const key of path)
            value = isObject(value) ? value[key] : undefined;
        let text;
        if (typeof value === "string")
            text = value;
        else if (typeof value === "boolean")
            text = value ? "true" : "false";
        else if (typeof value === "number" && Number.isSafeInteger(value))
            text = String(value);
        if (text !== undefined)
            headers[`mcp-param-${name.toLowerCase()}`] = encodeHeaderValue(text);
    }
    return headers;
}
