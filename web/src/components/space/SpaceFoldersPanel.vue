<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { ArrowRight, Document, Folder, FolderOpened } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { SpaceFoldersDocument } from '@/api/gen/graphql'
import { formatBytes } from '@/utils/format'
import { barWidth, shareText } from '@/utils/space'

/**
 * 按文件夹逐层看(doc/23 三.1):从根开始,子文件夹按子树大小从大到小,点进去下钻。
 * 数字用的是已有的子树统计(异步重算),statsStale 时标「统计更新中」。
 */

const router = useRouter()

interface Crumb {
  id: string | null
  name: string
}

const stack = ref<Crumb[]>([{ id: null, name: '我的文件' }])
const current = computed(() => stack.value[stack.value.length - 1])

const { data, isFetching } = useQuery({
  queryKey: computed(() => ['space', 'folders', current.value.id]),
  queryFn: () => request(SpaceFoldersDocument, { parentId: current.value.id }),
})
const level = computed(() => data.value?.spaceFolders ?? null)
const folders = computed(() => level.value?.folders ?? [])
const levelTotal = computed(
  () => folders.value.reduce((s, f) => s + (f.subtreeBytes ?? 0), 0) + (level.value?.looseBytes ?? 0),
)
const maxBytes = computed(() =>
  Math.max(0, level.value?.looseBytes ?? 0, ...folders.value.map((f) => f.subtreeBytes ?? 0)),
)

function down(f: { id: string; name: string }) {
  stack.value = [...stack.value, { id: f.id, name: f.name }]
}

function up(i: number) {
  stack.value = stack.value.slice(0, i + 1)
}

function openInDrive(id: string | null) {
  void router.push(id ? `/drive/${id}` : '/drive')
}
</script>

<template>
  <div class="sf">
    <div class="sf-top">
      <el-breadcrumb separator="/" class="crumbs">
        <el-breadcrumb-item v-for="(c, i) in stack" :key="c.id ?? 'root'">
          <button v-if="i < stack.length - 1" type="button" class="crumb" @click="up(i)">{{ c.name }}</button>
          <span v-else>{{ c.name }}</span>
        </el-breadcrumb-item>
      </el-breadcrumb>
      <el-button size="small" :icon="FolderOpened" @click="openInDrive(current.id)">打开</el-button>
    </div>
    <p v-if="level" class="muted">{{ level.path }} · 共 {{ formatBytes(levelTotal) }}</p>

    <ul v-loading="isFetching && !level" class="rows">
      <li v-if="level && !folders.length && !level.looseFiles" class="empty">这一层是空的</li>
      <li
        v-for="f in folders"
        :key="f.id"
        class="row clickable"
        role="button"
        tabindex="0"
        :title="`${f.name}:${formatBytes(f.subtreeBytes ?? 0)},${f.subtreeCount ?? 0} 个文件,占这一层 ${shareText(f.subtreeBytes ?? 0, levelTotal)}`"
        @click="down(f)"
        @keydown.enter="down(f)"
      >
        <span class="row-name">
          <el-icon class="row-icon"><Folder /></el-icon>
          <span class="name-text">{{ f.name }}</span>
          <el-tag v-if="f.statsStale" size="small" type="info" effect="plain">统计更新中</el-tag>
        </span>
        <span class="track"><span class="fill" :style="{ width: `${barWidth(f.subtreeBytes ?? 0, maxBytes)}%` }" /></span>
        <span class="num">
          {{ f.statsStale ? '约 ' : '' }}{{ formatBytes(f.subtreeBytes ?? 0) }}
          <span class="muted"> · {{ f.subtreeCount ?? 0 }} 个</span>
        </span>
        <el-icon class="go"><ArrowRight /></el-icon>
      </li>
      <li
        v-if="level && level.looseFiles > 0"
        class="row"
        :title="`直接放在这一层的文件:${formatBytes(level.looseBytes)},${level.looseFiles} 个`"
      >
        <span class="row-name">
          <el-icon class="row-icon muted-icon"><Document /></el-icon>
          <span class="name-text muted-text">直接放在这一层的文件</span>
        </span>
        <span class="track"><span class="fill loose" :style="{ width: `${barWidth(level.looseBytes, maxBytes)}%` }" /></span>
        <span class="num">
          {{ formatBytes(level.looseBytes) }}<span class="muted"> · {{ level.looseFiles }} 个</span>
        </span>
        <span class="go" />
      </li>
    </ul>
  </div>
</template>

<style scoped>
.sf-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.crumbs {
  min-width: 0;
  line-height: 1.8;
}
.crumb {
  padding: 0;
  border: none;
  background: none;
  color: var(--el-color-primary);
  font: inherit;
  cursor: pointer;
}
.muted {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
p.muted {
  margin: 4px 0 8px;
}
.rows {
  max-width: 820px;
  min-height: 120px;
  margin: 0;
  padding: 0;
  list-style: none;
}
/* 每行各是一个 grid:数字列定宽,各行的条才一样长、能直接比 */
.row {
  display: grid;
  grid-template-columns: minmax(0, 1.3fr) minmax(0, 1fr) 170px 16px;
  align-items: center;
  gap: 10px;
  padding: 7px 4px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  font-size: 13px;
}
.row.clickable {
  cursor: pointer;
}
.row.clickable:hover {
  background: var(--el-fill-color-light);
}
.row-name {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.row-icon {
  flex: none;
  color: var(--el-color-primary);
}
.muted-icon,
.muted-text {
  color: var(--el-text-color-secondary);
}
.name-text {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.track {
  height: 8px;
  border-radius: 4px;
  background: var(--el-fill-color);
  overflow: hidden;
}
.fill {
  display: block;
  height: 100%;
  border-radius: 4px;
  background: var(--el-color-primary-light-3);
}
.fill.loose {
  background: var(--el-text-color-placeholder);
}
.num {
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  text-align: right;
}
.go {
  color: var(--el-text-color-secondary);
}
.empty {
  padding: 32px 0;
  text-align: center;
  color: var(--el-text-color-secondary);
}

@media (max-width: 767px) {
  .row {
    grid-template-columns: minmax(0, 1fr) auto 16px;
    row-gap: 4px;
  }
  .track {
    grid-column: 1 / 3;
    grid-row: 2;
  }
}
</style>
