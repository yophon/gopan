<script setup lang="ts">
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useInfiniteQuery, useQuery } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { Close, Download, FolderOpened, Grid, List, Lock, View } from '@element-plus/icons-vue'

import { guestRequest, hasErrorCode, plainRequest } from '@/api/client'
import { errorText } from '@/api/errors'
import {
  ChildrenDocument,
  NodeDownloadUrlDocument,
  NodePreviewDocument,
  ShareInfoDocument,
  ShareRootDocument,
  VerifySharePasswordDocument,
} from '@/api/gen/graphql'
import type { ChildrenQuery } from '@/api/gen/graphql'
import { formatBytes } from '@/utils/format'
import { mostlyMedia, shareSummary } from '@/utils/visitor'
import { openPreview, previewState } from '@/composables/preview'
import { useBreakpoints } from '@/composables/breakpoints'
import BrandLogo from '@/components/BrandLogo.vue'
import NodeBrowser from '@/components/nodes/NodeBrowser.vue'
import NodeCardList from '@/components/nodes/NodeCardList.vue'
import NodeActionSheet from '@/components/nodes/NodeActionSheet.vue'
import NodeIcon from '@/components/nodes/NodeIcon.vue'
import ContextMenu, { type MenuEntry } from '@/components/ContextMenu.vue'
import type { NodeListItem, SheetItem } from '@/components/nodes/types'
import '@/styles/visitor.css'

/**
 * 分享的访客页(/s/:token)。不登录:打开链接 → (有密码就输密码)→ 看、预览、下载。
 * 拿到链接的人多半在手机微信里点开,手机优先;跟着访客自己的系统切深浅色。
 */

type ChildItem = ChildrenQuery['children']['items'][number]

// 预览弹层第一次打开时才加载
const PreviewModal = defineAsyncComponent(() => import('@/components/preview/PreviewModal.vue'))
const previewOpened = ref(false)
watch(
  () => previewState.visible,
  (v) => {
    if (v) previewOpened.value = true
  },
)

const route = useRoute()
const { isMobile } = useBreakpoints()
const token = computed(() => String(route.params.token ?? ''))

// ---------- 访客凭证:sessionStorage 按分享隔离,30 分钟过期后走重验 ----------

const gtKey = computed(() => `gopan_gt_${token.value}`)
const guestToken = ref<string | null>(sessionStorage.getItem(gtKey.value))

function setGuestToken(t: string | null) {
  guestToken.value = t
  if (t) sessionStorage.setItem(gtKey.value, t)
  else sessionStorage.removeItem(gtKey.value)
}

/** 访客 token 失效(过期/分享被取消)→ 清凭证回到入口态,并刷新 shareInfo */
function onGuestError(err: unknown): boolean {
  if (hasErrorCode(err, 'UNAUTHENTICATED') || hasErrorCode(err, 'SHARE_EXPIRED')) {
    setGuestToken(null)
    void refetchInfo()
    return true
  }
  return false
}

async function guarded<T>(fn: () => Promise<T>): Promise<T | null> {
  try {
    return await fn()
  } catch (err) {
    if (onGuestError(err)) return null
    throw err
  }
}

// ---------- 公开信息 ----------

const {
  data: infoData,
  isPending: infoPending,
  isError: infoError,
  error: infoErr,
  refetch: refetchInfo,
} = useQuery({
  queryKey: ['shareInfo', token],
  queryFn: () => plainRequest(ShareInfoDocument, { token: token.value }),
  retry: false,
})
const info = computed(() => infoData.value?.shareInfo ?? null)
const notFound = computed(() => hasErrorCode(infoErr.value, 'NOT_FOUND'))

watch(info, (i) => {
  if (i && !i.needPassword) document.title = `${i.name} · gopan 云盘`
})

// ---------- 验密 / 领取访客凭证 ----------

const password = ref('')
const verifying = ref(false)
const verifyError = ref<string | null>(null)

async function verify(pwd: string) {
  verifying.value = true
  verifyError.value = null
  try {
    const res = await plainRequest(VerifySharePasswordDocument, { token: token.value, password: pwd })
    setGuestToken(res.verifySharePassword.accessToken)
  } catch (err) {
    if (hasErrorCode(err, 'SHARE_EXPIRED')) {
      void refetchInfo()
      return
    }
    verifyError.value = errorText(err, '验证失败,请稍后再试', {
      BAD_SHARE_PASSWORD: '密码不对,再试一次',
      SHARE_PASSWORD_REQUIRED: '请输入密码',
    })
  } finally {
    verifying.value = false
  }
}

