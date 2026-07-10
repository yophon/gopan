<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ArrowLeft, ArrowRight } from '@element-plus/icons-vue'
import * as pdfjs from 'pdfjs-dist'
import type { PDFDocumentProxy, RenderTask } from 'pdfjs-dist'
// Vite 把 worker 当静态资源打包,pdfjs 在独立 worker 线程里解析
import pdfWorkerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'

pdfjs.GlobalWorkerOptions.workerSrc = pdfWorkerUrl

const props = defineProps<{ url: string }>()

const containerEl = ref<HTMLDivElement | null>(null)
const canvasEl = ref<HTMLCanvasElement | null>(null)
const page = ref(1)
const pageCount = ref(0)
const loading = ref(true)
const error = ref<string | null>(null)

let doc: PDFDocumentProxy | null = null
let renderTask: RenderTask | null = null
/** 递增序号:URL/页码快速切换时丢弃过期的异步结果 */
let renderSeq = 0

async function loadDocument(url: string) {
  const seq = ++renderSeq
  loading.value = true
  error.value = null
  pageCount.value = 0
  renderTask?.cancel()
  renderTask = null
  const old = doc
  doc = null
  if (old) void old.destroy()

  try {
    const loaded = await pdfjs.getDocument({ url }).promise
    if (seq !== renderSeq) {
      void loaded.destroy()
      return
    }
    doc = loaded
    pageCount.value = loaded.numPages
    page.value = 1
    await renderPage()
  } catch (err) {
    if (seq !== renderSeq) return
    error.value = err instanceof Error ? err.message : 'PDF 加载失败'
  } finally {
    if (seq === renderSeq) loading.value = false
  }
}

async function renderPage() {
  if (!doc || !canvasEl.value) return
  const seq = renderSeq
  renderTask?.cancel()
  try {
    const p = await doc.getPage(page.value)
    if (seq !== renderSeq || !canvasEl.value) return

    // 按容器宽度自适应缩放,乘 devicePixelRatio 保证清晰度
    const base = p.getViewport({ scale: 1 })
    const maxWidth = Math.max((containerEl.value?.clientWidth ?? 800) - 16, 200)
    const cssScale = Math.min(maxWidth / base.width, 2)
    const dpr = window.devicePixelRatio || 1
    const viewport = p.getViewport({ scale: cssScale * dpr })

    const canvas = canvasEl.value
    canvas.width = viewport.width
    canvas.height = viewport.height
    canvas.style.width = `${viewport.width / dpr}px`
    canvas.style.height = `${viewport.height / dpr}px`

    const ctx = canvas.getContext('2d')
    if (!ctx) return
    renderTask = p.render({ canvasContext: ctx, viewport })
    await renderTask.promise
  } catch (err) {
    // 翻页/切文件触发的取消是正常路径,静默
    if (err instanceof Error && err.name === 'RenderingCancelledException') return
    if (seq !== renderSeq) return
    error.value = err instanceof Error ? err.message : 'PDF 渲染失败'
  }
}

function prevPage() {
  if (page.value <= 1) return
  page.value--
  void renderPage()
}

function nextPage() {
  if (page.value >= pageCount.value) return
  page.value++
  void renderPage()
}

watch(
  () => props.url,
  (url) => {
    void loadDocument(url)
  },
)

onMounted(() => {
  void loadDocument(props.url)
})

onBeforeUnmount(() => {
  renderSeq++
  renderTask?.cancel()
  if (doc) void doc.destroy()
  doc = null
})
</script>

<template>
  <div class="pdf-viewer">
    <div ref="containerEl" class="pdf-scroll" v-loading="loading">
      <el-empty v-if="error" :description="`PDF 加载失败:${error}`" />
      <canvas v-show="!error && !loading" ref="canvasEl" class="pdf-canvas" />
    </div>
    <div v-if="pageCount > 0 && !error" class="pdf-pager">
      <el-button :icon="ArrowLeft" circle size="small" :disabled="page <= 1" @click="prevPage" />
      <span class="pdf-page-label">{{ page }} / {{ pageCount }}</span>
      <el-button
        :icon="ArrowRight"
        circle
        size="small"
        :disabled="page >= pageCount"
        @click="nextPage"
      />
    </div>
  </div>
</template>

<style scoped>
.pdf-viewer {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  width: 100%;
  height: 100%;
  min-height: 0;
}
.pdf-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
  display: flex;
  justify-content: center;
  align-items: flex-start;
  padding: 8px;
}
.pdf-canvas {
  background: #fff;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.4);
  border-radius: 2px;
}
.pdf-pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 10px 0 2px;
}
.pdf-page-label {
  min-width: 64px;
  text-align: center;
  color: #e5e7eb;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
</style>
