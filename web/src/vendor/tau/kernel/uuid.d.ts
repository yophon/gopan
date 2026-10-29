/**
 * uuidv7 ported from pi (packages/agent/src/harness/session/uuid.ts): 48-bit
 * timestamp + monotonic sequence + random tail. Deviation: randomness comes
 * from the injected Platform.randomBytes instead of globalThis.crypto, to
 * satisfy tau's kernel purity gate.
 */
export declare function uuidv7(randomBytes: (length: number) => Uint8Array): string;
