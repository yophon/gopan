<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CopyDocument, Delete, VideoPause, VideoPlay, View } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import {
  LinkDocument,
  LinkHitsDocument,
  LinkPrefsDocument,
  LinkReferersDocument,
  LinkStatsDocument,
  PauseLinkDocument,
  RevokeLinkDocument,
  UpdateLinkDocument,
} from '@/api/gen/graphql'
import { useBreakpoints } from '@/composables/breakpoints'
import { ClipboardError, showManualCopy, writeClipboard } from '@/composables/links'
import { formatBytes, formatRelative, formatTime } from '@/utils/format'
import {
  absoluteLinkUrl,
  formatLink,
  hitStatusType,
  linkFormatLabels,
  linkSettingsFrom,
  linkSettingsInput,
  linkStatusMeta,
  refererHost,
  refererLabel,
  validateLinkSettings,
} from '@/utils/links'
import type { LinkFormat, LinkSettingsForm } from '@/utils/links'
import { describeUserAgent } from '@/utils/userAgent'
import LinkHourChart from './LinkHourChart.vue'
import LinkThumb from './LinkThumb.vue'

/** 单条直链的详情(doc/22 四.4):复制、7 天按小时的图、来源排行、访问明细、设置、暂停 / 吊销 */

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ linkId: string | null }>()

const queryClient = useQueryClient()
const { isMobile } = useBreakpoints()
const origin = location.origin
const open = computed(() => visible.value && !!props.linkId)

const { data, isFetching, isError, error } = useQuery({
  queryKey: computed(() => ['link', props.linkId]),
  queryFn: () => request(LinkDocument, { id: props.linkId! }),
  enabled: open,
  staleTime: 0,
})
const link = computed(() => data.value?.link ?? null)
const status = computed(() => (link.value ? linkStatusMeta(link.value.status, link.value.pauseReason) : null))
const fullUrl = computed(() => (link.value ? absoluteLinkUrl(link.value.url, origin) : ''))
// 显示用解码后的(中文文件名能看懂),复制仍是编码后的
const readableUrl = computed(() => {
  try {
    return decodeURI(fullUrl.value)
  } catch {
    return fullUrl.value
  }
})

const { data: statsData } = useQuery({
  queryKey: computed(() => ['linkStats', props.linkId]),
  queryFn: () => request(LinkStatsDocument, { id: props.linkId!, hours: 168 }),
  enabled: open,
  staleTime: 0,
})

const { data: referersData } = useQuery({
  queryKey: computed(() => ['linkReferers', props.linkId]),
  queryFn: () => request(LinkReferersDocument, { id: props.linkId!, days: 30 }),
  enabled: open,
  staleTime: 0,
})
const referers = computed(() => referersData.value?.linkReferers ?? [])
const refererMax = computed(() => Math.max(1, ...referers.value.map((r) => r.hits)))

const hitsQuery = useInfiniteQuery({
  queryKey: computed(() => ['linkHits', props.linkId]),
  queryFn: ({ pageParam }) => request(LinkHitsDocument, { id: props.linkId!, cursor: pageParam }),
  initialPageParam: null as string | null,
  getNextPageParam: (last) => last.linkHits.nextCursor ?? null,
  enabled: open,
  staleTime: 0,
})
const hits = computed(() => hitsQuery.data.value?.pages.flatMap((p) => p.linkHits.items) ?? [])

const { data: prefsData } = useQuery({
  queryKey: ['linkPrefs'],
  queryFn: () => request(LinkPrefsDocument),
  enabled: open,
})
const prefs = computed(() => prefsData.value?.linkPrefs ?? null)
const defaultHostsText = computed(() => {
  const hosts = prefs.value?.refererHosts ?? []
  return hosts.length ? hosts.join('、') : '不限来源'
})

function invalidateLink() {
  void queryClient.invalidateQueries({ queryKey: ['link', props.linkId] })
  void queryClient.invalidateQueries({ queryKey: ['myLinks'] })
}

// ---------- 复制 ----------

const formats: LinkFormat[] = ['url', 'markdown', 'html', 'bbcode']

async function copy(fmt: LinkFormat) {
  const l = link.value
  if (!l) return
  try {
    await writeClipboard(Promise.resolve(formatLink(l, fmt, origin)))
    ElMessage.success(`已复制(${linkFormatLabels[fmt]})`)
  } catch (err) {
    if (err instanceof ClipboardError) showManualCopy(err.text)
  }
}