// 无密码分享:info 到手自动领 token
watch(
  [info, guestToken],
  ([i, gt]) => {
    if (i && !i.expired && !i.needPassword && !gt && !verifying.value) void verify('')
  },
  { immediate: true },
)

function onSubmitPassword() {
  if (password.value) void verify(password.value)
}

// ---------- 分享根 ----------

const { data: rootData, error: rootErr } = useQuery({
  queryKey: ['shareRoot', guestToken],
  enabled: computed(() => !!guestToken.value),
  queryFn: () => guarded(() => guestRequest(ShareRootDocument, guestToken.value!)),
  retry: false,
})
const root = computed(() => rootData.value?.shareRoot ?? null)

/** 分享整体失效:info 标记过期,或访客请求发现已吊销 */
const dead = computed(() => !!info.value?.expired || hasErrorCode(rootErr.value, 'SHARE_EXPIRED'))

// ---------- 单个文件:页面里直接看 ----------

const { data: rootPreviewData } = useQuery({
  queryKey: ['sharePreview', computed(() => root.value?.id), guestToken],
  enabled: computed(() => !!guestToken.value && root.value?.kind === 'FILE'),
  queryFn: () => guarded(() => guestRequest(NodePreviewDocument, guestToken.value!, { id: root.value!.id })),
  retry: false,
})
const rootPreview = computed(() => rootPreviewData.value?.node.preview ?? null)
/** 能在弹层里预览的类型(图片视频音频直接在页面里放,这些给一个「预览」按钮) */
const canPreview = computed(() => {
  const k = rootPreview.value?.kind
  return !!k && k !== 'NONE' && !(k === 'OFFICE' && rootPreview.value?.status === 'UNAVAILABLE')
})

// ---------- 文件夹浏览:导航栈面包屑(分享根之上不可见,不能用 parentId 回溯) ----------

const navStack = ref<{ id: string; name: string }[]>([])
const currentFolder = computed(() => {
  if (navStack.value.length > 0) return navStack.value[navStack.value.length - 1]!
  return root.value?.kind === 'FOLDER' ? { id: root.value.id, name: root.value.name } : null
})

watch(token, () => {
  navStack.value = []
  guestToken.value = sessionStorage.getItem(gtKey.value)
})

// 一页 200 条,翻完所有页(以前只显示第一页)
const childrenQuery = useInfiniteQuery({
  queryKey: ['shareChildren', computed(() => currentFolder.value?.id), guestToken],
  enabled: computed(() => !!guestToken.value && !!currentFolder.value),
  queryFn: ({ pageParam }) =>
    guarded(() => guestRequest(ChildrenDocument, guestToken.value!, { parentId: currentFolder.value!.id, cursor: pageParam })),
  initialPageParam: null as string | null,
  getNextPageParam: (last) => last?.children.nextCursor ?? undefined,
  retry: false,
})
watch(
  () => [childrenQuery.hasNextPage.value, childrenQuery.isFetching.value] as const,
  ([more, fetching]) => {
    if (more && !fetching) void childrenQuery.fetchNextPage()
  },
)
const items = computed<ChildItem[]>(() => childrenQuery.data.value?.pages.flatMap((p) => p?.children.items ?? []) ?? [])
const listLoading = computed(() => childrenQuery.isPending.value && !!currentFolder.value)

// 列表 / 网格:访客自己切过就听他的,没切过时相册类的分享默认网格
const viewPref = ref<'list' | 'grid' | null>(null)
const view = computed<'list' | 'grid'>(() => viewPref.value ?? (mostlyMedia(items.value) ? 'grid' : 'list'))

function enterFolder(row: { id: string; name: string }) {
  navStack.value = [...navStack.value, { id: row.id, name: row.name }]
  selectedIds.value = []
}

function jumpTo(index: number) {
  // index = -1 回到分享根
  navStack.value = navStack.value.slice(0, index + 1)
  selectedIds.value = []
}

