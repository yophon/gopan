<script setup lang="ts">
import { computed, ref } from 'vue'
import { CaretBottom, CaretTop, Check, Download, MoreFilled, Share, StarFilled } from '@element-plus/icons-vue'

import { formatTime } from '@/utils/format'
import NodeIcon from './NodeIcon.vue'
import { sizeText } from './nodeDisplay'
import { applySelectClick, DRAG_MIME, type SortKey } from './browser'
import type { NodeListItem } from './types'

/**
 * 桌面的文件列表(列表 / 网格两种形态),照着桌面文件管理器的习惯:
 * 单击选中(Ctrl / ⌘ 加选、Shift 连选),点名字或双击打开,右键出菜单(空白处也有),
 * 拖到文件夹上就是移动,把电脑里的文件拖到文件夹上就是传进去。
 * 选中状态由父组件持有(v-model:selected-ids),键盘快捷键也在父组件里接。
 */
const props = withDefaults(
  defineProps<{
    items: NodeListItem[]
    selectedIds: string[]
    view?: 'list' | 'grid'
    loading?: boolean
    /** 传了才显示可点的排序表头 */
    sort?: { key: SortKey; desc: boolean } | null
    /** 允许拖动条目移动到文件夹里 */
    movable?: boolean
    /** 行尾的快捷按钮(下载 / 分享 / 更多);回收站这类只读列表关掉 */
    rowActions?: boolean
    /** 行尾要不要「分享」(访客页不要) */
    rowShare?: boolean
    /** 第三列(默认是修改时间)的标题与取值,回收站、搜索用来放别的 */
    timeLabel?: string
    timeOf?: (node: NodeListItem) => string
    /** 能不能打开(回收站里的东西不能预览):关掉后名字不是链接,双击也不打开 */
    openable?: boolean
    /** 第三列可点(搜索结果点「位置」进所在文件夹) */
    timeLink?: boolean
    emptyText?: string
  }>(),
  { view: 'list', loading: false, sort: null, movable: false, rowActions: true, rowShare: true, openable: true, timeLabel: '修改时间', emptyText: '这里空空如也' },
)

const emit = defineEmits<{
  (e: 'update:selectedIds', ids: string[]): void
  (e: 'open', node: NodeListItem): void
  /** node 为 null:右键点在空白处 */
  (e: 'menu', node: NodeListItem | null, x: number, y: number): void
  (e: 'download', node: NodeListItem): void
  (e: 'share', node: NodeListItem): void
  (e: 'sort', key: SortKey): void
  (e: 'move', ids: string[], targetId: string): void
  (e: 'drop-files', dt: DataTransfer, targetId: string): void
  (e: 'time-click', node: NodeListItem): void
}>()

const selected = computed(() => new Set(props.selectedIds))
const anchor = ref<string | null>(null)
const allSelected = computed(() => props.items.length > 0 && props.items.every((n) => selected.value.has(n.id)))
const someSelected = computed(() => props.selectedIds.length > 0 && !allSelected.value)

function onRowClick(node: NodeListItem, e: MouseEvent) {
  const next = applySelectClick(
    props.items.map((n) => n.id),
    props.selectedIds,
    node.id,
    anchor.value,
    { toggle: e.ctrlKey || e.metaKey, range: e.shiftKey },
  )
  if (!e.shiftKey) anchor.value = node.id
  emit('update:selectedIds', next)
}

function toggleOne(node: NodeListItem) {
  anchor.value = node.id
  emit(
    'update:selectedIds',
    selected.value.has(node.id) ? props.selectedIds.filter((id) => id !== node.id) : [...props.selectedIds, node.id],
  )
}

function toggleAll() {
  emit('update:selectedIds', allSelected.value ? [] : props.items.map((n) => n.id))
}

function onRowMenu(node: NodeListItem, e: MouseEvent) {
  // 右键没选中的那一条:改成只选它;右键已选中的:对整组生效
  if (!selected.value.has(node.id)) {
    anchor.value = node.id
    emit('update:selectedIds', [node.id])
  }
  emit('menu', node, e.clientX, e.clientY)
}

function onMoreClick(node: NodeListItem, e: MouseEvent) {
  if (!selected.value.has(node.id)) emit('update:selectedIds', [node.id])
  const r = (e.currentTarget as HTMLElement).getBoundingClientRect()
  emit('menu', node, r.right - 4, r.bottom + 4)
}

