import { describe, expect, it } from 'vitest'

import { filterQuery, hasConditions, parseFilterQuery, timeRange, toContentFilter, toSearchFilter } from './searchFilter'

describe('search filter', () => {
  const now = new Date(2026, 9, 25, 15, 30) // 2026-10-25 15:30 本地时间

  it('reads the query string and drops what it does not know', () => {
    const s = parseFilterQuery({ type: 'image,VIDEO,movie,image', t: '7d', size: 'gt1g', sort: 'size', folder: 'f1' })
    expect(s).toEqual({ types: ['IMAGE', 'VIDEO'], time: '7d', size: 'gt1g', sort: 'size', folder: 'f1' })
    expect(parseFilterQuery({ t: 'range', size: 'huge', sort: 'random' })).toEqual({ types: [], time: '', size: '', sort: '', folder: null })
    expect(parseFilterQuery({ t: '2026-01-01~2026-01-31' }).time).toBe('2026-01-01~2026-01-31')
  })

  it('writes back only what is set', () => {
    const s = parseFilterQuery({ type: 'document', sort: 'name' })
    expect(filterQuery(s)).toEqual({ type: 'document', t: undefined, size: undefined, sort: 'name', folder: undefined })
    expect(hasConditions(s)).toBe(true)
    expect(hasConditions(parseFilterQuery({ sort: 'name' }))).toBe(false)
  })

  it('turns presets and ranges into [after, before)', () => {
    expect(timeRange('today', now).after).toEqual(new Date(2026, 9, 25))
    expect(timeRange('year', now).after).toEqual(new Date(2026, 0, 1))
    expect(timeRange('older', now).before!.getTime()).toBe(now.getTime() - 365 * 24 * 3600 * 1000)
    const r = timeRange('2026-02-10~2026-02-01', now)
    expect(r.after).toEqual(new Date(2026, 1, 1))
    expect(r.before).toEqual(new Date(2026, 1, 11))
    expect(timeRange('', now)).toEqual({})
  })

  it('builds the GraphQL filter', () => {
    expect(toSearchFilter(parseFilterQuery({}), now)).toBeNull()
    const f = toSearchFilter(parseFilterQuery({ type: 'video', size: '100m-1g', sort: 'size', folder: 'f1' }), now)
    expect(f).toEqual({ types: ['VIDEO'], minSize: 100 * 1024 * 1024, maxSize: 1024 * 1024 * 1024, folderId: 'f1', order: 'SIZE' })
    expect(toSearchFilter(parseFilterQuery({ size: 'lt1m' }), now)).toEqual({ maxSize: 1024 * 1024 - 1 })
    expect(toContentFilter(parseFilterQuery({ type: 'folder,document', sort: 'name' }), now)).toEqual({ types: ['DOCUMENT'] })
    expect(toContentFilter(parseFilterQuery({ type: 'folder' }), now)).toBeNull()
  })
})
