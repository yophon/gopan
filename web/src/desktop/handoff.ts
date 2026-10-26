/**
 * 桌面端里网页发起的上传交给外壳的传输引擎(doc/29):缩到托盘照常跑,退出再打开接着传。
 *
 * 选文件、拖进来照旧走网页自己的流程(拖进文件夹、同名问替换都不变),只在入队那一步把 File / FileSystemHandle
 * 用 WebView2 的 postMessageWithAdditionalObjects 交给外壳,外壳读出本地路径;再调 desktop_upload_files 说传到哪。
 * 网页不报路径,外壳只认 WebView2 交来的对象(见 desktop/src-tauri/src/handoff.rs)。
 */
import { desktopWindow, invokeDesktop } from './bridge'

const PREFIX = 'gopan-desktop-files:'

interface WebView2Bridge {
  postMessageWithAdditionalObjects?: (message: unknown, objects: unknown[]) => void
}

function webview(): WebView2Bridge | null {
  return (window as unknown as { chrome?: { webview?: WebView2Bridge } }).chrome?.webview ?? null
}

/** 上传交给外壳(桥接成功、WebView2 支持交文件) */
export function nativeUploads(): boolean {
  return desktopWindow() !== null && typeof webview()?.postMessageWithAdditionalObjects === 'function'
}

export type Handoff = File | FileSystemHandle

export interface UploadTarget {
  /** 云端文件夹;null = 根目录「我的文件」 */
  parentId: string | null
  /** 发到传输助手(外壳自己找传输助手文件夹) */
  toChat?: boolean
  /** 这些名字的文件替换同名的(已经问过) */
  replace?: string[]
  /** 选文件夹上传:只交第一个文件,外壳按它的相对路径层数找回选的那个文件夹 */
  rootDepth?: number
}

let seq = 0

/** 交给外壳上传;返回入队了几项 */
export function uploadViaDesktop(objects: Handoff[], target: UploadTarget): Promise<number> {
  const bridge = webview()
  if (!bridge?.postMessageWithAdditionalObjects || objects.length === 0) return Promise.resolve(0)
  seq += 1
  const id = `${Date.now().toString(36)}-${seq}-${Math.random().toString(36).slice(2, 8)}`
  bridge.postMessageWithAdditionalObjects(PREFIX + id, objects)
  return invokeDesktop<number>('desktop_upload_files', {
    requestId: id,
    parentId: target.parentId,
    toChat: target.toChat ?? false,
    replace: target.replace ?? [],
    rootDepth: target.rootDepth ?? null,
  })
}

/**
 * 拖进来的东西:必须在 drop 事件里同步取(事件一结束 DataTransfer 就清空了),文件夹要 FileSystemHandle 才有路径。
 * 返回的 Promise 可以晚点等。
 */
export function collectDropped(dt: DataTransfer): Promise<Handoff[]> {
  const pending: Promise<Handoff | null>[] = []
  for (const item of Array.from(dt.items ?? [])) {
    if (item.kind !== 'file') continue
    const file = item.getAsFile()
    const asHandle = (item as DataTransferItem & { getAsFileSystemHandle?: () => Promise<FileSystemHandle | null> }).getAsFileSystemHandle
    if (asHandle) pending.push(asHandle.call(item).then((h) => h ?? file, () => file))
    else pending.push(Promise.resolve(file))
  }
  return Promise.all(pending).then((list) => list.filter((x): x is Handoff => x !== null))
}

/** 顶层的文件(不含文件夹):拿来问同名替换 */
export function topLevelFiles(objects: Handoff[]): { name: string }[] {
  return objects.filter((o) => (o instanceof File ? true : o.kind === 'file'))
}
