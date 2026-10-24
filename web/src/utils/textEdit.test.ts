import { describe, expect, it } from 'vitest'

import { canEditText, insertIndent, MAX_TEXT_EDIT } from './textEdit'

describe('insertIndent', () => {
  it('光标处插两个空格,光标跟到后面', () => {
    expect(insertIndent('ab', 1, 1)).toEqual({ text: 'a  b', cursor: 3 })
  })
  it('有选区时替换掉选中的', () => {
    expect(insertIndent('hello', 1, 4)).toEqual({ text: 'h  o', cursor: 3 })
  })
  it('越界的位置收回到文本范围内', () => {
    expect(insertIndent('ab', 5, 9)).toEqual({ text: 'ab  ', cursor: 4 })
    expect(insertIndent('ab', 2, 0)).toEqual({ text: 'ab  ', cursor: 4 })
  })
})

describe('canEditText', () => {
  it('只编辑不超过上限的文本', () => {
    expect(canEditText('TEXT', 10)).toBe(true)
    expect(canEditText('TEXT', MAX_TEXT_EDIT)).toBe(true)
    expect(canEditText('TEXT', MAX_TEXT_EDIT + 1)).toBe(false)
    expect(canEditText('IMAGE', 10)).toBe(false)
    expect(canEditText(undefined, 10)).toBe(false)
  })
})
