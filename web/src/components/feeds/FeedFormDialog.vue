<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Folder, Search } from '@element-plus/icons-vue'

import { getErrorMessage, request } from '@/api/client'
import { errorText } from '@/api/errors'
import { CreateFeedDocument, PreviewFeedDocument, UpdateFeedDocument } from '@/api/gen/graphql'
import type { FeedFieldsFragment, PreviewFeedQuery } from '@/api/gen/graphql'
import { useBreakpoints } from '@/composables/breakpoints'
import { formatBytes, formatTime } from '@/utils/format'
import {
  INTERVAL_MAX,
  INTERVAL_MIN,
  INTERVAL_PRESETS,
  defaultFolderText,
  feedModeHint,
  feedModeLabel,
  feedModes,
  githubRepo,
  intervalText,
  validateFeedForm,
} from '@/utils/feeds'
import type { FeedKindValue, FeedModeValue } from '@/utils/feeds'
import MoveDialog from '@/components/MoveDialog.vue'

/**
 * 添加 / 修改订阅(doc/23 四.4)。添加:贴地址先预览(标题、最近几条,看出是播客就建议「下载附件」),
 * 再选方式、文件夹、过滤、保留条数、检查间隔;修改:地址不能改,其余同上。
 */

type Preview = PreviewFeedQuery['previewFeed']

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ feed?: FeedFieldsFragment | null }>()
const emit = defineEmits<{ saved: [feed: FeedFieldsFragment] }>()

const { isMobile } = useBreakpoints()
const isEdit = computed(() => !!props.feed)

const urlInput = ref('')
const preview = ref<Preview | null>(null)
const previewing = ref(false)
const saving = ref(false)
const pickVisible = ref(false)

const repo = computed(() => githubRepo(urlInput.value))
const kind = computed<FeedKindValue>(() => props.feed?.kind ?? preview.value?.kind ?? (repo.value ? 'GITHUB' : 'RSS'))
const modes = computed(() => feedModes(kind.value))
/** 添加时要先预览过才显示其余设置 */
const ready = computed(() => isEdit.value || !!preview.value)

const form = reactive({
  title: '',
  folderId: null as string | null,
  folderText: '',
  mode: 'NONE' as FeedModeValue,
  includeWords: '',
  excludeWords: '',
  assetPattern: '',
  prerelease: false,
  keepLast: 0,
  interval: 360 as number | 'custom',
  customMinutes: 360,
  initial: 1,
})

const intervalMinutes = computed(() => (form.interval === 'custom' ? form.customMinutes : form.interval))

function reset() {
  const f = props.feed
  preview.value = null
  urlInput.value = ''
  Object.assign(form, {
    title: f?.title ?? '',
    folderId: null,
    folderText: f ? (f.folder ? f.folder.name : '') : '',
    mode: f?.mode ?? 'NONE',
    includeWords: f?.includeWords ?? '',
    excludeWords: f?.excludeWords ?? '',
    assetPattern: f?.assetPattern ?? '',
    prerelease: f?.prerelease ?? false,
    keepLast: f?.keepLast ?? 0,
    interval: f ? ((INTERVAL_PRESETS as readonly number[]).includes(f.intervalMinutes) ? f.intervalMinutes : 'custom') : 360,
    customMinutes: f?.intervalMinutes ?? 360,
    initial: 1,
  })
}

watch(visible, (v) => {
  if (v) reset()
})

// 改了地址,之前的预览就不算数了
watch(urlInput, () => {
  if (preview.value) preview.value = null
})

async function onPreview() {
  const url = urlInput.value.trim()
  if (!url) return
  previewing.value = true
  try {
    const p = (
      await request(PreviewFeedDocument, {
        url,
        assetPattern: repo.value ? form.assetPattern.trim() || null : null,
        prerelease: repo.value ? form.prerelease : false,
      })
    ).previewFeed
    preview.value = p
    form.title = p.title
    form.mode = feedModes(p.kind).includes(p.suggestedMode) ? p.suggestedMode : 'NONE'
  } catch (err) {
    ElMessage.error({ message: getErrorMessage(err) ?? errorText(err, '没能读出这个地址'), duration: 6000 })
  } finally {
    previewing.value = false
  }
}

function onPicked(id: string | null, path: string) {
  if (!id) return
  form.folderId = id
  form.folderText = path
}

