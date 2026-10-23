import { describe, expect, it } from 'vitest'

import { applySelectClick, baseNameRange, clampMenu, nextSort, parseSortPref, stepSelection } from './browser'

const order = ['a', 'b', 'c', 'd', 'e']

describe('applySelectClick', () => {
  it('普通单击只选这一条', () => {
    expect(applySelectClick(order, ['a', 'b'], 'c', 'a', { toggle: false, range: false })).toEqual(['c'])
  })
  it('Ctrl 加选与取消', () => {
    expect(applySelectClick(order, ['a'], 'c', 'a', { toggle: true, range: false })).toEqual(['a', 'c'])
    expect(applySelectClick(order, ['a', 'c'], 'c', 'a', { toggle: true, range: false })).toEqual(['a'])
  })
  it('Shift 从锚点连选,两个方向都行', () => {
    expect(applySelectClick(order, ['b'], 'd', 'b', { toggle: false, range: true })).toEqual(['b', 'c', 'd'])
    expect(applySelectClick(order, ['d'], 'b', 'd', { toggle: false, range: true })).toEqual(['b', 'c', 'd'])
  })
  it('Shift 没有锚点时只选这一条', () => {
    expect(applySelectClick(order, [], 'c', null, { toggle: false, range: true })).toEqual(['c'])
  })
  it('Ctrl+Shift 把连选并进原有选中,按列表顺序', () => {
    expect(applySelectClick(order, ['e'], 'c', 'a', { toggle: true, range: true })).toEqual(['a', 'b', 'c', 'e'])
  })
})

describe('stepSelection', () => {
  it('没有选中时 ↓ 选第一条、↑ 选最后一条', () => {
    expect(stepSelection(order, [], 1)).toBe('a')
    expect(stepSelection(order, [], -1)).toBe('e')
  })
  it('从最后一个选中的往前后走,到头就停', () => {
    expect(stepSelection(order, ['b'], 1)).toBe('c')
    expect(stepSelection(order, ['a'], -1)).toBe('a')
    expect(stepSelection(order, ['e'], 1)).toBe('e')
  })
  it('空列表返回 null', () => {
    expect(stepSelection([], [], 1)).toBeNull()
  })
})

describe('排序偏好', () => {
  it('脏值回落到按名称升序', () => {
    expect(parseSortPref(null)).toEqual({ key: 'NAME', desc: false })
    expect(parseSortPref('oops')).toEqual({ key: 'NAME', desc: false })
    expect(parseSortPref('{"key":"COLOR"}')).toEqual({ key: 'NAME', desc: false })
    expect(parseSortPref('{"key":"SIZE","desc":true}')).toEqual({ key: 'SIZE', desc: true })
  })
  it('点同一列换方向,换列时大小与时间默认降序', () => {
    expect(nextSort({ key: 'NAME', desc: false }, 'NAME')).toEqual({ key: 'NAME', desc: true })
    expect(nextSort({ key: 'NAME', desc: false }, 'SIZE')).toEqual({ key: 'SIZE', desc: true })
    expect(nextSort({ key: 'SIZE', desc: true }, 'NAME')).toEqual({ key: 'NAME', desc: false })
  })
})

describe('clampMenu', () => {
  it('放得下就贴着鼠标', () => {
    expect(clampMenu(100, 100, 200, 300, 1440, 900)).toEqual({ left: 100, top: 100 })
  })
  it('靠右下角时往里收', () => {
    expect(clampMenu(1400, 850, 200, 300, 1440, 900)).toEqual({ left: 1232, top: 592 })
  })
})

describe('baseNameRange', () => {
  it('文件只选主名,文件夹与没有扩展名的全选', () => {
    expect(baseNameRange('论文终稿.pdf', false)).toEqual([0, 4])
    expect(baseNameRange('archive.tar.gz', false)).toEqual([0, 11])
    expect(baseNameRange('.env', false)).toEqual([0, 4])
    expect(baseNameRange('README', false)).toEqual([0, 6])
    expect(baseNameRange('照片.2026', true)).toEqual([0, 7])
  })
})