function onBlankClick(e: MouseEvent) {
  if (e.target === e.currentTarget && !e.ctrlKey && !e.metaKey && !e.shiftKey) emit('update:selectedIds', [])
}

function onBlankMenu(e: MouseEvent) {
  emit('update:selectedIds', [])
  emit('menu', null, e.clientX, e.clientY)
}

// ---------- 拖动:条目拖到文件夹 = 移动;电脑里的文件拖到文件夹 = 传进去 ----------

const dragIds = ref<string[]>([])
const dropTarget = ref<string | null>(null)

function onDragStart(node: NodeListItem, e: DragEvent) {
  if (!props.movable || !e.dataTransfer) return
  const ids = selected.value.has(node.id) ? props.selectedIds : [node.id]
  if (!selected.value.has(node.id)) emit('update:selectedIds', [node.id])
  dragIds.value = ids
  e.dataTransfer.effectAllowed = 'move'
  e.dataTransfer.setData(DRAG_MIME, JSON.stringify(ids))
  e.dataTransfer.setData('text/plain', ids.length > 1 ? `${ids.length} 项` : node.name)
}

function onDragEnd() {
  dragIds.value = []
  dropTarget.value = null
}

function acceptsDrop(node: NodeListItem, e: DragEvent): 'move' | 'files' | null {
  if (node.kind !== 'FOLDER' || !e.dataTransfer) return null
  const types = Array.from(e.dataTransfer.types)
  if (types.includes(DRAG_MIME)) return props.movable && !dragIds.value.includes(node.id) ? 'move' : null
  if (types.includes('Files')) return 'files'
  return null
}

function onDragOver(node: NodeListItem, e: DragEvent) {
  const kind = acceptsDrop(node, e)
  if (!kind) return
  e.preventDefault()
  // 别让整页的「松开上传到当前文件夹」遮罩抢走这次放下
  e.stopPropagation()
  if (e.dataTransfer) e.dataTransfer.dropEffect = kind === 'move' ? 'move' : 'copy'
  dropTarget.value = node.id
}

function onDragLeave(node: NodeListItem, e: DragEvent) {
  const to = e.relatedTarget as Node | null
  if (to && (e.currentTarget as HTMLElement).contains(to)) return
  if (dropTarget.value === node.id) dropTarget.value = null
}

function onDrop(node: NodeListItem, e: DragEvent) {
  const kind = acceptsDrop(node, e)
  dropTarget.value = null
  if (!kind || !e.dataTransfer) return
  e.preventDefault()
  e.stopPropagation()
  if (kind === 'move') {
    let ids: string[] = []
    try {
      ids = JSON.parse(e.dataTransfer.getData(DRAG_MIME)) as string[]
    } catch {
      ids = dragIds.value
    }
    if (ids.length) emit('move', ids, node.id)
  } else {
    emit('drop-files', e.dataTransfer, node.id)
  }
  dragIds.value = []
}

function timeText(node: NodeListItem): string {
  return props.timeOf ? props.timeOf(node) : formatTime(node.updatedAt)
}

const columns: { key: SortKey; label: () => string; cls: string }[] = [
  { key: 'NAME', label: () => '名称', cls: 'c-name' },
  { key: 'SIZE', label: () => '大小', cls: 'c-size' },
  { key: 'UPDATED_AT', label: () => props.timeLabel, cls: 'c-time' },
]
</script>

