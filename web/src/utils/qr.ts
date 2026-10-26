import { encode } from 'uqr'

/** 二维码画成一条 SVG path(每行连续的黑格并成一段),配 viewBox="0 0 size size" 用 */
export function qrPath(text: string): { size: number; d: string } {
  const { size, data } = encode(text, { ecc: 'M', border: 1 })
  let d = ''
  data.forEach((row, y) => {
    for (let x = 0; x < size; x++) {
      if (!row[x]) continue
      let run = 1
      while (x + run < size && row[x + run]) run++
      d += `M${x} ${y}h${run}v1h-${run}z`
      x += run - 1
    }
  })
  return { size, d }
}
