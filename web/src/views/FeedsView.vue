<script setup lang="ts">
import { computed, h, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, Plus, Upload } from '@element-plus/icons-vue'

import { getErrorMessage, request } from '@/api/client'
import { errorText } from '@/api/errors'
import { ExportOpmlDocument, FeedsDocument, ImportOpmlDocument } from '@/api/gen/graphql'
import type { FeedFieldsFragment } from '@/api/gen/graphql'
import { useBreakpoints } from '@/composables/breakpoints'
import { useFetchEnabled } from '@/composables/jobs'
import { formatRelative, formatTime } from '@/utils/format'
import { feedKindTag, feedModeLabel, feedStatus, intervalText } from '@/utils/feeds'
import { withoutQuery } from '@/utils/notifications'
import FeedFormDialog from '@/components/feeds/FeedFormDialog.vue'
import FeedDetailDrawer from '@/components/feeds/FeedDetailDrawer.vue'
import PageHelp from '@/components/PageHelp.vue'

type Feed = FeedFieldsFragment

/**
 * 订阅(doc/23 四.4):RSS / 播客 / GitHub Releases。列表、添加(先预览)、详情抽屉、OPML 导入导出。
 * ?id= 打开那个订阅的详情(订阅通知点进来),用完从地址里去掉。
 */

const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()
const { isMobile, isCompact } = useBreakpoints()
const fetchEnabled = useFetchEnabled()

const { data, isFetching } = useQuery({
  queryKey: ['feeds'],
  queryFn: () => request(FeedsDocument),
})
const feeds = computed<Feed[]>(() => data.value?.feeds ?? [])

function asFeed(row: unknown): Feed {
  return row as Feed
}

/** 最后更新:最新条目的时间;还没条目就说上次检查 */
function updatedText(f: Feed): string {
  if (f.latestAt) return formatRelative(f.latestAt)
  return f.lastCheckedAt ? `检查于 ${formatRelative(f.lastCheckedAt)}` : '还没检查'
}

function nextText(f: Feed): string {
  if (!f.enabled || !f.folder) return '—'
  return formatTime(f.nextCheckAt).slice(5)
}

// ---------- 详情 / 添加 ----------

const detail = ref<{ visible: boolean; id: string | null }>({ visible: false, id: null })
const addVisible = ref(false)

function openDetail(id: string) {
  detail.value = { visible: true, id }
}

watch(
  () => route.query.id,
  (id) => {
    if (typeof id !== 'string' || !id) return
    openDetail(id)
    void router.replace({ query: withoutQuery(route.query, ['id']) })
  },
  { immediate: true },
)

function onAdded(f: Feed) {
  void queryClient.invalidateQueries({ queryKey: ['feeds'] })
  void queryClient.invalidateQueries({ queryKey: ['jobs'] })
  openDetail(f.id)
}

// ---------- OPML ----------

const opmlInput = ref<HTMLInputElement | null>(null)
const importing = ref(false)

async function onOpmlChosen(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (file.size > 2 << 20) {
    ElMessage.warning('OPML 文件太大了(超过 2 MB)')
    return
  }
  importing.value = true
  try {
    const res = (await request(ImportOpmlDocument, { opml: await file.text() })).importOpml
    void queryClient.invalidateQueries({ queryKey: ['feeds'] })
    const lines = [`新加 ${res.added} 个,已经订阅过的 ${res.existed} 个${res.failed.length ? `,${res.failed.length} 个没加上` : ''}。`]
    await ElMessageBox.alert(
      h('div', [
        h('p', { style: 'margin:0 0 6px' }, lines[0]),
        h('p', { style: 'margin:0 0 6px;font-size:12px;color:var(--el-text-color-secondary)' }, '播客按「下载附件」、其余按「只记录」订阅,不补下旧内容。'),
        res.failed.length
          ? h(
              'ul',
              { style: 'margin:0;padding-left:18px;font-size:12px;line-height:1.7;max-height:200px;overflow:auto;word-break:break-all' },
              res.failed.map((f) => h('li', `${f.url}:${f.reason}`)),
            )
          : null,
      ]),
      '导入完成',
      { confirmButtonText: '知道了' },
    ).catch(() => undefined)
  } catch (err) {
    ElMessage.error({ message: getErrorMessage(err) ?? errorText(err, '导入失败'), duration: 6000 })
  } finally {
    importing.value = false
  }
}

async function onExport() {
  try {
    const text = (await request(ExportOpmlDocument)).exportOpml
    const url = URL.createObjectURL(new Blob([text], { type: 'text/x-opml;charset=utf-8' }))
    const a = document.createElement('a')
    a.href = url
    a.download = 'gopan-feeds.opml'
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 10_000)
  } catch (err) {
    ElMessage.error(errorText(err, '导出失败'))
  }
}
</script>

