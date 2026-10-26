import { describe, expect, it } from 'vitest'

import { diffLines, diffRows, diffStats, splitLines } from './textDiff'

const show = (oldText: string, newText: string) =>
  diffLines(oldText, newText)!.map((l) => (l.kind === 'same' ? ' ' : l.kind === 'add' ? '+' : '-') + l.text)

describe('textDiff', () => {
  it('splits lines and ignores the final newline', () => {
    expect(splitLines('a\r\nb\n')).toEqual(['a', 'b'])
    expect(splitLines('')).toEqual([''])
  })

  it('finds added, removed and changed lines', () => {
    expect(show('a\nb\nc\n', 'a\nc\nd\n')).toEqual([' a', '-b', ' c', '+d'])
    expect(show('一\n二\n三', '一\n二改\n三')).toEqual([' 一', '-二', '+二改', ' 三'])
    expect(show('', 'x')).toEqual(['-', '+x'])
    expect(show('same\n', 'same\n')).toEqual([' same'])
  })

  it('numbers lines on both sides', () => {
    const lines = diffLines('a\nb\nc', 'a\nx\nb\nc')!
    expect(lines.map((l) => [l.kind, l.oldNo ?? null, l.newNo ?? null])).toEqual([
      ['same', 1, 1],
      ['add', null, 2],
      ['same', 2, 3],
      ['same', 3, 4],
    ])
    expect(diffStats(lines)).toEqual({ added: 1, removed: 0 })
  })

  it('reproduces the new text from the diff', () => {
    const oldText = Array.from({ length: 200 }, (_, i) => `line ${i}`).join('\n')
    const newText = oldText.replace('line 5\n', '').replace('line 120', 'line 120 changed') + '\nthe end'
    const lines = diffLines(oldText, newText)!
    expect(lines.filter((l) => l.kind !== 'del').map((l) => l.text).join('\n')).toBe(newText)
    expect(lines.filter((l) => l.kind !== 'add').map((l) => l.text).join('\n')).toBe(oldText)
  })

  it('folds long unchanged runs and gives up on huge rewrites', () => {
    const a = Array.from({ length: 30 }, (_, i) => `${i}`)
    const b = [...a]
    b[15] = 'changed'
    const rows = diffRows(diffLines(a.join('\n'), b.join('\n'))!, 2)
    expect(rows[0]).toEqual({ kind: 'skip', count: 13, key: 13 })
    expect(rows.filter((r) => r.kind !== 'skip')).toHaveLength(6)
    expect(rows[rows.length - 1]).toMatchObject({ kind: 'skip', count: 12 })
    const x = Array.from({ length: 3000 }, (_, i) => `a${i}`).join('\n')
    const y = Array.from({ length: 3000 }, (_, i) => `b${i}`).join('\n')
    expect(diffLines(x, y)).toBeNull()
  })
})