const folderShown = computed(() => {
  if (form.folderId) return form.folderText
  if (isEdit.value) return form.folderText ? `${form.folderText}(不变)` : '原来的文件夹不在了,选一个新的'
  return `${defaultFolderText(form.title)}(默认)`
})

async function onSubmit() {
  const problem = validateFeedForm({
    intervalMinutes: intervalMinutes.value,
    keepLast: form.keepLast,
    initial: isEdit.value ? undefined : form.initial,
  })
  if (problem) {
    ElMessage.warning(problem)
    return
  }
  const common = {
    title: form.title.trim() || null,
    folderId: form.folderId,
    mode: form.mode,
    includeWords: form.includeWords.trim(),
    excludeWords: form.excludeWords.trim(),
    assetPattern: kind.value === 'GITHUB' ? form.assetPattern.trim() : '',
    prerelease: kind.value === 'GITHUB' ? form.prerelease : false,
    keepLast: form.keepLast,
    intervalMinutes: intervalMinutes.value,
  }
  saving.value = true
  try {
    let feed: FeedFieldsFragment
    if (props.feed) {
      feed = (await request(UpdateFeedDocument, { id: props.feed.id, input: { ...common, enabled: props.feed.enabled } }))
        .updateFeed
      ElMessage.success('已保存')
    } else {
      feed = (
        await request(CreateFeedDocument, {
          input: { ...common, url: preview.value?.url ?? urlInput.value.trim(), initial: form.initial },
        })
      ).createFeed
      ElMessage.success(`已订阅「${feed.title}」`)
    }
    emit('saved', feed)
    visible.value = false
  } catch (err) {
    ElMessage.error({ message: getErrorMessage(err) ?? errorText(err, '保存失败'), duration: 6000 })
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="isEdit ? '修改订阅' : '添加订阅'"
    width="min(580px, 94vw)"
    :fullscreen="isMobile"
    append-to-body
    destroy-on-close
  >
    <el-form label-position="top" class="feed-form" @submit.prevent>
      <template v-if="!isEdit">
        <el-form-item label="地址">
          <div class="url-row">
            <el-input
              v-model="urlInput"
              placeholder="RSS / Atom / 播客地址,或 GitHub 仓库(owner/repo)"
              clearable
              autofocus
              @keyup.enter="onPreview"
            />
            <el-button :icon="Search" :loading="previewing" :disabled="!urlInput.trim()" @click="onPreview">
              预览
            </el-button>
          </div>
        </el-form-item>
        <div v-if="repo && !preview" class="hint">
          认出是 GitHub 仓库 <code>{{ repo }}</code>:订阅它的 Releases。先填要哪些文件再预览。
        </div>
      </template>

      <!-- GitHub:资源过滤影响预览,放在预览前面 -->
      <template v-if="kind === 'GITHUB' && (isEdit || repo)">
        <el-form-item label="资源文件名">
          <el-input v-model="form.assetPattern" placeholder="*.apk, linux-amd64(空 = 全部;不带 * 按包含算)" />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="form.prerelease">也要预发布版本</el-checkbox>
        </el-form-item>
      </template>

      <section v-if="preview" class="pv">
        <div class="pv-head">
          <span class="pv-title">{{ preview.title }}</span>
          <el-tag size="small" effect="plain">{{ preview.kind === 'GITHUB' ? 'GitHub' : preview.podcast ? '播客' : 'RSS' }}</el-tag>
        </div>
        <a v-if="preview.siteUrl" class="pv-site" :href="preview.siteUrl" target="_blank" rel="noopener noreferrer">
          {{ preview.siteUrl }}
        </a>
        <ul v-if="preview.items.length" class="pv-items">
          <li v-for="(it, i) in preview.items.slice(0, 5)" :key="i">
            <span class="pv-item-title">{{ it.title || it.url || it.enclosureUrl }}</span>
            <span class="pv-item-meta">
              {{ it.publishedAt ? formatTime(it.publishedAt).slice(0, 10) : '' }}
              <template v-if="it.enclosureSize"> · {{ formatBytes(it.enclosureSize) }}</template>
            </span>
          </li>
        </ul>
        <p v-else class="hint">现在还没有条目{{ preview.kind === 'GITHUB' ? '(或没有匹配的资源文件)' : '' }}。</p>
        <el-button v-if="preview.kind === 'GITHUB'" link size="small" :loading="previewing" @click="onPreview">
          按新的资源过滤重新预览
        </el-button>
      </section>

      <template v-if="ready">
        <el-form-item label="名字">
          <el-input v-model="form.title" :placeholder="isEdit ? '' : preview?.title" maxlength="100" />
        </el-form-item>

        <el-form-item label="方式">
          <el-radio-group v-model="form.mode" class="modes">
            <el-radio v-for="m in modes" :key="m" :value="m" class="mode">
              <span class="mode-label">{{ feedModeLabel[m] }}</span>
              <span class="mode-hint">{{ feedModeHint[m] }}</span>
            </el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="存到">
          <div class="folder-row">
            <span class="folder-text" :title="folderShown">{{ folderShown }}</span>
            <el-button size="small" :icon="Folder" @click="pickVisible = true">选择文件夹</el-button>
            <el-button v-if="!isEdit && form.folderId" size="small" link @click="form.folderId = null">用默认</el-button>
          </div>
        </el-form-item>

        <div class="two-col">
          <el-form-item label="标题包含(任一)">
            <el-input v-model="form.includeWords" placeholder="逗号分隔;空 = 不限" />
          </el-form-item>
          <el-form-item label="标题不含">
            <el-input v-model="form.excludeWords" placeholder="逗号分隔,如:预告, 花絮" />
          </el-form-item>
        </div>

        <el-form-item label="检查间隔">
          <div class="interval-row">
            <el-radio-group v-model="form.interval" size="small">
              <el-radio-button v-for="m in INTERVAL_PRESETS" :key="m" :value="m">{{ intervalText(m) }}</el-radio-button>
              <el-radio-button value="custom">自定义</el-radio-button>
            </el-radio-group>
            <span v-if="form.interval === 'custom'" class="inline">
              <el-input-number
                v-model="form.customMinutes"
                :min="INTERVAL_MIN"
                :max="INTERVAL_MAX"
                :step="30"
                size="small"
                controls-position="right"
              />
              分钟
            </span>
          </div>
        </el-form-item>

        <div class="two-col">
          <el-form-item label="只留最近几条">
            <span class="inline">
              <el-input-number v-model="form.keepLast" :min="0" :max="1000" size="small" controls-position="right" />
              <span class="hint">0 = 不删;超出的旧文件移进回收站</span>
            </span>
          </el-form-item>
          <el-form-item v-if="!isEdit" label="订阅后先处理最新几条">
            <span class="inline">
              <el-input-number v-model="form.initial" :min="0" :max="50" size="small" controls-position="right" />
              <span class="hint">更早的标「已跳过」,详情里能手动下</span>
            </span>
          </el-form-item>
        </div>
      </template>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!ready" @click="onSubmit">
        {{ isEdit ? '保存' : '订阅' }}
      </el-button>
    </template>

    <MoveDialog v-model="pickVisible" mode="pick" :allow-root="false" :exclude-ids="[]" @confirm="onPicked" />
  </el-dialog>
</template>

<style scoped>
.feed-form :deep(.el-form-item) {
  margin-bottom: 14px;
}
.url-row,
.folder-row,
.interval-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  width: 100%;
}
.url-row .el-input {
  flex: 1;
  min-width: 0;
}
.url-row :deep(.el-button + .el-button),
.folder-row :deep(.el-button + .el-button) {
  margin-left: 0;
}
.hint {
  margin: -6px 0 12px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
.inline .hint,
.pv .hint {
  margin: 0;
}
.pv {
  margin-bottom: 16px;
  padding: 10px 12px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
}
.pv-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.pv-title {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 600;
}
.pv-site {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--el-color-primary);
  text-decoration: none;
}
.pv-items {
  margin: 8px 0 4px;
  padding: 0;
  list-style: none;
  font-size: 13px;
}
.pv-items li {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  padding: 2px 0;
}
.pv-item-title {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pv-item-meta {
  flex: none;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.modes {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}
.mode {
  height: auto;
  align-items: flex-start;
  margin-right: 0;
  white-space: normal;
}
.mode :deep(.el-radio__input) {
  margin-top: 3px;
}
.mode-label {
  display: block;
}
.mode-hint {
  display: block;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}
.folder-text {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}
.two-col {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 12px;
}
.inline {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 13px;
}

@media (max-width: 767px) {
  .two-col {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
