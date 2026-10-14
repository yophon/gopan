/**
 * 本地随机 id(上传任务、乐观消息的临时 id)。
 *
 * crypto.randomUUID 只在安全上下文(https / localhost)存在;安卓壳指向局域网 http 实例、
 * 或者自部署用户用 http 访问时它是 undefined,整个上传入口会在 enqueue 时直接抛错。
 * 这里退化到 getRandomValues 自拼 v4,格式一致。
 */
export function randomId(): string {
  const c = globalThis.crypto
  if (c && typeof c.randomUUID === 'function') return c.randomUUID()
  const bytes = new Uint8Array(16)
  if (c && typeof c.getRandomValues === 'function') c.getRandomValues(bytes)
  else for (let i = 0; i < 16; i++) bytes[i] = Math.floor(Math.random() * 256)
  bytes[6] = (bytes[6]! & 0x0f) | 0x40
  bytes[8] = (bytes[8]! & 0x3f) | 0x80
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
