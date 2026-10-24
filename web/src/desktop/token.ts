/** access token(JWT)离过期不到 [marginSec] 秒,或者解不出 exp:算快过期 */
export function tokenExpiring(token: string, marginSec: number, nowMs = Date.now()): boolean {
  const payload = token.split('.')[1]
  if (!payload) return true
  try {
    const json = JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/'))) as { exp?: unknown }
    if (typeof json.exp !== 'number') return true
    return json.exp * 1000 <= nowMs + marginSec * 1000
  } catch {
    return true
  }
}
