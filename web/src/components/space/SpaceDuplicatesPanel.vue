<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useInfiniteQuery } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { Delete } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { SpaceDuplicatesDocument } from '@/api/gen/graphql'
import type { SpaceFileFieldsFragment } from '@/api/gen/graphql'
import { useSpaceCleanup } from '@/composables/space'
import { formatBytes, formatTime } from '@/utils/format'
import { notificationRoute } from '@/utils/notifications'
import { checkDupeSelection, defaultDupeSelection } from '@/utils/space'
import NodeIcon from '@/components/nodes/NodeIcon.vue'

/**
 * 重复文件(doc/23 三.1):同一份内容在盘里出现多次的,按「删掉多余的能省多少」排序。
 * 每组列出所有位置,默认留最早的一份(其余都勾上);一组一份都不留的不让删。
 */

const router = useRouter()
const { trash } = useSpaceCleanup()

const query = useInfiniteQuery({
  queryKey: ['space', 'dupes'],
  queryFn: ({ pageParam }) => request(SpaceDuplicatesDocument, { offset: pageParam, limit: 50 }),
  initialPageParam: 0,
  getNextPageParam: (last) => last.spaceDuplicates.nextOffset ?? null,
})
const groups = computed(() => query.data.value?.pages.flatMap((p) => p.spaceDuplicates.items) ?? [])

// 新加载的组按默认勾选;用户改过的组不再动
const selected = ref<string[]>([])
const seen = new Set<string>()
watch(
  groups,
  (list) => {
    const fresh = list.filter((g) => !seen.has(g.sha256))
    fresh.forEach((g) => seen.add(g.sha256))
    if (fresh.length) selected.value = [...selected.value, ...defaultDupeSelection(fresh)]
  },
  { immediate: true },
)

const check = computed(() => checkDupeSelection(groups.value, selected.value))

function toggle(id: string) {
  selected.value = selected.value.includes(id) ? selected.value.filter((x) => x !== id) : [...selected.value, id]
}

function open(f: SpaceFileFieldsFragment) {
  const to = notificationRoute({ kind: 'NODE', id: f.node.id, parentId: f.node.parentId })
  if (to) void router.push(to)
}

async function onDelete() {
  const c = check.value
  if (c.allGone.length) {
    ElMessage.warning(`有 ${c.allGone.length} 组一份都没留,每组至少留一份`)
    return
  }
  const ok = await trash(c.ids, '个重复文件', c.bytes)
  if (ok) {
    // 刷新后重新按默认勾选
    selected.value = []
    seen.clear()
  }
}
</script>

<template>
  <div class="sd">
    <p class="note">服务器上同一份内容本来只存一份,删重复文件省的是你的配额,不是服务器磁盘。</p>
    <div class="sd-bar">
      <span class="muted">
        <template v-if="check.ids.length">已选 {{ check.ids.length }} 个 · 能省 {{ formatBytes(check.bytes) }}</template>
        <template v-else>{{ groups.length }} 组</template>
        <template v-if="check.allGone.length"><span class="danger"> · {{ check.allGone.length }} 组一份都没留</span></template>
      </span>
      <el-button type="danger" plain size="small" :icon="Delete" :disabled="!check.ids.length" @click="onDelete">
        删掉选中的
      </el-button>
    </div>

    <div v-loading="query.isFetching.value && !query.data.value" class="groups">
      <el-empty v-if="!groups.length && !query.isFetching.value" description="盘里没有内容一样的文件" />
      <section v-for="g in groups" :key="g.sha256" class="group" :class="{ bad: check.allGone.includes(g.sha256) }">
        <header class="g-head">
          <span>{{ formatBytes(g.size) }} × {{ g.copies }} 份</span>
          <span class="muted">删掉多余的能省 {{ formatBytes(g.size * (g.copies - 1)) }}</span>
        </header>
        <ul class="g-files">
          <li v-for="(f, i) in g.files" :key="f.node.id" class="g-file">
            <el-checkbox :model-value="selected.includes(f.node.id)" @change="toggle(f.node.id)" />
            <NodeIcon :node="f.node" :size="24" />
            <div class="g-main">
              <button type="button" class="g-name" :title="`打开所在位置:${f.node.name}`" @click="open(f)">
                {{ f.node.name }}
              </button>
              <span class="muted g-path" :title="f.path">{{ f.path }} · {{ formatTime(f.node.updatedAt) }}</span>
            </div>
            <el-tag v-if="i === 0" size="small" type="success" effect="plain" class="g-tag">最早</el-tag>
          </li>
        </ul>
      </section>
      <div v-if="query.hasNextPage.value" class="more">
        <el-button link :loading="query.isFetchingNextPage.value" @click="query.fetchNextPage()">加载更多</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.note {
  margin: 0 0 10px;
  padding: 8px 12px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
}
.muted {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.danger {
  color: var(--el-color-danger);
}
.sd-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}
.groups {
  min-height: 160px;
}
.group {
  margin-bottom: 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  overflow: hidden;
}
.group.bad {
  border-color: var(--el-color-danger-light-5);
}
.g-head {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 4px 12px;
  padding: 8px 12px;
  background: var(--el-fill-color-light);
  font-size: 13px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}
.g-files {
  margin: 0;
  padding: 0 12px;
  list-style: none;
}
.g-file {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.g-file:last-child {
  border-bottom: none;
}
.g-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.g-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding: 0;
  border: none;
  background: none;
  color: var(--el-text-color-primary);
  font: inherit;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}
.g-name:hover {
  color: var(--el-color-primary);
  text-decoration: underline;
}
.g-path {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.g-tag {
  flex: none;
}
.more {
  padding: 8px 0;
  text-align: center;
}
</style>
