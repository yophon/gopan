import { describe, expect, it } from 'vitest'

import { tokenExpiring } from './token'

function jwt(exp: unknown): string {
  const payload = btoa(JSON.stringify({ sub: 'u', exp })).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  return `h.${payload}.s`
}

describe('tokenExpiring', () => {
  const now = 1_800_000_000_000
  it('reads exp from the JWT payload', () => {
    expect(tokenExpiring(jwt(now / 1000 + 3600), 120, now)).toBe(false)
    expect(tokenExpiring(jwt(now / 1000 + 60), 120, now)).toBe(true)
  })
  it('treats tokens it cannot read as expiring', () => {
    expect(tokenExpiring('nope', 120, now)).toBe(true)
    expect(tokenExpiring(jwt('x'), 120, now)).toBe(true)
    expect(tokenExpiring('h.@@@.s', 120, now)).toBe(true)
  })
})
