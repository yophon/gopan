import { TauError } from "./errors.js";
import { utf8CodePointLength } from "./utf8.js";
/** A tool's exposure; absent = `"direct"` (pi). */
export function toolExposure(tool) {
    return tool.exposure ?? "direct";
}
/** Whether activating the tool declares it to the model (pi _isDeclarable). */
export function isDeclarable(tool) {
    const exposure = toolExposure(tool);
    return exposure === "direct" || exposure === "model-only";
}
/** Whether registering the tool activates it (pi _isActivatedOnRegistration). */
export function isActivatedOnRegistration(tool) {
    return isDeclarable(tool) && tool.defaultActive !== false;
}
/**
 * Apply the `+name` and `-name` entries of `entries` to `base` in order (pi applyToolModifiers):
 * `+name` adds a tool and `-name` removes one. Other entries are ignored.
 */
export function applyToolModifiers(base, entries) {
    const tools = [...base];
    for (const entry of entries) {
        if (!entry.startsWith("+") && !entry.startsWith("-"))
            continue;
        const name = entry.slice(1);
        const index = tools.indexOf(name);
        if (entry.startsWith("+") && index === -1 && name)
            tools.push(name);
        else if (entry.startsWith("-") && index !== -1)
            tools.splice(index, 1);
    }
    return tools;
}
/** Tool information without `execute` (a copy: extensions cannot mutate the registered tool through it). */
export function toToolInfo(tool, source) {
    return {
        name: tool.name,
        description: tool.description,
        parameters: tool.parameters,
        ...(tool.outputSchema ? { outputSchema: tool.outputSchema } : {}),
        exposure: toolExposure(tool),
        ...(tool.namespace ? { namespace: tool.namespace } : {}),
        ...(tool.promptSnippet ? { promptSnippet: tool.promptSnippet } : {}),
        ...(tool.promptGuidelines ? { promptGuidelines: [...tool.promptGuidelines] } : {}),
        ...(tool.reachesTools ? { reachesTools: tool.reachesTools } : {}),
        source,
    };
}
/**
 * Keep the start and the end of `content`, about half of `maxBytes` (UTF-8) each,
 * and replace the middle with a `…N chars truncated…` marker — pi's
 * truncateMiddle (Codex's format). Cuts only at character boundaries.
 */
export function truncateMiddle(content, maxBytes) {
    const chars = Array.from(content);
    const sizes = chars.map((char) => utf8CodePointLength(char.codePointAt(0) ?? 0));
    const totalBytes = sizes.reduce((sum, size) => sum + size, 0);
    const totalLines = content.split("\n").length;
    if (totalBytes <= maxBytes)
        return { content, truncated: false, removedChars: 0, totalBytes, totalLines };
    const headBudget = Math.floor(maxBytes / 2);
    let headEnd = 0;
    for (let used = 0; headEnd < chars.length && used + sizes[headEnd] <= headBudget; headEnd++)
        used += sizes[headEnd];
    const tailBudget = maxBytes - headBudget;
    let tailStart = chars.length;
    for (let used = 0; tailStart > headEnd && used + sizes[tailStart - 1] <= tailBudget; tailStart--)
        used += sizes[tailStart - 1];
    const removedChars = tailStart - headEnd;
    return {
        content: `${chars.slice(0, headEnd).join("")}…${removedChars} chars truncated…${chars.slice(tailStart).join("")}`,
        truncated: true,
        removedChars,
        totalBytes,
        totalLines,
    };
}
/**
 * Model-facing text held to `maxBytes`, in pi's limitMcpContent format: a
 * warning header, the middle-truncated text, then `where` (the full output's
 * location, or why there is none). Untruncated text comes back as is.
 */
