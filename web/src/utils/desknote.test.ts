import { describe, expect, it } from 'vitest'

import {
  applyDeskNoteOp,
  changeTouchesFolder,
  createDeskNoteSync,
  deskNoteColors,
  formatRemind,
  isDeskNoteName,
  isDue,
  parseDeskNote,
  serializeDeskNote,
  splitLinks,
  type DeskNote,
} from './desknote'

const sample = `{
  "title": "ToDo",
  "items": [
    { "text": "买牛奶", "done": false },
    { "text": "交周报", "done": true, "remind": "2026-11-19 09:00", "reminded": true }
  ],
  "style": { "opacity": 0.9, "fontSize": 15, "textColor": "#C9D1D9", "bgColor": "#34424D", "checkboxes": true },
  "future": { "keep": 1 }
}`

function note(): DeskNote {
  return parseDeskNote(sample)!
}

describe('parseDeskNote', () => {
  it('认出便签', () => {
    const n = note()
    expect(n.title).toBe('ToDo')
    expect(n.items).toHaveLength(2)
  })
  it('不是便签的 json 不认', () => {
    expect(parseDeskNote('{"a":1}')).toBeNull()
    expect(parseDeskNote('[1,2]')).toBeNull()
    expect(parseDeskNote('{"title":"x","items":[{"t":1}]}')).toBeNull()
    expect(parseDeskNote('not json')).toBeNull()
  })
  it('文件名要是 .json', () => {
    expect(isDeskNoteName('20261118-204521-c358b2.json')).toBe(true)
    expect(isDeskNoteName('a.JSON')).toBe(true)
    expect(isDeskNoteName('a.txt')).toBe(false)
    expect(isDeskNoteName(null)).toBe(false)
  })
})

describe('applyDeskNoteOp', () => {
  it('勾选不改原对象', () => {
    const n = note()
    const r = applyDeskNoteOp(n, { kind: 'toggle', index: 0, text: '买牛奶', done: true })!
    expect(r.items[0].done).toBe(true)
    expect(n.items[0].done).toBe(false)
  })
  it('条目挪了位置:按文字找回同一条', () => {
    const moved: DeskNote = { ...note(), items: [{ text: '新加的' }, ...note().items] }
    const r = applyDeskNoteOp(moved, { kind: 'toggle', index: 0, text: '买牛奶', done: true })!
    expect(r.items[0].done).toBeUndefined()
    expect(r.items[1].done).toBe(true)
  })
  it('那一条在别处被改掉了:返回 null', () => {
    expect(applyDeskNoteOp(note(), { kind: 'remove', index: 0, text: '已经不在了' })).toBeNull()
  })
  it('改文字;清空 = 删除', () => {
    const r = applyDeskNoteOp(note(), { kind: 'edit', index: 0, text: '买牛奶', newText: '  买酸奶 ' })!
    expect(r.items[0].text).toBe('买酸奶')
    const r2 = applyDeskNoteOp(note(), { kind: 'edit', index: 0, text: '买牛奶', newText: '   ' })!
    expect(r2.items).toHaveLength(1)
  })
  it('添加和删除', () => {
    const r = applyDeskNoteOp(note(), { kind: 'add', text: ' 第三条 ' })!
    expect(r.items[2]).toEqual({ text: '第三条', done: false })
    const r2 = applyDeskNoteOp(note(), { kind: 'remove', index: 1, text: '交周报' })!
    expect(r2.items.map((i) => i.text)).toEqual(['买牛奶'])
  })
  it('改标题:换行变空格,空了用 ToDo', () => {
    expect(applyDeskNoteOp(note(), { kind: 'title', title: '周\n计划' })!.title).toBe('周 计划')
    expect(applyDeskNoteOp(note(), { kind: 'title', title: '  ' })!.title).toBe('ToDo')
  })
  it('写回保留不认识的字段和条目上的其他字段', () => {
    const r = applyDeskNoteOp(note(), { kind: 'toggle', index: 1, text: '交周报', done: false })!
    const back = JSON.parse(serializeDeskNote(r))
    expect(back.future).toEqual({ keep: 1 })
    expect(back.style.bgColor).toBe('#34424D')
    expect(back.items[1].remind).toBe('2026-11-19 09:00')
  })
})

describe('提醒', () => {
  const now = new Date(2026, 10, 19, 10, 0)
  it('说法和桌面端一致', () => {
    expect(formatRemind('2026-11-19 15:00', now)).toBe('15:00')
    expect(formatRemind('2026-11-20 09:05', now)).toBe('明天 09:05')
    expect(formatRemind('2026-12-01 08:00', now)).toBe('12月1日 08:00')
    expect(formatRemind('2027-01-02 08:00', now)).toBe('2027-1-2 08:00')
    expect(formatRemind('', now)).toBe('')
  })
  it('到点且没做完才算过期', () => {
    expect(isDue({ text: 'a', remind: '2026-11-19 09:00', reminded: true })).toBe(true)
    expect(isDue({ text: 'a', remind: '2026-11-19 09:00', reminded: true, done: true })).toBe(false)
    expect(isDue({ text: 'a', remind: '2026-11-19 09:00' })).toBe(false)
  })
})

