import { describe, expect, it } from 'vitest'

import { canApply, isValidName, planRename, renameOne, splitName } from './batchRename'

const file = (id: string, name: string) => ({ id, name, isFolder: false })
const folder = (id: string, name: string) => ({ id, name, isFolder: true })

describe('splitName', () => {
  it('keeps the extension apart only for files with a real one', () => {
    expect(splitName('a.tar.gz', false)).toEqual({ base: 'a.tar', ext: 'gz' })
    expect(splitName('.bashrc', false)).toEqual({ base: '.bashrc', ext: '' })
    expect(splitName('README', false)).toEqual({ base: 'README', ext: '' })
    expect(splitName('v1.2', true)).toEqual({ base: 'v1.2', ext: '' })
  })
})

describe('renameOne', () => {
  it('numbers with # padded to its width and * as the old name, keeping the extension', () => {
    const rule = { kind: 'template' as const, template: '第##集', start: 1 }
    expect(renameOne(file('a', 'xx.mkv'), rule, 0)).toBe('第01集.mkv')
    expect(renameOne(file('a', 'xx.mkv'), rule, 11)).toBe('第12集.mkv')
    expect(renameOne(file('a', 'xx.mkv'), { kind: 'template', template: '#', start: 99 }, 1)).toBe('100.mkv')
    expect(renameOne(file('a', 'IMG.jpg'), { kind: 'template', template: '2024-* (#)', start: 1 }, 0)).toBe('2024-IMG (1).jpg')
    expect(renameOne(folder('d', 'a.b'), { kind: 'template', template: '*_##', start: 1 }, 2)).toBe('a.b_03')
  })

  it('replaces every occurrence in the name but not the extension', () => {
    const rule = { kind: 'replace' as const, find: 'mp4', replace: '' }
    expect(renameOne(file('a', 'mp4-剪辑mp4.mp4'), rule, 0)).toBe('-剪辑.mp4')
    expect(renameOne(file('a', 'abc.txt'), { kind: 'replace', find: '', replace: 'x' }, 0)).toBe('abc.txt')
  })

  it('changes or drops the extension of files only', () => {
    expect(renameOne(file('a', 'a.jpeg'), { kind: 'extension', ext: '.jpg' }, 0)).toBe('a.jpg')
    expect(renameOne(file('a', 'a.txt'), { kind: 'extension', ext: '' }, 0)).toBe('a')
    expect(renameOne(file('a', 'notes'), { kind: 'extension', ext: 'md' }, 0)).toBe('notes.md')
    expect(renameOne(folder('d', 'dir'), { kind: 'extension', ext: 'md' }, 0)).toBe('dir')
  })
})

describe('planRename', () => {
  it('flags duplicates inside the batch, clashes with other entries and invalid names', () => {
    const items = [file('a', 'a.txt'), file('b', 'b.txt'), file('c', 'c.txt')]
    const same = planRename(items, { kind: 'template', template: '同', start: 1 })
    expect(same.every((p) => p.problem === 'duplicate')).toBe(true)
    expect(canApply(same)).toBe(false)

    const plan = planRename(items, { kind: 'template', template: '#', start: 1 }, ['1.txt', 'a.txt', '其它.txt'])
    expect(plan.map((p) => p.newName)).toEqual(['1.txt', '2.txt', '3.txt'])
    expect(plan[0].problem).toBe('exists')
    expect(canApply(plan)).toBe(false)

    expect(planRename([file('a', 'a.txt')], { kind: 'replace', find: 'a', replace: 'x/y' })[0].problem).toBe('invalid')
  })

  it('lets the batch take names its own members give up (swaps)', () => {
    const plan = planRename([file('a', '1.txt'), file('b', '2.txt')], { kind: 'template', template: '#', start: 2 }, ['1.txt', '2.txt'])
    expect(plan.map((p) => p.newName)).toEqual(['2.txt', '3.txt'])
    expect(plan.every((p) => p.problem === null)).toBe(true)
    expect(canApply(plan)).toBe(true)
  })

  it('cannot apply when nothing changes', () => {
    expect(canApply(planRename([file('a', 'a.txt')], { kind: 'replace', find: 'zz', replace: 'y' }))).toBe(false)
  })
})

describe('isValidName', () => {
  it('matches the server rules', () => {
    expect(isValidName('')).toBe(false)
    expect(isValidName('..')).toBe(false)
    expect(isValidName('a/b')).toBe(false)
    expect(isValidName('好'.repeat(85))).toBe(true) // 255 字节
    expect(isValidName('好'.repeat(86))).toBe(false)
  })
})