// ---------- 设置 ----------

const form = ref<LinkSettingsForm | null>(null)
const saving = ref(false)

// 换一条直链、或默认上限拿到后再初始化表单;保存后按服务端返回的重填
watch(
  [() => link.value?.id, () => prefs.value?.dailyBytesCapDefault],
  () => {
    if (link.value && prefs.value) form.value = linkSettingsFrom(link.value, prefs.value.dailyBytesCapDefault)
  },
  { immediate: true },
)

async function onSave() {
  const l = link.value
  const f = form.value
  if (!l || !f) return
  const problem = validateLinkSettings(f)
  if (problem) {
    ElMessage.warning(problem)
    return
  }
  saving.value = true
  try {
    const res = await request(UpdateLinkDocument, { id: l.id, input: linkSettingsInput(f) })
    queryClient.setQueryData(['link', l.id], { link: res.updateLink })
    if (prefs.value) form.value = linkSettingsFrom(res.updateLink, prefs.value.dailyBytesCapDefault)
    void queryClient.invalidateQueries({ queryKey: ['myLinks'] })
    ElMessage.success('设置已保存')
  } catch (err) {
    ElMessage.error(errorText(err, '保存失败'))
  } finally {
    saving.value = false
  }
}

// ---------- 暂停 / 吊销 ----------

const pausing = ref(false)

async function onTogglePause() {
  const l = link.value
  if (!l) return
  const pause = l.status !== 'PAUSED'
  pausing.value = true
  try {
    const res = await request(PauseLinkDocument, { id: l.id, paused: pause })
    queryClient.setQueryData(['link', l.id], { link: res.pauseLink })
    void queryClient.invalidateQueries({ queryKey: ['myLinks'] })
    ElMessage.success(pause ? '已暂停,访问会返回 503' : '已恢复')
  } catch (err) {
    ElMessage.error(errorText(err, '操作失败'))
  } finally {
    pausing.value = false
  }
}

