<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { PurgeTrashDocument, SpaceOverviewDocument } from '@/api/gen/graphql'
import { useSpaceCleanup } from '@/composables/space'
import { formatBytes } from '@/utils/format'
import { barWidth, categoryLabel, fillCategories, shareText } from '@/utils/space'

/** 空间总览(doc/23 三.1):已用 / 配额、按类型的条形、回收站与清空、重复文件的概要 */

const emit = defineEmits<{ go: [tab: string] }>()

const router = useRouter()
const { refresh } = useSpaceCleanup()

const { data, isFetching } = useQuery({
  queryKey: ['space', 'overview'],
  queryFn: () => request(SpaceOverviewDocument),
})
const ov = computed(() => data.value?.spaceOverview ?? null)

const percent = computed(() => {
  const o = ov.value
  if (!o || o.quotaBytes <= 0) return 0
  return Math.min(100, Math.round((o.usedBytes / o.quotaBytes) * 100))
})

const rows = computed(() => fillCategories(ov.value?.byType ?? []))
const maxBytes = computed(() => Math.max(0, ...rows.value.map((r) => r.bytes)))
const typedTotal = computed(() => rows.value.reduce((s, r) => s + r.bytes, 0))

async function onPurgeTrash() {
  const o = ov.value
  if (!o) return
  try {
    await ElMessageBox.confirm(
      `回收站里的 ${o.trashFiles} 个文件(${formatBytes(o.trashBytes)})会被彻底删除,撤不回。`,
      '清空回收站?',
      { confirmButtonText: '清空', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await request(PurgeTrashDocument)
    ElMessage.success('回收站已清空')
  } catch (err) {
    ElMessage.error(errorText(err, '清空失败'))
  }
  refresh()
}
</script>

<template>
  <div v-loading="isFetching && !ov" class="ov">
    <template v-if="ov">
      <section class="usage">
        <div class="usage-num">
          {{ formatBytes(ov.usedBytes) }}
          <span class="usage-quota">/ {{ formatBytes(ov.quotaBytes) }}</span>
        </div>
        <el-progress
          :percentage="percent"
          :status="percent >= 90 ? 'exception' : percent >= 70 ? 'warning' : undefined"
          :stroke-width="10"
        />
        <p class="muted">已用里含回收站的 {{ formatBytes(ov.trashBytes) }}:还占着配额,彻底删除或到期后才释放。</p>
      </section>

      <section class="block">
        <h4>按类型</h4>
        <ul class="bars">
          <li
            v-for="r in rows"
            :key="r.category"
            class="bar-row"
            :title="`${categoryLabel[r.category]}:${formatBytes(r.bytes)},${r.files} 个文件,占 ${shareText(r.bytes, typedTotal)}`"
          >
            <span class="bar-label">{{ categoryLabel[r.category] }}</span>
            <span class="bar-track"><span class="bar-fill" :style="{ width: `${barWidth(r.bytes, maxBytes)}%` }" /></span>
            <span class="bar-num">
              {{ formatBytes(r.bytes) }}<span class="muted"> · {{ r.files }} 个 · {{ shareText(r.bytes, typedTotal) }}</span>
            </span>
          </li>
        </ul>
      </section>

      <section class="tiles">
        <div class="tile">
          <span class="tile-label">回收站</span>
          <span class="tile-value">{{ formatBytes(ov.trashBytes) }}</span>
          <span class="muted">{{ ov.trashFiles }} 个文件</span>
          <span class="tile-actions">
            <el-button size="small" @click="router.push('/trash')">打开回收站</el-button>
            <el-button size="small" type="danger" plain :icon="Delete" :disabled="!ov.trashFiles" @click="onPurgeTrash">
              清空回收站
            </el-button>
          </span>
        </div>
        <div class="tile">
          <span class="tile-label">重复文件</span>
          <span class="tile-value">{{ ov.duplicateGroups ? formatBytes(ov.duplicateBytes) : '没有' }}</span>
          <span class="muted">
            <template v-if="ov.duplicateGroups">{{ ov.duplicateGroups }} 组,删掉多余的能省这么多配额</template>
            <template v-else>盘里没有内容一样的文件</template>
          </span>
          <span class="tile-actions">
            <el-button size="small" :disabled="!ov.duplicateGroups" @click="emit('go', 'duplicates')">去看看</el-button>
          </span>
        </div>
      </section>
      <p class="muted note">服务器上同一份内容本来只存一份,删重复文件省的是你的配额,不是服务器磁盘。</p>
    </template>
  </div>
</template>

<style scoped>
.ov {
  min-height: 200px;
}
.muted {
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
p.muted {
  margin: 6px 0 0;
}
.usage {
  max-width: 560px;
}
.usage-num {
  margin-bottom: 6px;
  font-size: 22px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.usage-quota {
  font-size: 14px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}
.block {
  margin-top: 20px;
}
.block h4 {
  margin: 0 0 8px;
  font-size: 14px;
}
.bars {
  max-width: 720px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.bar-row {
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr) minmax(0, auto);
  align-items: center;
  gap: 10px;
  padding: 5px 0;
  font-size: 13px;
}
.bar-label {
  white-space: nowrap;
}
.bar-track {
  height: 8px;
  border-radius: 4px;
  background: var(--el-fill-color);
  overflow: hidden;
}
.bar-fill {
  display: block;
  height: 100%;
  border-radius: 4px;
  background: var(--el-color-primary-light-3);
}
.bar-num {
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  text-align: right;
}
.tiles {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 12px;
  margin-top: 20px;
  max-width: 720px;
}
.tile {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 14px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
}
.tile-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.tile-value {
  font-size: 18px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.tile-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 6px;
}
.tile-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.note {
  margin-top: 12px !important;
}

@media (max-width: 767px) {
  .bar-row {
    grid-template-columns: 72px minmax(0, 1fr);
  }
  .bar-num {
    grid-column: 2;
    text-align: left;
  }
}
</style>
