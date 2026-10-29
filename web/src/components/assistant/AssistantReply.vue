<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRouter } from 'vue-router'

import { refsIn, splitReply, withRefChips, type FileRef } from '@/assistant/refs'
import { ensureRefs, refNodes, type RefNode } from '@/assistant/refNodes'
import { toggleAssistant } from '@/assistant/state'
import { useBreakpoints } from '@/composables/breakpoints'
import { openPreview } from '@/composables/preview'
import { formatBytes } from '@/utils/format'
import { miniMarkdown } from '@/utils/miniMarkdown'

/**
 * 助手的一段回答(doc/35 六):Markdown,里面的 [名字](gopan:<id>) 变成能点的标签;
 * 只列文件的段落变成卡片,图片、视频直接显示缩略图。
 */

const props = defineProps<{ text: string }>()

const router = useRouter()
const { isMobile } = useBreakpoints()

const blocks = computed(() => splitReply(props.text))
watch(
  () => refsIn(props.text).map((r) => r.id),
  (ids) => ensureRefs(ids),
  { immediate: true },
)

const FOLDER_ICON = '<svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true"><path fill="currentColor" d="M3 6.5A1.5 1.5 0 0 1 4.5 5h4.6l2 2h8.4A1.5 1.5 0 0 1 21 8.5v9a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 17.5z"/></svg>'
const FILE_ICON = '<svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true"><path fill="currentColor" d="M6 3h8l5 5v12a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1zm7 1.5V9h4.5z"/></svg>'

function escape(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

function chip(ref: FileRef): string {
  const n = refNodes.get(ref.id)
  if (n === null) return `<span class="gref gone">${escape(ref.name)}(已不在)</span>`
  const icon = n?.kind === 'FOLDER' ? FOLDER_ICON : FILE_ICON
  return `<a class="gref" href="#" data-gid="${escape(ref.id)}">${icon}${escape(n?.name ?? ref.name)}</a>`
}

function html(md: string): string {
  return withRefChips(md, miniMarkdown, chip)
}

const isVisual = (n: RefNode | null | undefined) => n?.preview.kind === 'IMAGE' || n?.preview.kind === 'VIDEO'
const thumb = (n: RefNode, single: boolean) => (single ? n.preview.largeUrl ?? n.preview.thumbUrl : n.preview.thumbUrl ?? n.preview.largeUrl)
const where = (n: RefNode) => {
  const p = n.path ?? ''
  const dir = p.slice(0, p.lastIndexOf('/'))
  return dir || '我的文件'
}

function open(id: string) {
  const n = refNodes.get(id)
  if (!n) return
  if (n.kind === 'FOLDER') {
    void router.push(`/drive/${n.id}`)
    if (isMobile.value) toggleAssistant(false) // 手机上面板占满屏,让出来看文件夹
  } else {
    openPreview([n.id], 0)
  }
}

function onClick(e: MouseEvent) {
  const a = (e.target as HTMLElement).closest<HTMLElement>('a.gref')
  if (!a) return
  e.preventDefault()
  open(a.dataset.gid ?? '')
}
</script>

<template>
  <div class="reply" @click="onClick">
    <template v-for="(b, i) in blocks" :key="i">
      <!-- miniMarkdown 先整段转义再加标签;标签自己转义名字 -->
      <div v-if="b.kind === 'md'" class="md" v-html="html(b.text)" />
      <div v-else class="cards">
        <div class="tiles" :class="{ single: b.refs.filter((r) => isVisual(refNodes.get(r.id))).length === 1 }">
          <template v-for="r in b.refs" :key="r.id">
            <button v-if="refNodes.get(r.id) && isVisual(refNodes.get(r.id))" type="button" class="tile" :title="refNodes.get(r.id)!.name" @click="open(r.id)">
              <img
                v-if="thumb(refNodes.get(r.id)!, b.refs.length === 1)"
                :src="thumb(refNodes.get(r.id)!, b.refs.length === 1)!"
                :alt="refNodes.get(r.id)!.name"
                loading="lazy"
              />
              <span v-else class="tile-name">{{ refNodes.get(r.id)!.name }}</span>
              <span v-if="refNodes.get(r.id)!.preview.kind === 'VIDEO'" class="play">▶</span>
            </button>
          </template>
        </div>
        <template v-for="r in b.refs" :key="r.id">
          <button v-if="refNodes.get(r.id) && !isVisual(refNodes.get(r.id))" type="button" class="row" @click="open(r.id)">
            <span class="row-icon" v-html="refNodes.get(r.id)!.kind === 'FOLDER' ? FOLDER_ICON : FILE_ICON" />
            <span class="row-main">
              <span class="row-name">{{ refNodes.get(r.id)!.name }}</span>
              <span class="row-meta">{{ where(refNodes.get(r.id)!) }}<template v-if="refNodes.get(r.id)!.kind === 'FILE'"> · {{ formatBytes(refNodes.get(r.id)!.size) }}</template></span>
            </span>
          </button>
          <div v-else-if="refNodes.get(r.id) === null" class="row gone">{{ r.name }}(已不在)</div>
          <div v-else-if="!refNodes.has(r.id)" class="row pending">{{ r.name }}</div>
        </template>
      </div>
    </template>
  </div>
</template>

<style scoped>
.md :deep(p),
.md :deep(ul),
.md :deep(ol),
.md :deep(pre) {
  margin: 0 0 6px;
}
.md :deep(ul),
.md :deep(ol) {
  padding-left: 20px;
}
.md :deep(code) {
  padding: 0 4px;
  border-radius: 4px;
  background: var(--el-fill-color);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.92em;
}
.md :deep(pre) {
  overflow-x: auto;
  padding: 8px;
  border-radius: 6px;
  background: var(--el-fill-color);
}
.md :deep(pre code) {
  padding: 0;
  background: none;
}
.md :deep(.gref) {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  max-width: 100%;
  padding: 0 6px;
  border-radius: 6px;
  background: var(--el-fill-color);
  color: var(--el-color-primary);
  text-decoration: none;
  vertical-align: baseline;
}
.md :deep(a.gref:hover) {
  background: var(--el-color-primary-light-9);
}
.md :deep(.gref svg) {
  flex: none;
}
.md :deep(.gref.gone) {
  color: var(--el-text-color-secondary);
  text-decoration: line-through;
}
.cards {
  margin: 6px 0;
}
.tiles {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
}
.tiles:empty {
  display: none;
}
.tiles.single {
  grid-template-columns: 1fr;
}
.tile {
  position: relative;
  overflow: hidden;
  aspect-ratio: 1;
  padding: 0;
  border: none;
  border-radius: 8px;
  background: var(--el-fill-color);
  cursor: pointer;
}
.tiles.single .tile {
  aspect-ratio: auto;
  max-height: 320px;
}
.tile img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.tiles.single .tile img {
  max-height: 320px;
  object-fit: contain;
}
.tile-name {
  display: block;
  padding: 6px;
  font-size: 12px;
  word-break: break-all;
}
.play {
  position: absolute;
  top: 50%;
  left: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  margin: -18px 0 0 -18px;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  font-size: 14px;
}
.row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  margin-top: 6px;
  padding: 8px 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-bg-color);
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
button.row:hover {
  border-color: var(--el-color-primary-light-5);
}
.row-icon {
  display: flex;
  color: var(--el-color-primary);
}
.row-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.row-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row-meta {
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row.gone {
  color: var(--el-text-color-secondary);
  cursor: default;
  text-decoration: line-through;
}
.row.pending {
  color: var(--el-text-color-secondary);
  cursor: default;
}
</style>
