/**
 * 随对话发的图(doc/35):发之前在浏览器里缩到长边 1568 的 JPEG。
 * 图跟着消息存进对话记录(每条 1 MB 上限),所以一条消息里的图合计不超过 700 KB(按 data URL 的长度算),
 * 超了先降质量重压一遍,还超就不让发。
 */

export const MAX_IMAGES = 4
const MAX_PX = 1568
const BUDGET = 700_000

export async function shrinkImage(file: Blob, quality = 0.8): Promise<string> {
  let bmp: ImageBitmap
  try {
    bmp = await createImageBitmap(file, { imageOrientation: 'from-image' })
  } catch {
    throw new Error('这张图浏览器打不开(HEIC 之类的格式),换成 JPEG 或 PNG 再发')
  }
  const scale = Math.min(1, MAX_PX / Math.max(bmp.width, bmp.height))
  const canvas = document.createElement('canvas')
  canvas.width = Math.max(1, Math.round(bmp.width * scale))
  canvas.height = Math.max(1, Math.round(bmp.height * scale))
  const ctx = canvas.getContext('2d')!
  ctx.fillStyle = '#fff' // 透明的 PNG 转 JPEG 不要变黑
  ctx.fillRect(0, 0, canvas.width, canvas.height)
  ctx.drawImage(bmp, 0, 0, canvas.width, canvas.height)
  bmp.close()
  return canvas.toDataURL('image/jpeg', quality)
}

export interface PendingImage {
  file: Blob
  url: string
}

/** 发之前:合计超了就整体降质量重压;还超报错 */
export async function fitBudget(images: PendingImage[]): Promise<string[]> {
  const total = (urls: string[]) => urls.reduce((n, u) => n + u.length, 0)
  let urls = images.map((i) => i.url)
  if (total(urls) <= BUDGET) return urls
  urls = await Promise.all(images.map((i) => shrinkImage(i.file, 0.55)))
  if (total(urls) <= BUDGET) return urls
  throw new Error('图片太大了,少发几张')
}