function onOpen(row: NodeListItem) {
  if (row.kind === 'FOLDER') {
    enterFolder(row)
    return
  }
  const files = items.value.filter((n) => n.kind === 'FILE')
  const idx = files.findIndex((n) => n.id === row.id)
  if (idx >= 0) openPreview(files.map((n) => n.id), idx)
}

// ---------- 下载:单个文件预签名直链,文件夹或多项打 zip ----------

const downloadingId = ref<string | null>(null)

function clickA(href: string, download?: string) {
  const a = document.createElement('a')
  a.href = href
  if (download) a.download = download
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  a.remove()
}

async function download(nodes: { id: string; name: string; kind: string }[]) {
  if (!nodes.length || !guestToken.value) return
  const only = nodes.length === 1 ? nodes[0]! : null
  if (!only || only.kind === 'FOLDER') {
    clickA(`/pack?nodes=${nodes.map((n) => n.id).join(',')}&token=${encodeURIComponent(guestToken.value)}`)
    return
  }
  downloadingId.value = only.id
  try {
    const res = await guestRequest(NodeDownloadUrlDocument, guestToken.value, { id: only.id })
    const url = res.node.downloadUrl
    if (!url) {
      ElMessage.error('拿不到下载地址')
      return
    }
    clickA(url, only.name)
  } catch (err) {
    if (!onGuestError(err)) ElMessage.error(errorText(err, '拿不到下载地址'))
  } finally {
    downloadingId.value = null
  }
}

// ---------- 桌面:选中、右键;手机:长按菜单 ----------

const selectedIds = ref<string[]>([])
const selection = computed(() => {
  const set = new Set(selectedIds.value)
  return items.value.filter((n) => set.has(n.id))
})

const menu = ref<{ visible: boolean; x: number; y: number; node: NodeListItem | null }>({ visible: false, x: 0, y: 0, node: null })

function onMenu(node: NodeListItem | null, x: number, y: number) {
  if (node) menu.value = { visible: true, x, y, node }
}

const menuItems = computed<MenuEntry[]>(() => {
  const n = menu.value.node
  if (!n) return []
  const many = selection.value.length > 1
  return [
    ...(many ? [] : [{ key: 'open', label: n.kind === 'FOLDER' ? '打开' : '预览', icon: n.kind === 'FOLDER' ? FolderOpened : View }]),
    { key: 'download', label: many ? `下载 ${selection.value.length} 项` : n.kind === 'FOLDER' ? '打包下载' : '下载', icon: Download },
  ]
})

function onMenuSelect(key: string) {
  const n = menu.value.node
  if (!n) return
  if (key === 'open') onOpen(n)
  else if (key === 'download') void download(selection.value.length > 1 ? selection.value : [n])
}

const sheet = ref<{ visible: boolean; node: NodeListItem | null }>({ visible: false, node: null })

const sheetItems = computed<SheetItem[]>(() => [
  { key: 'open', label: sheet.value.node?.kind === 'FOLDER' ? '打开' : '预览' },
  { key: 'download', label: sheet.value.node?.kind === 'FOLDER' ? '打包下载' : '下载', icon: Download },
])

function onSheetSelect(key: string) {
  const node = sheet.value.node
  if (!node) return
  if (key === 'open') onOpen(node)
  if (key === 'download') void download([node])
}

const summary = computed(() => (info.value ? shareSummary(info.value) : ''))
</script>