<template>
  <div
    v-loading="loading && items.length === 0"
    class="node-browser"
    :class="`view-${view}`"
    @click="onBlankClick"
    @contextmenu.prevent.self="onBlankMenu"
  >
    <!-- 列表 -->
    <template v-if="view === 'list'">
      <div v-if="items.length" class="row head" @contextmenu.prevent.stop>
        <span class="c-check">
          <span
            class="check"
            :class="{ on: allSelected, some: someSelected }"
            role="checkbox"
            :aria-checked="allSelected"
            aria-label="全选"
            @click.stop="toggleAll"
          >
            <el-icon v-if="allSelected"><Check /></el-icon>
          </span>
        </span>
        <span
          v-for="col in columns"
          :key="col.key"
          :class="[col.cls, { sortable: !!sort, sorted: sort?.key === col.key }]"
          @click.stop="sort && emit('sort', col.key)"
        >
          {{ col.label() }}
          <el-icon v-if="sort?.key === col.key" class="sort-caret">
            <CaretBottom v-if="sort.desc" />
            <CaretTop v-else />
          </el-icon>
        </span>
        <span v-if="rowActions" class="c-actions" />
      </div>
      <div class="body" @click="onBlankClick" @contextmenu.prevent.self="onBlankMenu">
        <div
          v-for="node in items"
          :key="node.id"
          class="row item"
          :class="{ selected: selected.has(node.id), 'drop-target': dropTarget === node.id, dragging: dragIds.includes(node.id) }"
          :draggable="movable"
          :data-id="node.id"
          @click="onRowClick(node, $event)"
          @dblclick="openable && emit('open', node)"
          @contextmenu.prevent.stop="onRowMenu(node, $event)"
          @dragstart="onDragStart(node, $event)"
          @dragend="onDragEnd"
          @dragover="onDragOver(node, $event)"
          @dragleave="onDragLeave(node, $event)"
          @drop="onDrop(node, $event)"
        >
          <span class="c-check">
            <span class="check" :class="{ on: selected.has(node.id) }" @click.stop="toggleOne(node)">
              <el-icon v-if="selected.has(node.id)"><Check /></el-icon>
            </span>
          </span>
          <span class="c-name">
            <NodeIcon :node="node" :size="28" />
            <a v-if="openable" class="name" :title="node.name" @click.stop="emit('open', node)">{{ node.name }}</a>
            <span v-else class="name plain" :title="node.name">{{ node.name }}</span>
            <el-icon v-if="node.starred" class="star" title="已收藏"><StarFilled /></el-icon>
          </span>
          <span class="c-size" :class="{ stale: node.statsStale }">{{ sizeText(node) }}</span>
          <span class="c-time" :title="timeText(node)">
            <a v-if="timeLink" class="time-link" @click.stop="emit('time-click', node)">{{ timeText(node) }}</a>
            <template v-else>{{ timeText(node) }}</template>
          </span>
          <span v-if="rowActions" class="c-actions" @dblclick.stop>
            <el-button link :icon="Download" :title="node.kind === 'FOLDER' ? '打包下载' : '下载'" @click.stop="emit('download', node)" />
            <el-button v-if="rowShare" link :icon="Share" title="分享" @click.stop="emit('share', node)" />
            <el-button link :icon="MoreFilled" title="更多" @click.stop="onMoreClick(node, $event)" />
          </span>
        </div>
      </div>
    </template>

    <!-- 网格 -->
    <div v-else class="grid" @click="onBlankClick" @contextmenu.prevent.self="onBlankMenu">
      <div
        v-for="node in items"
        :key="node.id"
        class="tile"
        :class="{ selected: selected.has(node.id), 'drop-target': dropTarget === node.id, dragging: dragIds.includes(node.id) }"
        :draggable="movable"
        :data-id="node.id"
        @click="onRowClick(node, $event)"
        @dblclick="openable && emit('open', node)"
        @contextmenu.prevent.stop="onRowMenu(node, $event)"
        @dragstart="onDragStart(node, $event)"
        @dragend="onDragEnd"
        @dragover="onDragOver(node, $event)"
        @dragleave="onDragLeave(node, $event)"
        @drop="onDrop(node, $event)"
      >
        <span class="check" :class="{ on: selected.has(node.id) }" @click.stop="toggleOne(node)">
          <el-icon v-if="selected.has(node.id)"><Check /></el-icon>
        </span>
        <div class="tile-visual">
          <NodeIcon :node="node" :size="node.kind === 'FOLDER' ? 72 : 120" class="tile-icon" />
          <el-icon v-if="node.starred" class="star tile-star" title="已收藏"><StarFilled /></el-icon>
        </div>
        <a class="tile-name" :title="node.name" @click.stop="openable ? emit('open', node) : onRowClick(node, $event)">{{ node.name }}</a>
        <span class="tile-meta">{{ sizeText(node) }}</span>
      </div>
    </div>

    <div v-if="!loading && items.length === 0" class="empty" @contextmenu.prevent.stop="onBlankMenu">
      <slot name="empty">{{ emptyText }}</slot>
    </div>
  </div>
</template>

<style scoped>
.node-browser {
  position: relative;
  min-height: 240px;
  font-size: 14px;
  padding-bottom: 48px;
  user-select: none;
}

