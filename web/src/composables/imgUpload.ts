import { singleFlightRefresh } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { IMG_UPLOAD_PATH, parseUploadResponse } from '@/utils/imgUpload'
import type { ImgUploadResult } from '@/utils/imgUpload'

interface RawResponse {
  status: number
  body: string
}

function send(file: File, token: string, onProgress?: (ratio: number) => void): Promise<RawResponse> {
  return new Promise((resolve, reject) => {
    // 用 XHR 不用 fetch:要上传进度
    const xhr = new XMLHttpRequest()
    xhr.open('POST', IMG_UPLOAD_PATH)
    xhr.setRequestHeader('Authorization', `Bearer ${token}`)
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && e.total > 0) onProgress?.(e.loaded / e.total)
    }
    xhr.onload = () => resolve({ status: xhr.status, body: xhr.responseText })
    xhr.onerror = () => reject(new Error('网络错误,没传上去'))
    xhr.onabort = () => reject(new Error('已取消'))
    const form = new FormData()
    form.append('file', file, file.name || 'image.png')
    xhr.send(form)
  })
}

/**
 * 网页粘贴 / 拖入上传到图床文件夹(doc/22 四.3):带登录的 access token 调 /api/img/upload。
 * token 过期(401)刷新一次再传。失败抛 Error(message 是中文)。
 */
export async function uploadImage(file: File, onProgress?: (ratio: number) => void): Promise<ImgUploadResult> {
  const auth = useAuthStore()
  const used = auth.accessToken ?? ''
  let res = await send(file, used, onProgress)
  if (res.status === 401 && (await singleFlightRefresh(used))) {
    res = await send(file, auth.accessToken ?? '', onProgress)
  }
  const parsed = parseUploadResponse(res.status, res.body)
  if (!parsed.ok) throw new Error(parsed.message)
  return parsed.result
}