export function limitToolText(text, maxBytes, where) {
    const truncation = truncateMiddle(text, maxBytes);
    if (!truncation.truncated)
        return text;
    const tokens = Math.ceil(truncation.totalBytes / 4);
    return `Warning: truncated output (original token count: ${tokens})\nTotal output lines: ${truncation.totalLines}\n\n${truncation.content}\n\n${where(text)}`;
}
/** A plain text result. */
export function textResult(text, details) {
    return details === undefined ? { content: [{ type: "text", text }] } : { content: [{ type: "text", text }], details };
}
export function errorResult(message) {
    return { content: [{ type: "text", text: message }], isError: true };
}
/** A result's content as text (images as a marker): for renderers and hosts that print results. */
export function toolResultText(result) {
    return result.content.map((block) => (block.type === "text" ? block.text : "[image]")).join("\n");
}
export function requireString(args, key) {
    const value = args[key];
    if (typeof value !== "string" || value === "") {
        throw new Error(`Missing or invalid required string argument "${key}"`);
    }
    return value;
}
export function optionalNumber(args, key) {
    const value = args[key];
    if (value === undefined || value === null)
        return undefined;
    if (typeof value !== "number" || !Number.isFinite(value)) {
        throw new Error(`Invalid numeric argument "${key}"`);
    }
    return value;
}
function schemaTypeOf(value) {
    if (value === null)
        return "null";
    if (Array.isArray(value))
        return "array";
    if (typeof value === "number")
        return Number.isInteger(value) ? "integer" : "number";
    if (typeof value === "string")
        return "string";
    if (typeof value === "boolean")
        return "boolean";
    return "object";
}
function typeAccepts(declared, actual) {
    if (declared === actual)
        return true;
    return declared === "number" && actual === "integer";
}
function validateValue(schema, value, path, problems) {
    const label = path === "" ? "arguments" : path;
    const declaredType = schema.type;
    if (typeof declaredType === "string" || Array.isArray(declaredType)) {
        const accepted = (Array.isArray(declaredType) ? declaredType : [declaredType]).filter((entry) => typeof entry === "string");
        const actual = schemaTypeOf(value);
        if (accepted.length > 0 && !accepted.some((entry) => typeAccepts(entry, actual))) {
            problems.push(`${label}: expected ${accepted.join(" | ")}, got ${actual}`);
            return; // Structural checks below assume the declared type.
        }
    }
    const allowed = schema.enum;
    if (Array.isArray(allowed) && allowed.length > 0 && !allowed.some((entry) => entry === value)) {
        problems.push(`${label}: must be one of ${allowed.map((entry) => JSON.stringify(entry)).join(", ")}`);
        return;
    }
    if (Array.isArray(value)) {
        const items = schema.items;
        if (items !== undefined && items !== null && typeof items === "object" && !Array.isArray(items)) {
            value.forEach((element, index) => {
                validateValue(items, element, `${label}[${index}]`, problems);
            });
        }
        return;
    }
    if (value !== null && typeof value === "object") {
        const record = value;
        const required = schema.required;
        if (Array.isArray(required)) {
            for (const key of required) {
                if (typeof key === "string" && record[key] === undefined) {
                    problems.push(`${label}: missing required property "${key}"`);
                }
            }
        }
        const properties = schema.properties;
        if (properties !== undefined && properties !== null && typeof properties === "object") {
            for (const [key, propertySchema] of Object.entries(properties)) {
                if (record[key] === undefined)
                    continue;
                if (propertySchema === null || typeof propertySchema !== "object" || Array.isArray(propertySchema))
                    continue;
                validateValue(propertySchema, record[key], path === "" ? key : `${label}.${key}`, problems);
            }
        }
    }
}
/** True when the tool carries an object JSON Schema the wire and validator can use. */
export function hasToolSchema(tool) {
    const schema = tool.parameters;
    return typeof schema === "object" && schema !== null && !Array.isArray(schema);
}
/**
 * Reject a tool without an object `parameters` schema (pi 0.86.0 rejects
 * schemaless extension tools at registration). Without one, a model call to the
 * tool threw a TypeError out of prompt() and left an unpaired tool_call in the
 * session, so every later request was refused by the endpoint (HF-8).
 */
export function assertToolSchema(tool) {
    if (!hasToolSchema(tool)) {
        throw new TauError("invalid_tool", `Tool "${tool.name}" must declare a JSON Schema object in \`parameters\``);
    }
}
/**
 * Validate tool-call arguments against a JSON Schema subset: type (incl.
 * arrays of types), properties, required, enum, items, and the basic scalar
 * types. Unknown keywords are ignored and extra properties are permitted —
 * the goal is catching malformed model output so it can self-correct, not
 * full JSON Schema conformance. Returns problem descriptions; empty = valid.
 */
export function validateToolArgs(schema, args) {
    const problems = [];
    validateValue(schema, args, "", problems);
    return problems;
}
