<script setup lang="ts">
import { computed, h, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElButton, ElMessage, ElMessageBox, ElNotification } from 'element-plus'
import { Close, CollectionTag, RefreshRight, Scissor, Setting } from '@element-plus/icons-vue'

import { getErrorMessage, request } from '@/api/client'
import { errorText } from '@/api/errors'
import {
  ClipPrefsDocument,
  ClipSitesDocument,
  ClipUrlDocument,
  ClipsDocument,
  NodeClipDocument,
  SetClipPrefsDocument,
} from '@/api/gen/graphql'
import type { ClipFieldsFragment, JobFieldsFragment } from '@/api/gen/graphql'
import { waitForJob } from '@/composables/clip'
import { useFetchEnabled } from '@/composables/jobs'
import { buildBookmarklet, clipTargetUrl, siteOf } from '@/utils/clips'
import { formatRelative, formatTime } from '@/utils/format'
import { isActiveJob, jobPercent, jobStatusText, parseUrls } from '@/utils/jobs'
import ClipReader from '@/components/clips/ClipReader.vue'

/**
 * 剪藏(doc/23 五.3):顶上贴链接剪一篇;下面是剪过的(卡片),可筛未读、按站点;点开是阅读视图。
 * 书签栏小工具在右上角:拖到书签栏,看到哪篇点一下。
 */

const queryClient = useQueryClient()
const fetchEnabled = useFetchEnabled()

let alive = true
onBeforeUnmount(() => (alive = false))

// ---------- 剪一篇 ----------

interface Running {
  key: number
  url: string
  job: JobFieldsFragment
}

const input = ref('')
const submitting = ref(false)
const running = ref<Running[]>([])
let seq = 0

function refreshLists() {
  void queryClient.invalidateQueries({ queryKey: ['clips'] })
  void queryClient.invalidateQueries({ queryKey: ['clipSites'] })
}

async function onClip(force = false, raw = input.value) {
  const url = clipTargetUrl(parseUrls(raw)[0] ?? raw)
  if (!url) {
    ElMessage.warning('贴一个 http / https 的网页链接')
    return
  }
  submitting.value = true
  try {
    const res = (await request(ClipUrlDocument, { url, force })).clipUrl
    if (res.existing) {
      void askExisting(res.existing, url)
      return
    }
    if (res.job) {
      input.value = ''
      track(url, res.job)
    }
  } catch (err) {
    ElMessage.error(getErrorMessage(err) ?? errorText(err, '剪藏失败'))
  } finally {
    submitting.value = false
  }
}

async function askExisting(c: ClipFieldsFragment, url: string) {
  try {
    await ElMessageBox.confirm(`「${c.title || url}」在 ${formatTime(c.createdAt)} 剪过了。`, '这个网页剪过了', {
      confirmButtonText: '打开',
      cancelButtonText: '重新剪藏',
      distinguishCancelAndClose: true,
      type: 'info',
    })
    input.value = ''
    openReader(c.id)
  } catch (action) {
    if (action === 'cancel') void onClip(true, url)
  }
}

function track(url: string, job: JobFieldsFragment) {
  const item = reactive<Running>({ key: ++seq, url, job })
  running.value = [item, ...running.value]
  waitForJob(job.id, (j) => (item.job = j), () => alive).then(
    (done) => {
      if (!done) return
      refreshLists()
      if (done.status !== 'DONE') return
      running.value = running.value.filter((r) => r.key !== item.key)
      const nodeId = done.result?.id
      const n = ElNotification({
        title: '已存好',
        type: 'success',
        duration: 6000,
        message: h('div', { class: 'clip-done' }, [
          h('div', { style: 'margin-bottom:4px;word-break:break-all' }, done.title),
          done.errorMessage ? h('div', { style: 'font-size:12px;color:var(--el-color-warning)' }, done.errorMessage) : null,
          nodeId
            ? h(
                ElButton,
                {
                  link: true,
                  type: 'primary',
                  onClick: () => {
                    n.close()
                    void openByNode(nodeId)
                  },
                },
                () => '打开',
              )
            : null,
        ]),
      })
    },
    (err: unknown) => {
      item.job = { ...item.job, status: 'FAILED', errorMessage: errorText(err, '查不到任务进度') }
    },
  )
}

function dismiss(r: Running) {
  running.value = running.value.filter((x) => x.key !== r.key)
}

// ---------- 列表 ----------

const unreadOnly = ref(false)
const site = ref('')

