/** 歌词(doc/31 二):服务端把同名 .lrc 解析好,GET /lrc/{歌的 nodeId} 回这个形状;没有歌词 404 */

export interface LyricLine {
  /** 秒;纯文本歌词为 -1 */
  t: number
  text: string
}

export interface Lyrics {
  synced: boolean
  lines: LyricLine[]
}

/** 当前该亮哪一行:最后一个时间不晚于 t 的;还没到第一行为 -1 */
export function currentLyricIndex(lines: LyricLine[], t: number): number {
  let lo = 0
  let hi = lines.length - 1
  let ans = -1
  while (lo <= hi) {
    const mid = (lo + hi) >> 1
    if (lines[mid].t <= t + 0.05) {
      ans = mid
      lo = mid + 1
    } else {
      hi = mid - 1
    }
  }
  return ans
}

export function lyricsUrl(nodeId: string): string {
  return `/lrc/${encodeURIComponent(nodeId)}`
}