<template>
  <div class="visitor">
    <header class="v-top">
      <a href="/app" class="v-brand" title="gopan 云盘"><BrandLogo /></a>
    </header>

    <main class="v-main">
      <!-- 加载中 -->
      <div v-if="infoPending" v-loading="true" class="v-state" element-loading-background="transparent" />

      <!-- 不存在 / 出错 / 失效 -->
      <section v-else-if="notFound || infoError || dead" class="v-state">
        <svg class="v-state-art" viewBox="0 0 34 29" aria-hidden="true">
          <path d="M1 4a3 3 0 0 1 3-3h7.2a2 2 0 0 1 1.5.7L15.4 4H30a3 3 0 0 1 3 3v18a3 3 0 0 1-3 3H4a3 3 0 0 1-3-3z" class="folder-back" />
          <path d="M1 9.5h32V25a3 3 0 0 1-3 3H4a3 3 0 0 1-3-3z" class="folder-front" />
        </svg>
        <h1 class="v-state-title">{{ notFound ? '链接不存在' : dead ? '分享已失效' : '打不开这个分享' }}</h1>
        <p class="v-state-text">
          {{
            notFound
              ? '可能是地址抄错了,或者分享已经被删除。'
              : dead
                ? '链接过期了,或者分享的人取消了分享。可以找对方重新要一个。'
                : errorText(infoErr, '网络不太好,稍后刷新试试。')
          }}
        </p>
      </section>

      <!-- 输密码 -->
      <section v-else-if="info && !guestToken && info.needPassword" class="gp-folder v-card v-lock">
        <span class="gp-folder-tab"><el-icon><Lock /></el-icon>需要密码</span>
        <h1 class="v-title" :title="info.name">{{ info.name }}</h1>
        <p class="v-sub">分享的人给它设了密码</p>
        <form class="v-form" @submit.prevent="onSubmitPassword">
          <el-input v-model="password" type="password" placeholder="输入密码" show-password size="large" autofocus />
          <p v-if="verifyError" class="v-error">{{ verifyError }}</p>
          <el-button type="primary" size="large" native-type="submit" :loading="verifying" :disabled="!password" class="v-btn">
            查看
          </el-button>
        </form>
      </section>

      <!-- 正在领凭证 -->
      <div v-else-if="!root" v-loading="true" class="v-state" element-loading-background="transparent" />

      <!-- 单个文件 -->
      <section v-else-if="root.kind === 'FILE'" class="gp-folder v-card v-file">
        <span class="gp-folder-tab">分享给你的文件</span>
        <div class="v-media">
          <img
            v-if="rootPreview?.kind === 'IMAGE' && (rootPreview.largeUrl || rootPreview.contentUrl || rootPreview.thumbUrl)"
            :src="rootPreview.largeUrl ?? rootPreview.contentUrl ?? rootPreview.thumbUrl ?? ''"
            :alt="root.name"
            class="v-image"
            @click="openPreview([root.id], 0)"
          />
          <video
            v-else-if="rootPreview?.kind === 'VIDEO' && rootPreview.contentUrl"
            :src="rootPreview.contentUrl"
            :poster="rootPreview.largeUrl ?? rootPreview.thumbUrl ?? undefined"
            controls
            playsinline
            preload="metadata"
            class="v-video"
          />
          <audio v-else-if="rootPreview?.kind === 'AUDIO' && rootPreview.contentUrl" :src="rootPreview.contentUrl" controls class="v-audio" />
          <NodeIcon v-else :node="{ ...root, preview: rootPreview }" :size="72" class="v-big-icon" />
        </div>
        <h1 class="v-title" :title="root.name">{{ root.name }}</h1>
        <p class="v-sub">{{ summary || formatBytes(root.size) }}</p>
        <div class="v-actions">
          <el-button type="primary" size="large" :icon="Download" :loading="downloadingId === root.id" @click="download([root])">
            下载
          </el-button>
          <el-button
            v-if="canPreview && !['IMAGE', 'VIDEO', 'AUDIO'].includes(rootPreview?.kind ?? '')"
            size="large"
            :icon="View"
            @click="openPreview([root.id], 0)"
          >
            预览
          </el-button>
        </div>
      </section>

      <!-- 文件夹 -->
      <template v-else>
        <section class="gp-folder v-card v-head">
          <span class="gp-folder-tab">分享给你的文件夹</span>
          <div class="v-head-row">
            <div class="v-head-text">
              <h1 class="v-title" :title="root.name">{{ root.name }}</h1>
              <p v-if="summary" class="v-sub">{{ summary }}</p>
            </div>
            <el-button type="primary" size="large" :icon="Download" class="v-head-btn" @click="download([root])">
              全部下载
            </el-button>
          </div>
        </section>

        <section class="v-folder">
          <div class="v-bar">
            <!-- 在根上时头部已经写着名字,路径只在进了子文件夹后出现 -->
            <nav v-if="navStack.length" class="v-crumbs" aria-label="位置">
              <button type="button" class="v-crumb" :class="{ current: navStack.length === 0 }" @click="jumpTo(-1)">{{ root.name }}</button>
              <template v-for="(c, i) in navStack" :key="c.id">
                <span class="v-sep">/</span>
                <button type="button" class="v-crumb" :class="{ current: i === navStack.length - 1 }" @click="jumpTo(i)">{{ c.name }}</button>
              </template>
            </nav>
            <span v-else class="v-count">{{ items.length }} 项</span>
            <template v-if="!isMobile && selection.length">
              <span class="v-sel">
                <el-button link :icon="Close" title="取消选择" aria-label="取消选择" @click="selectedIds = []" />
                已选 {{ selection.length }} 项
              </span>
              <el-button :icon="Download" @click="download(selection)">下载所选</el-button>
            </template>
            <el-button-group class="v-view">
              <el-button :type="view === 'list' ? 'primary' : undefined" :icon="List" title="列表" aria-label="列表" @click="viewPref = 'list'" />
              <el-button :type="view === 'grid' ? 'primary' : undefined" :icon="Grid" title="网格" aria-label="网格" @click="viewPref = 'grid'" />
            </el-button-group>
          </div>

          <NodeBrowser
            v-if="!isMobile"
            v-model:selected-ids="selectedIds"
            :items="items"
            :view="view"
            :loading="listLoading"
            :row-share="false"
            empty-text="这个文件夹是空的"
            @open="onOpen"
            @menu="onMenu"
            @download="(n) => download([n])"
          />
          <NodeCardList
            v-else
            :items="items"
            :mode="view"
            :selection-mode="false"
            :selected-ids="[]"
            :downloading-id="downloadingId"
            :loading="listLoading"
            @open="onOpen"
            @menu="(node) => (sheet = { visible: true, node })"
          />
        </section>
      </template>
    </main>

    <footer class="v-foot">
      <a href="/app">用 gopan 云盘分享</a>
    </footer>

    <PreviewModal v-if="guestToken && previewOpened" :guest-token="guestToken" />
    <ContextMenu v-model:visible="menu.visible" :x="menu.x" :y="menu.y" :items="menuItems" @select="onMenuSelect" />
    <NodeActionSheet v-model:visible="sheet.visible" :title="sheet.node?.name" :items="sheetItems" @select="onSheetSelect" />
  </div>