async function onRevoke() {
  const l = link.value
  if (!l) return
  try {
    await ElMessageBox.confirm('吊销后这个地址立即失效(404),引用它的网页会裂图。文件本身不受影响。', '吊销直链?', {
      confirmButtonText: '吊销',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await request(RevokeLinkDocument, { id: l.id })
    ElMessage.success('直链已吊销')
    visible.value = false
    invalidateLink()
  } catch (err) {
    ElMessage.error(errorText(err, '吊销失败'))
  }
}
</script>

<template>
  <el-drawer
    v-model="visible"
    direction="rtl"
    :size="isMobile ? '100%' : '600px'"
    :title="link?.name ?? '直链详情'"
    class="link-drawer"
    append-to-body
  >
    <div v-loading="isFetching && !link" class="ld">
      <el-empty v-if="isError" :description="errorText(error, '这条直链不存在或已吊销')" />

      <template v-else-if="link">
        <!-- 概要 -->
        <section class="ld-head">
          <LinkThumb :node-id="link.nodeId" :name="link.name" :mime="link.mime" :size="56" />
          <div class="ld-head-main">
            <div class="ld-status">
              <el-tag :type="status?.type" size="small">{{ status?.label }}</el-tag>
              <span v-if="status?.detail" class="muted">{{ status.detail }}</span>
              <el-tag v-if="link.hasGps" type="warning" size="small" effect="plain">照片带拍摄位置</el-tag>
            </div>
            <code class="ld-url" :title="fullUrl">{{ readableUrl }}</code>
            <div class="muted">
              {{ formatBytes(link.size) }} · {{ formatTime(link.createdAt) }} 创建
              <template v-if="link.expiresAt"> · 到 {{ formatTime(link.expiresAt) }}</template>
            </div>
          </div>
        </section>

        <div class="ld-copy">
          <el-button
            v-for="f in formats"
            :key="f"
            size="small"
            :icon="f === 'url' ? CopyDocument : undefined"
            @click="copy(f)"
          >
            {{ linkFormatLabels[f] }}
          </el-button>
          <el-button size="small" class="open-btn" :icon="View" tag="a" :href="fullUrl" target="_blank" rel="noopener">
            打开
          </el-button>
        </div>

        <!-- 用量 -->
        <section class="ld-usage">
          <div class="usage-cell">
            <span class="usage-label">今天</span>
            <span class="usage-value">{{ link.today.hits }} 次 · {{ formatBytes(link.today.bytes) }}</span>
            <span v-if="link.today.blocked" class="muted">拦下 {{ link.today.blocked }}</span>
          </div>
          <div class="usage-cell">
            <span class="usage-label">近 30 天</span>
            <span class="usage-value">{{ link.last30Days.hits }} 次 · {{ formatBytes(link.last30Days.bytes) }}</span>
            <span v-if="link.last30Days.blocked" class="muted">拦下 {{ link.last30Days.blocked }}</span>
          </div>
          <div class="usage-cell">
            <span class="usage-label">累计</span>
            <span class="usage-value">{{ link.hitsTotal }} 次 · {{ formatBytes(link.bytesTotal) }}</span>
            <span class="muted">最后访问 {{ link.lastHitAt ? formatRelative(link.lastHitAt) : '从未' }}</span>
          </div>
        </section>

        <section class="ld-section">
          <h4>最近 7 天</h4>
          <LinkHourChart :stats="statsData?.linkStats ?? []" :hours="168" />
        </section>

        <section class="ld-section">
          <h4>来源排行(30 天)</h4>
          <p v-if="!referers.length" class="muted">还没有访问</p>
          <ul v-else class="ref-list">
            <li v-for="r in referers" :key="r.host" class="ref-item">
              <span class="ref-host" :class="{ muted: r.host === '' || r.host === '*' }">{{ refererLabel(r.host) }}</span>
              <span class="ref-bar"><span :style="{ width: `${(r.hits / refererMax) * 100}%` }" /></span>
              <span class="ref-num">{{ r.hits }} 次 · {{ formatBytes(r.bytes) }}</span>
            </li>
          </ul>
        </section>

        <section class="ld-section">
          <h4>访问明细</h4>
          <p class="muted small">每条直链每小时最多记 200 条,超出的只计数;保留 30 天。</p>
          <el-table v-if="!isMobile" :data="hits" size="small" empty-text="还没有访问" max-height="360">
            <el-table-column label="时间" width="100">
              <template #default="{ row }">{{ formatTime(row.at).slice(5) }}</template>
            </el-table-column>
            <el-table-column label="IP" prop="ip" width="116" show-overflow-tooltip />
            <el-table-column label="客户端" min-width="100">
              <template #default="{ row }">
                <span :title="row.userAgent">{{ describeUserAgent(row.userAgent) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="来源" min-width="100">
              <template #default="{ row }">
                <span :title="row.referer">{{ refererHost(row.referer) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="58">
              <template #default="{ row }">
                <el-tag size="small" :type="hitStatusType(row.status)" effect="plain">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="流量" width="74" align="right">
              <template #default="{ row }">{{ formatBytes(row.bytes) }}</template>
            </el-table-column>
          </el-table>
          <ul v-else class="hit-cards">
            <li v-if="!hits.length" class="muted">还没有访问</li>
            <li v-for="(h, i) in hits" :key="`${h.at}-${i}`" class="hit-card">
              <div class="hit-line">
                <span>{{ formatTime(h.at) }}</span>
                <el-tag size="small" :type="hitStatusType(h.status)" effect="plain">{{ h.status }}</el-tag>
              </div>
              <div class="muted">{{ h.ip }} · {{ describeUserAgent(h.userAgent) }}</div>
              <div class="muted">{{ refererHost(h.referer) }} · {{ formatBytes(h.bytes) }}</div>
            </li>
          </ul>
          <div v-if="hitsQuery.hasNextPage.value" class="more">
            <el-button link :loading="hitsQuery.isFetchingNextPage.value" @click="hitsQuery.fetchNextPage()">
              加载更多
            </el-button>
          </div>
        </section>

        <!-- 设置 -->
        <section v-if="form" class="ld-section">
          <h4>设置</h4>
          <el-form :label-position="isMobile ? 'top' : 'right'" label-width="120px" @submit.prevent>
            <el-form-item label="来源白名单">
              <div class="field">
                <el-switch v-model="form.useDefaultHosts" active-text="用默认规则" />
                <div v-if="form.useDefaultHosts" class="muted small">默认规则:{{ defaultHostsText }}</div>
                <template v-else>
                  <el-input
                    v-model="form.hostsText"
                    type="textarea"
                    :rows="3"
                    placeholder="一行一个,如 blog.example.com(写 example.com 包含它的子域名);留空 = 不限来源"
                  />
                </template>
              </div>
            </el-form-item>
            <el-form-item label="允许无来源访问">
              <div class="field">
                <el-radio-group v-model="form.noReferer" size="small">
                  <el-radio-button value="default">默认({{ prefs?.allowNoReferer ? '允许' : '拒绝' }})</el-radio-button>
                  <el-radio-button value="allow">允许</el-radio-button>
                  <el-radio-button value="deny">拒绝</el-radio-button>
                </el-radio-group>
                <div class="muted small">直接在浏览器里打开、App 里看这类没有 Referer 的访问</div>
              </div>
            </el-form-item>
            <el-form-item label="每天流量上限">
              <div class="field inline">
                <el-radio-group v-model="form.capMode" size="small">
                  <el-radio-button value="default">默认({{ formatBytes(prefs?.dailyBytesCapDefault) }})</el-radio-button>
                  <el-radio-button value="custom">自定义</el-radio-button>
                </el-radio-group>
                <span v-if="form.capMode === 'custom'" class="cap-input">
                  <el-input-number v-model="form.capGiB" :min="0.01" :step="1" :precision="2" size="small" />
                  GiB
                </span>
              </div>
            </el-form-item>
            <el-form-item label="有效期">
              <div class="field inline">
                <el-radio-group v-model="form.expires" size="small">
                  <el-radio-button value="never">永久</el-radio-button>
                  <el-radio-button value="at">到期</el-radio-button>
                </el-radio-group>
                <el-date-picker
                  v-if="form.expires === 'at'"
                  v-model="form.expiresAt"
                  type="datetime"
                  size="small"
                  placeholder="到期时间"
                  :disabled-date="(d: Date) => d.getTime() < Date.now() - 86_400_000"
                />
              </div>
            </el-form-item>
            <el-form-item label="异常时自动暂停">
              <div class="field">
                <el-switch v-model="form.autoPause" />
                <div class="muted small">突增、陌生站点大量引用、单个 IP 狂刷时直接暂停(默认只提醒)</div>
              </div>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="saving" @click="onSave">保存设置</el-button>
            </el-form-item>
          </el-form>
        </section>

        <section class="ld-actions">
          <el-button
            :icon="link.status === 'PAUSED' ? VideoPlay : VideoPause"
            :loading="pausing"
            :disabled="link.status === 'EXPIRED'"
            @click="onTogglePause"
          >
            {{ link.status === 'PAUSED' ? '恢复' : '暂停' }}
          </el-button>
          <el-button type="danger" plain :icon="Delete" @click="onRevoke">吊销</el-button>
        </section>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.ld {
  min-height: 200px;
}
.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.small {
  margin: 4px 0 0;
  line-height: 1.5;
}
.ld-head {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}
.ld-head-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.ld-status {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}
.ld-url {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}
.ld-copy {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 12px 0;
}
.ld-copy :deep(.el-button + .el-button) {
  margin-left: 0;
}
.open-btn {
  text-decoration: none;
}
.ld-usage {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}
.usage-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
}
.usage-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.usage-value {
  font-size: 13px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}
.ld-section {
  margin-top: 20px;
}
.ld-section h4 {
  margin: 0 0 8px;
  font-size: 14px;
}
.ref-list {
  margin: 0;
  padding: 0;
  list-style: none;
}
.ref-item {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  font-size: 13px;
}
.ref-host {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ref-bar {
  height: 6px;
  border-radius: 3px;
  background: var(--el-fill-color);
  overflow: hidden;
}
.ref-bar span {
  display: block;
  height: 100%;
  background: var(--el-color-primary-light-3);
}
.ref-num {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}
.hit-cards {
  margin: 0;
  padding: 0;
  list-style: none;
}
.hit-card {
  padding: 8px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
  font-size: 13px;
}
.hit-line {
  display: flex;
  justify-content: space-between;
}
.more {
  padding: 6px 0;
  text-align: center;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
}
.field.inline {
  flex-direction: row;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.cap-input {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}
.ld-actions {
  display: flex;
  gap: 8px;
  margin: 20px 0 8px;
  padding-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
}

@media (max-width: 767px) {
  .ld-usage {
    grid-template-columns: 1fr;
  }
  .ld-actions {
    padding-bottom: var(--sab);
  }
}
</style>