const query = useInfiniteQuery({
  queryKey: computed(() => ['clips', unreadOnly.value, site.value]),
  queryFn: ({ pageParam }) =>
    request(ClipsDocument, { unreadOnly: unreadOnly.value, site: site.value || null, cursor: pageParam }),
  initialPageParam: null as string | null,
  getNextPageParam: (last) => last.clips.nextCursor ?? null,
})
const items = computed<ClipFieldsFragment[]>(() => query.data.value?.pages.flatMap((p) => p.clips.items) ?? [])

const { data: sitesData } = useQuery({ queryKey: ['clipSites'], queryFn: () => request(ClipSitesDocument) })
const sites = computed(() => sitesData.value?.clipSites ?? [])

// ---------- 阅读 ----------

const reader = ref<{ visible: boolean; id: string | null }>({ visible: false, id: null })

function openReader(id: string) {
  reader.value = { visible: true, id }
}

async function openByNode(nodeId: string) {
  try {
    const c = (await request(NodeClipDocument, { nodeId })).nodeClip
    if (c) openReader(c.id)
  } catch (err) {
    ElMessage.error(errorText(err, '打开失败'))
  }
}

// ---------- 设置 ----------

const prefsOpen = ref(false)
const prefs = reactive({ folder: '/剪藏', byMonth: true })
const savingPrefs = ref(false)

watch(prefsOpen, async (v) => {
  if (!v) return
  try {
    const p = (await request(ClipPrefsDocument)).clipPrefs
    prefs.folder = p.folder
    prefs.byMonth = p.byMonth
  } catch (err) {
    ElMessage.error(errorText(err, '读取设置失败'))
  }
})

async function savePrefs() {
  const folder = prefs.folder.trim()
  if (!folder) {
    ElMessage.warning('填一个文件夹,比如 /剪藏')
    return
  }
  savingPrefs.value = true
  try {
    const p = (await request(SetClipPrefsDocument, { input: { folder, byMonth: prefs.byMonth } })).setClipPrefs
    prefs.folder = p.folder
    prefs.byMonth = p.byMonth
    ElMessage.success('已保存,之后剪的存到这里')
    prefsOpen.value = false
  } catch (err) {
    ElMessage.error(getErrorMessage(err) ?? errorText(err, '保存失败'))
  } finally {
    savingPrefs.value = false
  }
}

// ---------- 书签栏小工具 ----------

const bookmarklet = buildBookmarklet(location.origin)

function onBookmarkletClick() {
  ElMessage.info('把这个按钮拖到浏览器的书签栏,在别的网页上点它')
}
</script>

