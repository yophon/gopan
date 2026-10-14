import { ref } from 'vue'
import { defineStore } from 'pinia'

/**
 * 外部分享进来的内容(系统分享菜单 → PWA share_target / 安卓原生壳 → 传输助手)。
 *
 * 生产方(sw.js 的 POST share_target 落到 IndexedDB 后由 utils/shareTarget 读出、
 * 安卓原生插件、GET share_target 的 query)只管 push,然后把路由切到 /chat;
 * ChatView 挂载时与 pending 变化时 take() 走:文本进草稿,文件直接发。
 */
export interface IncomingShare {
  text?: string
  files?: File[]
}

export const useIncomingShareStore = defineStore('incomingShare', () => {
  const pending = ref<IncomingShare | null>(null)

  /** 合并进待处理:text 以换行拼接,files 追加 */
  function push(share: IncomingShare): void {
    const text = share.text?.trim() ?? ''
    const files = share.files ?? []
    if (!text && files.length === 0) return
    const cur = pending.value
    const nextText = [cur?.text ?? '', text].filter((s) => s.length > 0).join('\n')
    const nextFiles = [...(cur?.files ?? []), ...files]
    pending.value = {
      ...(nextText ? { text: nextText } : {}),
      ...(nextFiles.length ? { files: nextFiles } : {}),
    }
  }

  /** 取走并清空 */
  function take(): IncomingShare | null {
    const p = pending.value
    pending.value = null
    return p
  }

  return { pending, push, take }
})