describe('splitLinks', () => {
  it('切出链接,去掉结尾标点', () => {
    expect(splitLinks('看一下 https://example.com/a?b=1。然后')).toEqual([
      { text: '看一下 ' },
      { text: 'https://example.com/a?b=1', url: 'https://example.com/a?b=1' },
      { text: '。然后' },
    ])
    expect(splitLinks('去 www.example.com, 好')).toEqual([
      { text: '去 ' },
      { text: 'www.example.com', url: 'http://www.example.com' },
      { text: ', 好' },
    ])
    expect(splitLinks('没有链接')).toEqual([{ text: '没有链接' }])
  })
})

describe('changeTouchesFolder', () => {
  it('只认便签所在的文件夹', () => {
    expect(changeTouchesFolder({ t: 'files', f: ['a', 'b'] }, 'b')).toBe(true)
    expect(changeTouchesFolder({ t: 'files', f: ['a'] }, 'b')).toBe(false)
    expect(changeTouchesFolder({ t: 'files', f: [''] }, null)).toBe(true) // 根目录
    expect(changeTouchesFolder({ t: 'files', all: true }, 'b')).toBe(true)
    expect(changeTouchesFolder({ t: 'resync' }, 'b')).toBe(true)
    expect(changeTouchesFolder({ t: 'chat' }, 'b')).toBe(false)
  })
})

describe('deskNoteColors', () => {
  it('用便签的颜色,没有就用默认', () => {
    expect(deskNoteColors(note())).toEqual({ bg: '#34424D', fg: '#C9D1D9' })
    expect(deskNoteColors({ title: '', items: [] })).toEqual({ bg: '#34424D', fg: '#A9B1B7' })
  })
})

describe('createDeskNoteSync', () => {
  /** 假的服务器:sha256 用版本号代替;base 对不上就报冲突 */
  function fakeRemote(initial: DeskNote) {
    let content = serializeDeskNote(initial)
    let version = 1
    const notices: string[] = []
    const conflict = new Error('EDIT_CONFLICT')
    const remote = {
      get note() {
        return JSON.parse(content) as DeskNote
      },
      /** 模拟桌面端在中间改了文件 */
      edit(fn: (n: DeskNote) => void) {
        const n = JSON.parse(content) as DeskNote
        fn(n)
        content = serializeDeskNote(n)
        version++
      },
      notices,
      sync: createDeskNoteSync({
        load: async () => ({ content, sha256: `v${version}` }),
        save: async (c, b) => {
          if (b !== `v${version}`) throw conflict
          content = c
          version++
          return `v${version}`
        },
        isConflict: (e) => e === conflict,
        onChange: () => {},
        onNotice: (_l, m) => notices.push(m),
      }),
    }
    return remote
  }

  it('一步步存,后一步基于前一步', async () => {
    const r = fakeRemote(note())
    await r.sync.load()
    void r.sync.run({ kind: 'add', text: '第三条' })
    await r.sync.run({ kind: 'toggle', index: 2, text: '第三条', done: true })
    expect(r.note.items.map((i) => [i.text, !!i.done])).toEqual([
      ['买牛奶', false],
      ['交周报', true],
      ['第三条', true],
    ])
    expect(r.sync.pending).toBe(0)
  })

  it('桌面端中途加了一条:重放这一步,不盖掉桌面端的改动', async () => {
    const r = fakeRemote(note())
    await r.sync.load()
    r.edit((n) => n.items.unshift({ text: '桌面端加的', done: false }))
    await r.sync.run({ kind: 'toggle', index: 0, text: '买牛奶', done: true })
    expect(r.note.items.map((i) => [i.text, !!i.done])).toEqual([
      ['桌面端加的', false],
      ['买牛奶', true],
      ['交周报', true],
    ])
    expect(r.sync.view?.items).toHaveLength(3)
    expect(r.notices).toEqual([])
  })

  it('那一条在桌面端被删了:放弃这一步,显示换成最新', async () => {
    const r = fakeRemote(note())
    await r.sync.load()
    r.edit((n) => n.items.splice(0, 1))
    await r.sync.run({ kind: 'edit', index: 0, text: '买牛奶', newText: '买酸奶' })
    expect(r.note.items.map((i) => i.text)).toEqual(['交周报'])
    expect(r.sync.view?.items.map((i) => i.text)).toEqual(['交周报'])
    expect(r.notices).toHaveLength(1)
  })
})