<template>
  <div class="feeds">
    <div class="head">
      <h2 class="page-title">
        订阅
        <PageHelp text="RSS / Atom(包括播客)和 GitHub 仓库的 Releases。服务器定时检查,新内容按订阅的方式下载附件、剪藏正文或只记录,有新内容和连续失败都会通知你。导出 OPML 不含 GitHub 订阅。" />
      </h2>
      <span class="head-actions">
        <el-button type="primary" :icon="Plus" :disabled="!fetchEnabled" @click="addVisible = true">添加</el-button>
        <el-button :icon="Upload" :loading="importing" :disabled="!fetchEnabled" @click="opmlInput?.click()">
          导入 OPML
        </el-button>
        <el-button :icon="Download" :disabled="!feeds.length" @click="onExport">导出 OPML</el-button>
      </span>
      <input ref="opmlInput" type="file" accept=".opml,.xml,text/xml,text/x-opml,application/xml" class="hidden-input" @change="onOpmlChosen" />
    </div>

    <el-alert
      v-if="!fetchEnabled"
      type="warning"
      :closable="false"
      show-icon
      title="本站没有开启离线下载,订阅也用不了"
      class="tip"
    />

    <el-table
      v-if="!isMobile"
      v-loading="isFetching && !data"
      :data="feeds"
      row-key="id"
      empty-text="还没有订阅"
      class="feed-table"
      @row-click="(row: Feed) => openDetail(row.id)"
    >
      <el-table-column label="名字" min-width="240">
        <template #default="{ row }">
          <span class="name-cell">
            <el-tag size="small" effect="plain" class="kind-tag">{{ feedKindTag(asFeed(row)) }}</el-tag>
            <span class="name" :title="asFeed(row).url">{{ asFeed(row).title }}</span>
            <el-badge v-if="asFeed(row).newCount" :value="asFeed(row).newCount" type="primary" class="new-badge" />
          </span>
        </template>
      </el-table-column>
      <el-table-column label="方式" width="100">
        <template #default="{ row }">{{ feedModeLabel[asFeed(row).mode] }}</template>
      </el-table-column>
      <el-table-column label="最后更新" width="140">
        <template #default="{ row }">{{ updatedText(asFeed(row)) }}</template>
      </el-table-column>
      <el-table-column v-if="!isCompact" label="下次检查" width="140">
        <template #default="{ row }">
          <span :title="intervalText(asFeed(row).intervalMinutes)">{{ nextText(asFeed(row)) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="150">
        <template #default="{ row }">
          <el-tooltip :disabled="!asFeed(row).lastError" :content="asFeed(row).lastError ?? ''" placement="left">
            <el-tag size="small" :type="feedStatus(asFeed(row)).type">{{ feedStatus(asFeed(row)).label }}</el-tag>
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>

    <ul v-else v-loading="isFetching && !data" class="cards">
      <li v-if="!feeds.length && !isFetching" class="empty">还没有订阅</li>
      <li v-for="f in feeds" :key="f.id" class="card" @click="openDetail(f.id)">
        <div class="card-line">
          <el-tag size="small" effect="plain">{{ feedKindTag(f) }}</el-tag>
          <span class="name">{{ f.title }}</span>
          <el-badge v-if="f.newCount" :value="f.newCount" type="primary" class="new-badge" />
        </div>
        <div class="card-sub">
          <el-tag size="small" :type="feedStatus(f).type">{{ feedStatus(f).label }}</el-tag>
          {{ feedModeLabel[f.mode] }} · {{ updatedText(f) }}
        </div>
        <div v-if="f.lastError && f.failCount" class="card-error">{{ f.lastError }}</div>
      </li>
    </ul>

    <FeedFormDialog v-model="addVisible" @saved="onAdded" />
    <FeedDetailDrawer v-model="detail.visible" :feed-id="detail.id" />
  </div>
</template>

<style scoped>
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
}
.page-title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}
.head-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.head-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.hidden-input {
  display: none;
}
.tip {
  margin-bottom: 12px;
}
.intro {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
.feed-table :deep(.el-table__row) {
  cursor: pointer;
}
.name-cell,
.card-line {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.kind-tag {
  flex: none;
}
.name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.new-badge {
  flex: none;
  line-height: 1;
}
.new-badge :deep(.el-badge__content) {
  position: static;
  transform: none;
}
.cards {
  margin: 0;
  padding: 0;
  list-style: none;
}
.card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 4px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  cursor: pointer;
}
.card-sub {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.card-error {
  font-size: 12px;
  color: var(--el-color-danger);
  word-break: break-word;
}
.empty {
  padding: 32px 0;
  text-align: center;
  color: var(--el-text-color-secondary);
}
</style>
