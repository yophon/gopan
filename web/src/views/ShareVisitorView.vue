<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import {
  Document,
  Download,
  Folder,
  Headset,
  Link,
  Lock,
  Memo,
  Picture,
  Reading,
  VideoCamera,
  WarningFilled,
} from '@element-plus/icons-vue'

import { guestRequest, hasErrorCode, plainRequest } from '@/api/client'
import { errorText } from '@/api/errors'
import {
  ChildrenDocument,
  NodeDownloadUrlDocument,
  ShareInfoDocument,
  ShareRootDocument,
  VerifySharePasswordDocument,
} from '@/api/gen/graphql'
import type { ChildrenQuery } from '@/api/gen/graphql'
import { formatBytes, formatTime } from '@/utils/format'
import { openPreview } from '@/composables/preview'
import PreviewModal from '@/components/preview/PreviewModal.vue'

type ChildItem = ChildrenQuery['children']['items'][number]

const route = useRoute()
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

// ---------- 公开信息 ----------

const {
  data: infoData,
  isFetching: infoLoading,
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

// ---------- 验密 / 领取访客凭证 ----------

const password = ref('')
const verifying = ref(false)
const verifyError = ref<string | null>(null)

async function verify(pwd: string) {
  verifying.value = true
  verifyError.value = null
  try {
    const res = await plainRequest(VerifySharePasswordDocument, {
      token: token.value,
      password: pwd,
    })
    setGuestToken(res.verifySharePassword.accessToken)
  } catch (err) {
    if (hasErrorCode(err, 'SHARE_EXPIRED')) {
      void refetchInfo()
      return
    }
    verifyError.value = errorText(err, '验证失败,请稍后再试', {
      BAD_SHARE_PASSWORD: '密码错误,请重试',
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
    if (i && !i.expired && !i.needPassword && !gt && !verifying.value) {
      void verify('')
    }
  },
  { immediate: true },
)

function onSubmitPassword() {
  if (!password.value) return
  void verify(password.value)
}

// ---------- 分享根 ----------

const { data: rootData, error: rootErr } = useQuery({
  queryKey: ['shareRoot', guestToken],
  enabled: computed(() => !!guestToken.value),
  queryFn: async () => {
    try {
      return await guestRequest(ShareRootDocument, guestToken.value!)
    } catch (err) {
      if (onGuestError(err)) return null
      throw err
    }
  },
  retry: false,
})
const root = computed(() => rootData.value?.shareRoot ?? null)

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

const { data: childrenData, isFetching: listLoading } = useQuery({
  queryKey: ['shareChildren', computed(() => currentFolder.value?.id), guestToken],
  enabled: computed(() => !!guestToken.value && !!currentFolder.value),
  queryFn: async () => {
    try {
      return await guestRequest(ChildrenDocument, guestToken.value!, {
        parentId: currentFolder.value!.id,
      })
    } catch (err) {
      if (onGuestError(err)) return null
      throw err
    }
  },
  retry: false,
})
const items = computed(() => childrenData.value?.children.items ?? [])

function enterFolder(row: ChildItem) {
  navStack.value = [...navStack.value, { id: row.id, name: row.name }]
}

function jumpTo(index: number) {
  // index = -1 回到分享根
  navStack.value = navStack.value.slice(0, index + 1)
}

function onRowDblclick(row: ChildItem) {
  if (row.kind === 'FOLDER') {
    enterFolder(row)
    return
  }
  const files = items.value.filter((n) => n.kind === 'FILE')
  const idx = files.findIndex((n) => n.id === row.id)
  if (idx >= 0) {
    openPreview(
      files.map((n) => n.id),
      idx,
    )
  }
}

// ---------- 下载 ----------

const downloadingId = ref<string | null>(null)

function packUrl(nodeId: string): string {
  return `/pack?nodes=${nodeId}&token=${encodeURIComponent(guestToken.value ?? '')}`
}

function clickA(href: string, download?: string) {
  const a = document.createElement('a')
  a.href = href
  if (download) a.download = download
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  a.remove()
}

async function onDownload(row: { id: string; name: string; kind: string }) {
  if (row.kind === 'FOLDER') {
    clickA(packUrl(row.id))
    return
  }
  downloadingId.value = row.id
  try {
    const res = await guestRequest(NodeDownloadUrlDocument, guestToken.value!, { id: row.id })
    const url = res.node.downloadUrl
    if (!url) {
      ElMessage.error('无法获取下载链接')
      return
    }
    clickA(url, row.name)
  } catch (err) {
    if (!onGuestError(err)) ElMessage.error(errorText(err, '获取下载链接失败'))
  } finally {
    downloadingId.value = null
  }
}

function previewRootFile() {
  if (root.value) openPreview([root.value.id], 0)
}

// ---------- 图标 / 缩略图 ----------

const thumbErrors = ref(new Set<string>())

function onThumbError(id: string) {
  const next = new Set(thumbErrors.value)
  next.add(id)
  thumbErrors.value = next
}

function showThumb(row: ChildItem): boolean {
  return (
    row.kind === 'FILE' &&
    (row.preview.kind === 'IMAGE' || row.preview.kind === 'VIDEO') &&
    !!row.preview.thumbUrl &&
    !thumbErrors.value.has(row.id)
  )
}

function fileIcon(row: ChildItem) {
  if (row.kind === 'FOLDER') return Folder
  switch (row.preview.kind) {
    case 'IMAGE':
      return Picture
    case 'VIDEO':
      return VideoCamera
    case 'AUDIO':
      return Headset
    case 'PDF':
      return Reading
    case 'TEXT':
      return Memo
    default:
      return Document
  }
}

function asChild(row: unknown): ChildItem {
  return row as ChildItem
}

/** 分享整体失效:info 标记过期,或访客请求发现已吊销 */
const dead = computed(
  () => !!info.value?.expired || hasErrorCode(rootErr.value, 'SHARE_EXPIRED'),
)
</script>

<template>
  <div class="visitor">
    <header class="visitor-header">
      <span class="brand">gopan 云盘</span>
      <span class="brand-sub">文件分享</span>
    </header>

    <main class="visitor-main">
      <!-- 入口态:加载 / 不存在 / 已失效 / 验密 -->
      <div v-if="infoLoading && !info" v-loading="true" class="state-box" />

      <el-empty v-else-if="notFound" class="state-box" description="分享链接不存在或已被删除">
        <template #image><el-icon :size="56" class="dead-icon"><WarningFilled /></el-icon></template>
      </el-empty>

      <el-empty v-else-if="infoError" class="state-box" :description="errorText(infoErr, '加载分享信息失败')" />

      <el-empty v-else-if="dead" class="state-box" description="该分享已过期或被取消">
        <template #image><el-icon :size="56" class="dead-icon"><WarningFilled /></el-icon></template>
      </el-empty>

      <!-- 验密卡片 -->
      <div v-else-if="info && !guestToken" class="password-card">
        <el-icon :size="40" class="pwd-icon"><Lock /></el-icon>
        <p class="pwd-name" :title="info.name">{{ info.name }}</p>
        <template v-if="info.needPassword">
          <p class="pwd-hint">该分享受密码保护,请输入密码</p>
          <el-input
            v-model="password"
            type="password"
            placeholder="分享密码"
            show-password
            class="pwd-input"
            @keyup.enter="onSubmitPassword"
          />
          <p v-if="verifyError" class="pwd-error">{{ verifyError }}</p>
          <el-button
            type="primary"
            class="pwd-btn"
            :loading="verifying"
            :disabled="!password"
            @click="onSubmitPassword"
          >
            查看分享
          </el-button>
        </template>
        <p v-else class="pwd-hint" v-loading="true">正在打开分享…</p>
      </div>

      <!-- 内容态 -->
      <template v-else-if="root">
        <!-- 单文件分享:文件卡片 -->
        <div v-if="root.kind === 'FILE'" class="file-card">
          <el-icon :size="48" class="file-icon"><Document /></el-icon>
          <p class="file-name" :title="root.name">{{ root.name }}</p>
          <p class="file-meta">
            {{ formatBytes(root.size) }} · 更新于 {{ formatTime(root.updatedAt) }}
          </p>
          <div class="file-actions">
            <el-button @click="previewRootFile">预览</el-button>
            <el-button
              type="primary"
              :icon="Download"
              :loading="downloadingId === root.id"
              @click="onDownload(root)"
            >
              下载
            </el-button>
          </div>
        </div>

        <!-- 文件夹分享:列表浏览 -->
        <div v-else class="folder-view">
          <div class="folder-bar">
            <el-breadcrumb separator="/" class="crumbs">
              <el-breadcrumb-item>
                <a class="crumb-link" @click.prevent="jumpTo(-1)">{{ root.name }}</a>
              </el-breadcrumb-item>
              <el-breadcrumb-item v-for="(c, i) in navStack" :key="c.id">
                <a class="crumb-link" @click.prevent="jumpTo(i)">{{ c.name }}</a>
              </el-breadcrumb-item>
            </el-breadcrumb>
            <el-button type="primary" :icon="Download" @click="onDownload(root)">
              打包下载全部
            </el-button>
          </div>

          <el-table
            v-loading="listLoading"
            :data="items"
            row-key="id"
            empty-text="这个文件夹是空的"
            @row-dblclick="onRowDblclick"
          >
            <el-table-column label="名称" min-width="320">
              <template #default="{ row }">
                <span class="name-cell" :class="{ folder: asChild(row).kind === 'FOLDER' }">
                  <img
                    v-if="showThumb(asChild(row))"
                    class="name-thumb"
                    :src="asChild(row).preview.thumbUrl!"
                    alt=""
                    loading="lazy"
                    @error="onThumbError(asChild(row).id)"
                  />
                  <el-icon v-else class="name-icon">
                    <component :is="fileIcon(asChild(row))" />
                  </el-icon>
                  {{ asChild(row).name }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="大小" width="120">
              <template #default="{ row }">
                {{ asChild(row).kind === 'FOLDER' ? '—' : formatBytes(asChild(row).size) }}
              </template>
            </el-table-column>
            <el-table-column label="修改时间" width="180">
              <template #default="{ row }">{{ formatTime(asChild(row).updatedAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="80" align="center">
              <template #default="{ row }">
                <el-button
                  link
                  type="primary"
                  :icon="Download"
                  :loading="downloadingId === asChild(row).id"
                  :title="asChild(row).kind === 'FOLDER' ? '打包下载' : '下载'"
                  @click.stop="onDownload(asChild(row))"
                />
              </template>
            </el-table-column>
          </el-table>
        </div>
      </template>
    </main>

    <footer class="visitor-footer">
      <el-icon><Link /></el-icon>
      由 gopan 自部署云盘提供分享服务
    </footer>

    <PreviewModal v-if="guestToken" :guest-token="guestToken" />
  </div>
</template>

<style scoped>
.visitor {
  min-height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--el-fill-color-lighter);
}
.visitor-header {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 16px 28px;
  background: var(--el-bg-color);
  border-bottom: 1px solid var(--el-border-color-light);
}
.brand {
  font-size: 17px;
  font-weight: 600;
}
.brand-sub {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.visitor-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 32px 24px;
}
.state-box {
  margin-top: 10vh;
}
.dead-icon {
  color: var(--el-color-warning);
}

/* 验密卡片 */
.password-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  margin-top: 8vh;
  padding: 36px 44px;
  border-radius: 12px;
  background: var(--el-bg-color);
  box-shadow: var(--el-box-shadow-light);
  width: min(380px, 92vw);
}
.pwd-icon {
  color: var(--el-color-primary);
}
.pwd-name {
  margin: 0;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 16px;
  font-weight: 600;
}
.pwd-hint {
  margin: 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.pwd-input {
  margin-top: 6px;
}
.pwd-error {
  margin: 0;
  align-self: flex-start;
  font-size: 12px;
  color: var(--el-color-danger);
}
.pwd-btn {
  width: 100%;
  margin-top: 6px;
}

/* 单文件卡片 */
.file-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  margin-top: 8vh;
  padding: 40px 56px;
  border-radius: 12px;
  background: var(--el-bg-color);
  box-shadow: var(--el-box-shadow-light);
  max-width: min(520px, 92vw);
}
.file-icon {
  color: var(--el-color-primary);
}
.file-name {
  margin: 0;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 17px;
  font-weight: 600;
}
.file-meta {
  margin: 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.file-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}

/* 文件夹浏览 */
.folder-view {
  width: min(960px, 100%);
  background: var(--el-bg-color);
  border-radius: 10px;
  padding: 16px 20px;
  box-shadow: var(--el-box-shadow-light);
}
.folder-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}
.crumb-link {
  cursor: pointer;
  font-weight: 500;
}
.name-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.name-cell.folder {
  cursor: pointer;
}
.name-icon {
  color: var(--el-color-primary);
}
.name-thumb {
  flex: none;
  width: 28px;
  height: 28px;
  border-radius: 4px;
  object-fit: cover;
  background: var(--el-fill-color-light);
}
.visitor-footer {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 14px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
