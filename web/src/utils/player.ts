/** 网页音乐播放队列的切歌规则(doc/22 二.4)。纯函数,单测直接引。 */

export type PlayMode = 'order' | 'one' | 'shuffle'

export const PLAY_MODES: PlayMode[] = ['order', 'one', 'shuffle']

export const playModeLabel: Record<PlayMode, string> = {
  order: '顺序播放',
  one: '单曲循环',
  shuffle: '随机播放',
}

export function nextPlayMode(mode: PlayMode): PlayMode {
  return PLAY_MODES[(PLAY_MODES.indexOf(mode) + 1) % PLAY_MODES.length]
}

export function parsePlayMode(raw: unknown): PlayMode {
  return PLAY_MODES.includes(raw as PlayMode) ? (raw as PlayMode) : 'order'
}

/** 随机播放的顺序:start 排第一(正在放的那首),其余洗牌 */
export function shuffleOrder(len: number, start: number, rng: () => number = Math.random): number[] {
  const rest: number[] = []
  for (let i = 0; i < len; i++) if (i !== start) rest.push(i)
  for (let i = rest.length - 1; i > 0; i--) {
    const j = Math.floor(rng() * (i + 1))
    ;[rest[i], rest[j]] = [rest[j], rest[i]]
  }
  return start >= 0 && start < len ? [start, ...rest] : rest
}

export interface QueuePos {
  len: number
  index: number
  mode: PlayMode
  /** 随机播放的顺序(shuffleOrder 的结果);其余模式不用 */
  order: number[]
}

export interface QueueMove {
  index: number
  /** 随机播放转完一圈、重新洗过的顺序;没变为 undefined */
  order?: number[]
}

/**
 * 下一首。auto = 一首放完自动切,否则是点了「下一首」。
 * 顺序:放完最后一首就停(null),手动切绕回第一首;
 * 单曲循环:自动切重放本首,手动切照顺序走;
 * 随机:按洗好的顺序走,转完一圈重新洗(新一圈不以刚放完的那首开头)接着放。
 */
export function nextIndex(q: QueuePos, auto: boolean, rng: () => number = Math.random): QueueMove | null {
  const { len, index, mode } = q
  if (len <= 0) return null
  if (mode === 'one' && auto) return { index }
  if (mode === 'shuffle') {
    if (len === 1) return auto ? null : { index: 0 }
    let order = q.order.length === len ? q.order : shuffleOrder(len, index, rng)
    let pos = order.indexOf(index)
    if (pos < 0) {
      order = shuffleOrder(len, index, rng)
      pos = 0
    }
    if (pos < len - 1) return order === q.order ? { index: order[pos + 1] } : { index: order[pos + 1], order }
    const fresh = shuffleOrder(len, -1, rng)
    if (fresh[0] === index) [fresh[0], fresh[len - 1]] = [fresh[len - 1], fresh[0]]
    return { index: fresh[0], order: fresh }
  }
  if (index < len - 1) return { index: index + 1 }
  return auto ? null : { index: 0 }
}

/** 上一首:顺序 / 单曲循环往前一首,第一首再往前绕到最后;随机按洗好的顺序往回,到头停在本首 */
export function prevIndex(q: QueuePos): QueueMove | null {
  const { len, index, mode } = q
  if (len <= 0) return null
  if (mode === 'shuffle') {
    const pos = q.order.indexOf(index)
    return { index: pos > 0 ? q.order[pos - 1] : index }
  }
  return { index: index > 0 ? index - 1 : len - 1 }
}

/** 放了超过 3 秒再点「上一首」是从头放本首(各家播放器的习惯) */
export const PREV_RESTART_SEC = 3