/* ---------- 列表 ---------- */
.row {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr) 110px var(--nb-time-w, 160px) 112px;
  align-items: center;
  min-height: 48px;
  padding: 0 8px 0 4px;
  border-radius: 10px;
}
.row.head {
  min-height: 36px;
  font-size: 12px;
  font-weight: 600;
  color: var(--gp-ink-3);
  border-radius: 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
  margin-bottom: 4px;
}
.head .sortable {
  cursor: pointer;
}
.head .sortable:hover,
.head .sorted {
  color: var(--gp-ink);
}
.sort-caret {
  vertical-align: -2px;
  margin-left: 2px;
}
.row.item {
  cursor: default;
  transition: background-color 0.12s;
}
.row.item:hover {
  background: var(--el-fill-color-light);
}
.row.item.selected {
  background: var(--el-color-primary-light-9);
}
.row.item.drop-target,
.tile.drop-target {
  background: var(--el-color-primary-light-8);
  box-shadow: 0 0 0 2px var(--el-color-primary) inset;
}
.row.item.dragging,
.tile.dragging {
  opacity: 0.5;
}
.c-name {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--el-text-color-primary);
  cursor: pointer;
}
.name:hover {
  text-decoration: underline;
  text-underline-offset: 3px;
}
.name.plain {
  cursor: default;
}
.name.plain:hover {
  text-decoration: none;
}
.star {
  flex: none;
  color: var(--gp-star);
  font-size: 14px;
}
.c-size,
.c-time {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  padding-right: 12px;
}
.time-link {
  color: inherit;
  cursor: pointer;
}
.time-link:hover {
  color: var(--el-text-color-primary);
  text-decoration: underline;
  text-underline-offset: 3px;
}
.head .c-size,
.head .c-time {
  font-size: 12px;
}
.c-size.stale {
  opacity: 0.6;
}
.c-actions {
  display: flex;
  justify-content: flex-end;
  gap: 2px;
  opacity: 0;
  transition: opacity 0.12s;
}
.c-actions :deep(.el-button) {
  width: 30px;
  height: 30px;
  margin: 0;
  color: var(--gp-ink-2);
}
.c-actions :deep(.el-button:hover) {
  color: var(--gp-ink);
}
.row.item:hover .c-actions,
.row.item.selected .c-actions {
  opacity: 1;
}

/* 勾选框:没选时只在悬停 / 已有选中时出现,省得一整列空圈 */
.check {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border: 1.5px solid var(--el-border-color-darker);
  border-radius: 50%;
  color: var(--el-bg-color);
  font-size: 12px;
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.12s;
}
.check.on {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary);
}
.check.some {
  border-color: var(--el-color-primary);
  background: linear-gradient(var(--el-color-primary), var(--el-color-primary)) center / 8px 2px no-repeat;
}
.row:hover .check,
.tile:hover .check,
.check.on,
.check.some,
.node-browser:has(.check.on) .check {
  opacity: 1;
}
.c-check {
  display: flex;
  justify-content: center;
}

/* 紧凑宽度:时间列让位 */
@media (max-width: 1023px) {
  .row {
    grid-template-columns: 40px minmax(0, 1fr) 100px 112px;
  }
  .c-time {
    display: none;
  }
}

/* ---------- 网格 ---------- */
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 12px;
  padding-top: 4px;
}
.tile {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 10px 10px 12px;
  border-radius: 14px;
  cursor: default;
  transition: background-color 0.12s;
}
.tile:hover {
  background: var(--el-fill-color-light);
}
.tile.selected {
  background: var(--el-color-primary-light-9);
}
.tile .check {
  position: absolute;
  top: 10px;
  left: 10px;
  z-index: 1;
  background: var(--el-bg-color);
}
.tile .check.on {
  background: var(--el-color-primary);
}
.tile-visual {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  aspect-ratio: 4 / 3;
  border-radius: 10px;
  background: var(--gp-panel);
  overflow: hidden;
}
.tile-visual :deep(.node-thumb) {
  width: 100% !important;
  height: 100% !important;
  border-radius: 0;
}
.tile-visual :deep(.node-icon) {
  width: 44px !important;
  height: 44px !important;
  font-size: 36px !important;
}
.tile-star {
  position: absolute;
  right: 8px;
  bottom: 8px;
  filter: drop-shadow(0 1px 2px rgb(0 0 0 / 0.25));
}
.tile-name {
  display: -webkit-box;
  width: 100%;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  text-align: center;
  word-break: break-all;
  font-size: 13px;
  line-height: 1.35;
  color: var(--el-text-color-primary);
  cursor: pointer;
}
.tile-name:hover {
  text-decoration: underline;
  text-underline-offset: 3px;
}
.tile-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 72px 0 24px;
  color: var(--el-text-color-secondary);
}
</style>
