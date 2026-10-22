import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

import { describe, expect, it } from 'vitest'

import { parseChangelog, parseInline } from './changelog'

describe('parseChangelog', () => {
  it('按 ## 切版本,版本 / 日期 / 标题按 · 分开,开头的说明不展示', () => {
    const src = [
      '# 更新日志',
      '写给维护者的说明',
      '',
      '## 1.1.0 · 2026-07-13 · 第二版',
      '',
      '一段说明',
      '',
      '- **新功能**:A',
      '- 修复 `b`',
      '',
      '## 1.0.0 · 2026-07-12',
      '- 第一条',
    ].join('\n')
    const got = parseChangelog(src)
    expect(got.map((e) => [e.version, e.date, e.title])).toEqual([
      ['1.1.0', '2026-07-13', '第二版'],
      ['1.0.0', '2026-07-12', ''],
    ])
    expect(got[0].intro).toEqual([[{ kind: 'text', text: '一段说明' }]])
    expect(got[0].items).toEqual([
      [{ kind: 'strong', text: '新功能' }, { kind: 'text', text: ':A' }],
      [{ kind: 'text', text: '修复 ' }, { kind: 'code', text: 'b' }],
    ])
    expect(got[1].items).toHaveLength(1)
  })

  it('缩进的续行接在上一条后面,CRLF 也认', () => {
    const got = parseChangelog('## 2.0.0 · 2026-01-01\r\n- 第一行\r\n  接着写\r\n- 第二条\r\n')
    expect(got[0].items.map((i) => i.map((s) => s.text).join(''))).toEqual(['第一行接着写', '第二条'])
  })

  it('标题里再有 · 也保留', () => {
    expect(parseChangelog('## 3.0.0 · 2026-10-15 · 安卓 · 原生')[0].title).toBe('安卓 · 原生')
  })

  it('仓库里的 CHANGELOG.md 能解析:版本从新到旧、每个版本都有日期和条目', () => {
    const src = readFileSync(fileURLToPath(new URL('../../../CHANGELOG.md', import.meta.url)), 'utf8')
    const got = parseChangelog(src)
    expect(got.length).toBeGreaterThan(5)
    for (const e of got) {
      expect(e.version).toMatch(/^\d+\.\d+\.\d+$/)
      expect(e.date).toMatch(/^\d{4}-\d{2}-\d{2}$/)
      expect(e.items.length).toBeGreaterThan(0)
    }
    const key = (v: string) => v.split('.').map((n) => n.padStart(4, '0')).join('.')
    const versions = got.map((e) => key(e.version))
    expect([...versions].sort().reverse()).toEqual(versions)
  })
})

describe('parseInline', () => {
  it('没配对的标记原样当文字', () => {
    expect(parseInline('a ** b ` c')).toEqual([{ kind: 'text', text: 'a ** b ` c' }])
  })
  it('不产出 HTML:尖括号原样', () => {
    expect(parseInline('<b>x</b>')).toEqual([{ kind: 'text', text: '<b>x</b>' }])
  })
})
