<script setup lang="ts">
import { computed, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { CopyDocument, Download } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { CreateMcpapiKeyDocument, LinkPrefsDocument, McpapiKeysDocument, RevokeMcpapiKeyDocument } from '@/api/gen/graphql'
import { useBreakpoints } from '@/composables/breakpoints'
import { ClipboardError, showManualCopy, writeClipboard } from '@/composables/links'
import { formatRelative, formatTime } from '@/utils/format'
import { uploadToolConfigs } from '@/utils/imgUpload'
import { IMG_UPLOAD_SCOPE, isImageKey } from '@/utils/mcpScopes'

/**
 * 「上传接口」(doc/22 四.3):图床密钥(scope 只有 img:upload 的 API Key)与各家工具的配置模板。
 * 密钥只在创建时显示一次;模板里先放占位,创建后自动换成新密钥。
 */

const queryClient = useQueryClient()
const { isMobile } = useBreakpoints()

const { data, isFetching } = useQuery({
  queryKey: ['mcpAPIKeys'],
  queryFn: () => request(McpapiKeysDocument),
})
const keys = computed(() => (data.value?.mcpAPIKeys ?? []).filter((k) => isImageKey(k.scopes)))

const { data: prefsData } = useQuery({ queryKey: ['linkPrefs'], queryFn: () => request(LinkPrefsDocument) })
const imageFolder = computed(() => prefsData.value?.linkPrefs.imageFolder ?? '/图床')

const newName = ref('')
const createdKey = ref<string | null>(null)

const createMutation = useMutation({
  mutationFn: () => request(CreateMcpapiKeyDocument, { name: newName.value.trim(), scopes: [IMG_UPLOAD_SCOPE] }),
  onSuccess: (res) => {
    createdKey.value = res.createMCPAPIKey.token
    newName.value = ''
    void queryClient.invalidateQueries({ queryKey: ['mcpAPIKeys'] })
  },
  onError: (err) => ElMessage.error(errorText(err, '创建失败')),
})

const revokeMutation = useMutation({
  mutationFn: (id: string) => request(RevokeMcpapiKeyDocument, { id }),
  onSuccess: () => {
    ElMessage.success('已吊销,用它的工具立即传不上了')
    void queryClient.invalidateQueries({ queryKey: ['mcpAPIKeys'] })
  },
  onError: (err) => ElMessage.error(errorText(err, '吊销失败')),
})

// ---------- 配置模板 ----------

const PLACEHOLDER = '<图床密钥>'
const configs = computed(() => uploadToolConfigs(location.origin, createdKey.value ?? PLACEHOLDER))
const tool = ref<'picgo' | 'sharex' | 'upic' | 'typora' | 'curl'>('picgo')
const toolText = computed(() => configs.value[tool.value])

async function copy(text: string) {
  try {
    await writeClipboard(Promise.resolve(text))
    ElMessage.success('已复制')
  } catch (err) {
    if (err instanceof ClipboardError) showManualCopy(err.text)
  }
}

function downloadSxcu() {
  const blob = new Blob([configs.value.sharex], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = configs.value.sharexFileName
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
</script>

<template>
  <div class="keys-panel">
    <p class="intro">
      PicGo、ShareX、uPic、Typora、curl 用「图床密钥」往 <b>{{ imageFolder }}</b> 传图片,返回直链和 Markdown。
      图床密钥只能传图片(单张 ≤ 20 MB),读不了、列不了网盘里的任何东西;泄露了在这里吊销即可。
    </p>

    <el-alert v-if="createdKey" type="success" :closable="false" class="created">
      <p class="created-label">新图床密钥,只显示这一次(下面的模板已经填好了):</p>
      <p class="mono created-value">
        {{ createdKey }}
        <el-button link type="primary" :icon="CopyDocument" @click="copy(createdKey!)">复制</el-button>
      </p>
    </el-alert>

    <div class="create-row">
      <el-input v-model="newName" placeholder="名称,如 PicGo / 公司电脑 ShareX" maxlength="64" />
      <el-button
        type="primary"
        :disabled="!newName.trim()"
        :loading="createMutation.isPending.value"
        @click="createMutation.mutate()"
      >
        新建图床密钥
      </el-button>
    </div>

    <el-table v-if="!isMobile" v-loading="isFetching && !data" :data="keys" row-key="id" empty-text="还没有图床密钥" size="small">
      <el-table-column label="名称" prop="name" min-width="160" />
      <el-table-column label="创建" width="150">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="最近使用" width="130">
        <template #default="{ row }">{{ row.lastUsedAt ? formatRelative(row.lastUsedAt) : '从未' }}</template>
      </el-table-column>
      <el-table-column label="" width="72" align="center">
        <template #default="{ row }">
          <el-popconfirm
            title="吊销后用它的工具立即传不上,确定?"
            confirm-button-text="吊销"
            cancel-button-text="取消"
            @confirm="revokeMutation.mutate(row.id)"
          >
            <template #reference>
              <el-button link type="danger">吊销</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>
    <ul v-else v-loading="isFetching && !data" class="key-cards">
      <li v-if="!keys.length && !(isFetching && !data)" class="muted">还没有图床密钥</li>
      <li v-for="k in keys" :key="k.id" class="key-card">
        <div class="key-main">
          <span>{{ k.name }}</span>
          <span class="muted">最近使用 {{ k.lastUsedAt ? formatRelative(k.lastUsedAt) : '从未' }}</span>
        </div>
        <el-popconfirm
          title="吊销后用它的工具立即传不上,确定?"
          confirm-button-text="吊销"
          cancel-button-text="取消"
          @confirm="revokeMutation.mutate(k.id)"
        >
          <template #reference>
            <el-button link type="danger">吊销</el-button>
          </template>
        </el-popconfirm>
      </li>
    </ul>

    <h4 class="sub-title">配置模板</h4>
    <p v-if="!createdKey" class="muted">
      密钥只在创建时显示一次。模板里的 {{ PLACEHOLDER }} 换成你自己的密钥;新建一把,这里会自动填好。
    </p>
    <el-segmented
      v-model="tool"
      :options="[
        { label: 'PicGo', value: 'picgo' },
        { label: 'ShareX', value: 'sharex' },
        { label: 'uPic', value: 'upic' },
        { label: 'Typora', value: 'typora' },
        { label: 'curl', value: 'curl' },
      ]"
      class="tool-tabs"
    />
    <div class="tool-box">
      <pre class="mono tool-pre">{{ toolText }}</pre>
      <div class="tool-ops">
        <el-button size="small" :icon="CopyDocument" @click="copy(toolText)">复制</el-button>
        <el-button v-if="tool === 'sharex'" size="small" type="primary" :icon="Download" @click="downloadSxcu">
          下载 .sxcu
        </el-button>
      </div>
    </div>
    <p v-if="tool === 'picgo'" class="muted">PicGo → 插件设置里装「web-uploader」,图床设置 → 自定义 Web 图床,照上面填。</p>
    <p v-else-if="tool === 'sharex'" class="muted">下载后双击 .sxcu 导入,或在 ShareX 的「自定义上传器」里导入。</p>
    <p v-else-if="tool === 'upic'" class="muted">uPic → 偏好设置 → 图床 → 添加「自定义」,照上面填。</p>
  </div>
</template>

<style scoped>
.intro {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
}
.muted {
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.created {
  margin-bottom: 12px;
}
.created-label {
  margin: 0 0 4px;
  font-size: 12px;
}
.created-value {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  word-break: break-all;
}
.create-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  max-width: 560px;
  margin-bottom: 12px;
}
.key-cards {
  margin: 0;
  padding: 0;
  list-style: none;
}
.key-card {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.key-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.sub-title {
  margin: 20px 0 6px;
  font-size: 14px;
}
.tool-tabs {
  margin: 4px 0 8px;
  max-width: 100%;
  overflow-x: auto;
}
.tool-box {
  position: relative;
  border-radius: 6px;
  background: var(--el-fill-color-light);
}
.tool-pre {
  margin: 0;
  padding: 12px 14px 44px;
  overflow-x: auto;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
.tool-ops {
  position: absolute;
  right: 8px;
  bottom: 8px;
  display: flex;
  gap: 8px;
}
.tool-ops :deep(.el-button + .el-button) {
  margin-left: 0;
}
</style>