<template>
  <div class="clips">
    <div class="head">
      <h2 class="page-title">剪藏</h2>
      <span class="head-actions">
        <el-popover placement="bottom-end" :width="320" trigger="click">
          <template #reference>
            <el-button :icon="CollectionTag">书签栏小工具</el-button>
          </template>
          <div class="bm">
            <a :href="bookmarklet" class="bm-link" title="拖到书签栏" @click.prevent="onBookmarkletClick">
              <el-icon><Scissor /></el-icon>剪藏到 gopan
            </a>
            <ol class="bm-steps">
              <li>把上面的按钮拖到浏览器的书签栏(书签栏没显示的话按 Ctrl+Shift+B)。</li>
              <li>看到想存的网页,点一下这个书签,会弹出一个小窗,点「剪藏」。</li>
              <li>要登录才能看的页面,在小窗里勾「连同页面内容」,把你看到的内容一起发过来。</li>
            </ol>
            <p class="muted">手机浏览器不方便用书签;安卓 App 里从浏览器「分享」到 gopan 就能剪藏。</p>
          </div>
        </el-popover>
        <el-popover v-model:visible="prefsOpen" placement="bottom-end" :width="300" trigger="click">
          <template #reference>
            <el-button :icon="Setting" title="剪藏设置" aria-label="剪藏设置" />
          </template>
          <div class="prefs">
            <label class="field">
              <span class="field-label">存到哪个文件夹</span>
              <el-input v-model="prefs.folder" placeholder="/剪藏" />
            </label>
            <el-checkbox v-model="prefs.byMonth">按月分子文件夹(/剪藏/2026-10)</el-checkbox>
            <div class="prefs-actions">
              <el-button size="small" @click="prefsOpen = false">取消</el-button>
              <el-button size="small" type="primary" :loading="savingPrefs" @click="savePrefs">保存</el-button>
            </div>
          </div>
        </el-popover>
      </span>
    </div>

    <el-alert
      v-if="!fetchEnabled"
      type="warning"
      :closable="false"
      show-icon
      title="本站没有开启离线下载,剪藏也用不了"
      class="disabled-tip"
    />

    <div class="clip-input">
      <el-input
        v-model="input"
        placeholder="贴一个网页链接,把正文存成 Markdown(图片一起存)"
        clearable
        :disabled="!fetchEnabled"
        @keyup.enter="onClip()"
      />
      <el-button
        type="primary"
        :icon="Scissor"
        :loading="submitting"
        :disabled="!fetchEnabled || !input.trim()"
        @click="onClip()"
      >
        剪藏
      </el-button>
    </div>

    <ul v-if="running.length" class="running">
      <li v-for="r in running" :key="r.key" class="run-item">
        <div class="run-main">
          <span class="run-title" :title="r.url">{{ r.job.title || r.url }}</span>
          <el-progress
            v-if="isActiveJob(r.job)"
            :percentage="jobPercent(r.job) ?? 30"
            :indeterminate="jobPercent(r.job) === null"
            :stroke-width="4"
            :show-text="false"
          />
          <span class="run-status" :class="{ error: r.job.status === 'FAILED' }">{{ jobStatusText(r.job) }}</span>
        </div>
        <template v-if="!isActiveJob(r.job)">
          <el-button link size="small" :icon="RefreshRight" title="再试一次" @click="dismiss(r); onClip(false, r.url)" />
          <el-button link size="small" :icon="Close" title="关掉" @click="dismiss(r)" />
        </template>
      </li>
    </ul>

    <div class="filters">
      <el-segmented
        v-model="unreadOnly"
        :options="[
          { label: '全部', value: false },
          { label: '未读', value: true },
        ]"
      />
      <el-select v-model="site" class="site-select" placeholder="全部站点" clearable filterable>
        <el-option v-for="s in sites" :key="s.site" :value="s.site" :label="s.site || '(未知站点)'">
          <span>{{ s.site || '(未知站点)' }}</span>
          <span class="opt-count">{{ s.total }}</span>
        </el-option>
      </el-select>
    </div>

    <div v-loading="query.isFetching.value && !query.data.value" class="cards">
      <el-empty
        v-if="!items.length && !query.isFetching.value"
        class="empty"
        :description="unreadOnly || site ? '没有符合条件的剪藏' : '还没有剪藏。贴一个链接试试,或者装上书签栏小工具'"
      />
      <button v-for="c in items" :key="c.id" type="button" class="card" :class="{ unread: !c.readAt }" @click="openReader(c.id)">
        <span class="card-title">
          <span v-if="!c.readAt" class="dot" title="未读" />
          {{ c.title || c.url }}
        </span>
        <span class="card-meta">{{ siteOf(c) }} · {{ formatRelative(c.createdAt) }}</span>
        <span v-if="c.excerpt" class="card-excerpt">{{ c.excerpt }}</span>
      </button>
    </div>
    <div v-if="query.hasNextPage.value" class="more">
      <el-button link :loading="query.isFetchingNextPage.value" @click="query.fetchNextPage()">加载更多</el-button>
    </div>

    <ClipReader v-model="reader.visible" :clip-id="reader.id" />
  </div>
</template>

<style scoped>
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 12px;
}
.page-title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}
.head-actions {
  display: flex;
  gap: 8px;
}
.head-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.disabled-tip {
  margin-bottom: 12px;
}
.clip-input {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.clip-input .el-input {
  flex: 1;
  min-width: 0;
}
.running {
  margin: 0 0 12px;
  padding: 0;
  list-style: none;
}
.run-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
}
.run-item + .run-item {
  margin-top: 6px;
}
.run-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.run-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}
.run-status {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.run-status.error {
  color: var(--el-color-danger);
}
.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.site-select {
  width: 200px;
}
.opt-count {
  float: right;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 12px;
  min-height: 160px;
}
.empty {
  grid-column: 1 / -1;
}
.card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
  padding: 12px 14px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color);
  color: var(--el-text-color-primary);
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s ease;
}
.card:hover {
  border-color: var(--el-color-primary-light-5);
}
.card-title {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  font-size: 15px;
  line-height: 1.45;
  word-break: break-word;
}
.card.unread .card-title {
  font-weight: 600;
}
.dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  margin: 0 4px 2px 0;
  border-radius: 50%;
  background: var(--el-color-primary);
  vertical-align: middle;
}
.card-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.card-excerpt {
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
  word-break: break-word;
}
.more {
  padding: 12px 0;
  text-align: center;
}
.bm-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  border-radius: 16px;
  background: var(--el-color-primary);
  color: #fff;
  font-size: 14px;
  text-decoration: none;
  cursor: grab;
}
.bm-steps {
  margin: 10px 0 6px;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.7;
}
.muted {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
.prefs {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.field-label {
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.prefs-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

@media (max-width: 767px) {
  .head-actions .el-button:first-child :deep(span) {
    display: none;
  }
  .head-actions .el-button:first-child :deep(.el-icon) {
    margin: 0;
  }
  .site-select {
    flex: 1;
    min-width: 0;
  }
  .cards {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
