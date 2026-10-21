/** 图床上传接口(doc/22 四.3):文件校验、返回解析、各家上传工具的配置模板。纯函数,单测直接引。 */

export const IMG_UPLOAD_PATH = '/api/img/upload'
export const IMG_EXTS = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'avif', 'bmp', 'svg']
export const IMG_MAX_BYTES = 20 * 1024 * 1024

/** 能不能传:返回错误文案,能传为 null。剪贴板截图没有扩展名,按 type 认 */
export function imageUploadError(file: { name: string; type: string; size: number }): string | null {
  const ext = file.name.includes('.') ? (file.name.toLowerCase().split('.').pop() ?? '') : ''
  const typeOk = /^image\/(jpeg|png|gif|webp|avif|bmp|svg\+xml)$/i.test(file.type)
  if (!(ext ? IMG_EXTS.includes(ext) : typeOk)) return '只能传图片(jpg、png、gif、webp、avif、bmp、svg)'
  if (file.size > IMG_MAX_BYTES) return '图片超过 20 MB'
  if (file.size === 0) return '空文件'
  return null
}

export interface ImgUploadResult {
  url: string
  markdown: string
  html: string
  bbcode: string
  name: string
  size: number
  node_id: string
  link_id: string
}

const UPLOAD_ERRORS: Record<string, string> = {
  UNAUTHENTICATED: '登录已过期,请刷新页面',
  FORBIDDEN: '没有权限',
  // UNSUPPORTED_TYPE / TOO_LARGE 用服务端的话(里面有收哪些格式、上限多大)
  QUOTA_EXCEEDED: '云盘空间不足',
  RATE_LIMITED: '传得太快了,稍后再试',
  LINKS_DISABLED: '本站没有开启直链',
  BUSY: '同时上传的太多了,稍后再试',
}

/** 解析接口返回:200 是结果 JSON,4xx / 5xx 是 {"error":{"code","message"}} */
export function parseUploadResponse(
  status: number,
  body: string,
): { ok: true; result: ImgUploadResult } | { ok: false; code: string; message: string } {
  let json: unknown = null
  try {
    json = JSON.parse(body)
  } catch {
    // 网关报错时可能是 HTML
  }
  if (status >= 200 && status < 300) {
    const r = json as Partial<ImgUploadResult> | null
    if (r && typeof r.url === 'string') return { ok: true, result: r as ImgUploadResult }
    return { ok: false, code: 'BAD_RESPONSE', message: '服务器返回的内容看不懂' }
  }
  const err = (json as { error?: { code?: unknown; message?: unknown } } | null)?.error
  const code = typeof err?.code === 'string' ? err.code : `HTTP_${status}`
  const message =
    UPLOAD_ERRORS[code] ??
    (typeof err?.message === 'string' && err.message
      ? err.message
      : status === 413
        ? '图片太大'
        : `上传失败(HTTP ${status})`)
  return { ok: false, code, message }
}

// ---------- 上传工具的配置模板 ----------

export interface UploadToolConfigs {
  endpoint: string
  picgo: string
  sharex: string
  sharexFileName: string
  upic: string
  typora: string
  curl: string
}

/** origin 为站点地址,key 为图床密钥(没有就用占位,提示用户换上自己的) */
export function uploadToolConfigs(origin: string, key: string, siteName = 'gopan'): UploadToolConfigs {
  const base = origin.replace(/\/+$/, '')
  const endpoint = `${base}${IMG_UPLOAD_PATH}`
  const auth = `Bearer ${key}`
  const host = (() => {
    try {
      return new URL(base).host
    } catch {
      return 'gopan'
    }
  })()

  const picgo = [
    '插件:web-uploader(自定义 Web 图床)',
    `API 地址:${endpoint}`,
    'POST 参数名:file',
    'JSON 路径:url',
    `自定义请求头:${JSON.stringify({ Authorization: auth })}`,
    '自定义 Body:留空',
  ].join('\n')

  const sharex = JSON.stringify(
    {
      Version: '15.0.0',
      Name: `${siteName} 图床(${host})`,
      DestinationType: 'ImageUploader',
      RequestMethod: 'POST',
      RequestURL: endpoint,
      Headers: { Authorization: auth },
      Body: 'MultipartFormData',
      FileFormName: 'file',
      URL: '{json:url}',
      ErrorMessage: '{json:error.message}',
    },
    null,
    2,
  )

  const upic = [
    '图床类型:自定义',
    `API 地址:${endpoint}`,
    '请求方式:POST',
    '使用 Base64:否',
    '文件字段名:file',
    `其他字段 → Header:Authorization = ${auth}`,
    'URL 路径:["url"]',
  ].join('\n')

  const typora = [
    'Typora 通过 PicGo 上传:',
    '1. 先按上面的 PicGo 配置好 web-uploader 并设为默认图床;',
    '2. Typora → 偏好设置 → 图像 → 上传服务选「PicGo.app」(或 PicGo-Core);',
    '3. 「插入图片时」选「上传图片」,粘贴截图就会自动换成直链。',
  ].join('\n')

  const curl = `curl -H "Authorization: ${auth}" -F "file=@a.png" ${endpoint}`

  return {
    endpoint,
    picgo,
    sharex,
    sharexFileName: `${host.replace(/[^a-z0-9.-]/gi, '_')}.sxcu`,
    upic,
    typora,
    curl,
  }
}