</template>

<style scoped>
/* 输密码 */
.v-lock {
  max-width: 440px;
  margin-top: 4vh;
}
/* 单个文件 */
.v-file {
  max-width: 640px;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  margin-top: 2vh;
}
.v-media {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  margin-bottom: 20px;
}
.v-image {
  display: block;
  max-width: 100%;
  max-height: 60vh;
  border-radius: 12px;
  background: var(--el-bg-color);
  cursor: zoom-in;
}
.v-video {
  width: 100%;
  max-height: 60vh;
  border-radius: 12px;
  background: #000;
}
.v-audio {
  width: 100%;
}
.v-big-icon {
  margin: 18px 0 4px;
}
.v-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
  margin-top: 20px;
}
.v-actions .el-button {
  min-width: 132px;
  border-radius: 999px;
}
.v-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

/* 文件夹 */
.v-head-row {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
}
.v-head-text {
  min-width: 0;
}
.v-head-btn {
  flex: none;
  border-radius: 999px;
}
.v-folder {
  width: 100%;
}
.v-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 40px;
  margin: 4px 0 8px;
}
.v-bar :deep(.el-button + .el-button) {
  margin-left: 0;
}
.v-crumbs {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px;
  flex: 1;
  min-width: 0;
}
.v-crumb {
  max-width: 220px;
  padding: 4px 8px;
  border: none;
  border-radius: 8px;
  background: none;
  color: var(--gp-ink-3);
  font: inherit;
  font-size: 15px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}
.v-crumb:hover {
  background: var(--el-fill-color-light);
  color: var(--gp-ink);
}
.v-crumb.current {
  color: var(--gp-ink);
}
.v-sep {
  color: var(--el-border-color-darker);
}
.v-count {
  flex: 1;
  font-size: 14px;
  color: var(--gp-ink-3);
}
.v-sel {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 14px;
  font-weight: 600;
}
.v-view :deep(.el-button) {
  padding: 8px 10px;
}

/* 手机:边距收窄,头部按钮换到下面整行 */
@media (max-width: 767px) {
  .v-head-row {
    flex-direction: column;
    align-items: stretch;
  }
  .v-head-btn {
    width: 100%;
  }
  .v-actions .el-button {
    flex: 1;
  }
}
</style>
