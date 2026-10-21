<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useMutation, useQueryClient } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { CopyDocument, Link } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { CreateFileRequestDocument, UpdateFileRequestDocument } from '@/api/gen/graphql'
import type { FileRequestFieldsFragment, FileRequestInput } from '@/api/gen/graphql'

/**
 * 收集文件链接:新建(传 folder)或修改(传 request)。doc/20 二.3。
 * 密码与目标文件夹建好后不能改,要改就取消重建。
 */

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{
  folder?: { id: string; name: string } | null
  request?: FileRequestFieldsFragment | null
}>()

const queryClient = useQueryClient()
const editing = computed(() => !!props.request)

const GB = 1024 ** 3
const MB = 1024 ** 2

const expireOptions = [
  { label: '1 天', value: 1 },
  { label: '7 天', value: 7 },
  { label: '30 天', value: 30 },
  { label: '不限', value: 0 },
]
const fileSizeOptions = [
  { label: '不限', value: 0 },
  { label: '100 MB', value: 100 * MB },
  { label: '1 GB', value: GB },
  { label: '5 GB', value: 5 * GB },
]
const totalOptions = [
  { label: '不限', value: 0 },
  { label: '1 GB', value: GB },
  { label: '10 GB', value: 10 * GB },
  { label: '50 GB', value: 50 * GB },
]

const title = ref('')
const note = ref('')
/** 截止(天);-1 = 修改时保持原截止时间 */
const expireDays = ref(7)
const requireName = ref(false)
const groupByUploader = ref(false)
const maxFileSize = ref(0)
const maxTotalBytes = ref(10 * GB)
const maxFiles = ref<number | undefined>(undefined)
const usePassword = ref(false)
const password = ref('')

const created = ref<FileRequestFieldsFragment | null>(null)

function randomCode(): string {
  const alphabet = 'abcdefghjkmnpqrstuvwxyz23456789'
  const buf = new Uint8Array(4)
  crypto.getRandomValues(buf)
  return Array.from(buf, (b) => alphabet[b % alphabet.length]).join('')
}

/** 已有的上限值不在预设里时,补一个选项,免得修改时被悄悄改掉 */
function withCurrent(options: { label: string; value: number }[], v: number | null | undefined) {
  if (!v || options.some((o) => o.value === v)) return options
  const label = v >= GB ? `${+(v / GB).toFixed(2)} GB` : `${+(v / MB).toFixed(1)} MB`
  return [...options, { label, value: v }]
}
const fileSizeChoices = computed(() => withCurrent(fileSizeOptions, props.request?.maxFileSize))
const totalChoices = computed(() => withCurrent(totalOptions, props.request?.maxTotalBytes))

watch(visible, (v) => {
  if (!v) return
  created.value = null
  const r = props.request
  if (r) {
    title.value = r.title
    note.value = r.note
    expireDays.value = -1
    requireName.value = r.requireName
    groupByUploader.value = r.groupByUploader
    maxFileSize.value = r.maxFileSize ?? 0
    maxTotalBytes.value = r.maxTotalBytes ?? 0
    maxFiles.value = r.maxFiles ?? undefined
  } else {
    title.value = props.folder ? `往「${props.folder.name}」传文件` : ''
    note.value = ''
    expireDays.value = 7
    requireName.value = false
    groupByUploader.value = false
    maxFileSize.value = 0
    maxTotalBytes.value = 10 * GB
    maxFiles.value = undefined
    usePassword.value = false
    password.value = randomCode()
  }
})

// 按上传者分文件夹,得知道是谁
watch(groupByUploader, (g) => {
  if (g) requireName.value = true
})

const linkUrl = computed(() => (created.value ? `${location.origin}/c/${created.value.token}` : ''))

function buildInput(): FileRequestInput {
  let expiresAt: string | null = null
  if (expireDays.value === -1) expiresAt = props.request?.expiresAt ?? null
  else if (expireDays.value > 0) expiresAt = new Date(Date.now() + expireDays.value * 86400_000).toISOString()
  return {
    title: title.value.trim(),
    note: note.value.trim(),
    requireName: requireName.value,
    groupByUploader: groupByUploader.value,
    maxFileSize: maxFileSize.value || null,
    maxTotalBytes: maxTotalBytes.value || null,
    maxFiles: maxFiles.value || null,
    expiresAt,
  }
}

const saveMutation = useMutation({
  mutationFn: async () => {
    if (props.request) {
      const res = await request(UpdateFileRequestDocument, { id: props.request.id, input: buildInput() })
      return res.updateFileRequest
    }
    const res = await request(CreateFileRequestDocument, {
      folderId: props.folder!.id,
      input: buildInput(),
      password: usePassword.value ? password.value : null,
    })
    return res.createFileRequest
  },
  onSuccess: (r) => {
    void queryClient.invalidateQueries({ queryKey: ['myFileRequests'] })
    if (editing.value) {
      ElMessage.success('已保存')
      visible.value = false
    } else {
      created.value = r
    }
  },
  onError: (err) => ElMessage.error(errorText(err, editing.value ? '保存失败' : '创建失败')),
})

function onSave() {
  if (!title.value.trim()) {
    ElMessage.warning('请填写标题')
    return
  }
  if (!editing.value && usePassword.value && !password.value.trim()) {
    ElMessage.warning('请输入密码,或关闭密码保护')
    return
  }
  saveMutation.mutate()
}

async function copyText(text: string, tip: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(tip)
  } catch {
    ElMessage.error('复制失败,请手动复制')
  }
}

function onCopyAll() {
  const parts = [`${created.value?.title ?? ''}`, `链接:${linkUrl.value}`]
  if (created.value?.hasPassword) parts.push(`密码:${password.value}`)
  void copyText(parts.join('\n'), created.value?.hasPassword ? '链接与密码已复制' : '链接已复制')
}

const dialogTitle = computed(() =>
  editing.value ? '修改收集链接' : `收集文件到「${props.folder?.name ?? ''}」`,
)
</script>

<template>
  <el-dialog v-model="visible" :title="dialogTitle" width="520px" class="fr-dialog">
    <el-form v-if="!created" label-width="96px" @submit.prevent="onSave">
      <el-form-item label="标题">
        <el-input v-model="title" maxlength="100" placeholder="访客打开链接时看到的标题" />
      </el-form-item>
      <el-form-item label="说明">
        <el-input
          v-model="note"
          type="textarea"
          :rows="2"
          maxlength="1000"
          placeholder="可选:要传什么、怎么命名"
        />
      </el-form-item>
      <el-form-item label="截止">
        <el-radio-group v-model="expireDays">
          <el-radio-button v-if="editing" :value="-1">不变</el-radio-button>
          <el-radio-button v-for="opt in expireOptions" :key="opt.value" :value="opt.value">
            {{ opt.label }}
          </el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="要求留名">
        <el-switch v-model="requireName" :disabled="groupByUploader" />
      </el-form-item>
      <el-form-item label="按人分文件夹">
        <el-switch v-model="groupByUploader" />
        <span class="hint">每人的文件放进以名字命名的子文件夹</span>
      </el-form-item>
      <el-form-item label="单个文件">
        <el-select v-model="maxFileSize" class="limit-select">
          <el-option v-for="o in fileSizeChoices" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="总量上限">
        <el-select v-model="maxTotalBytes" class="limit-select">
          <el-option v-for="o in totalChoices" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <span class="hint">计入你的空间</span>
      </el-form-item>
      <el-form-item label="个数上限">
        <el-input-number v-model="maxFiles" :min="1" :max="100000" placeholder="不限" controls-position="right" />
      </el-form-item>
      <template v-if="!editing">
        <el-form-item label="密码保护">
          <el-switch v-model="usePassword" />
        </el-form-item>
        <el-form-item v-if="usePassword" label="密码">
          <el-input v-model="password" maxlength="32" show-password />
        </el-form-item>
      </template>
    </el-form>

    <div v-else class="fr-result">
      <el-input :model-value="linkUrl" readonly>
        <template #prefix><el-icon><Link /></el-icon></template>
      </el-input>
      <p v-if="created.hasPassword" class="fr-pwd">密码:<code>{{ password }}</code></p>
      <p class="fr-hint">
        {{ created.expiresAt ? `截止 ${new Date(created.expiresAt).toLocaleString()}` : '不限时间' }} ·
        拿到链接的人不用注册就能往这个文件夹传文件,但看不到里面已有的内容
      </p>
    </div>

    <template #footer>
      <template v-if="!created">
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saveMutation.isPending.value" @click="onSave">
          {{ editing ? '保存' : '创建链接' }}
        </el-button>
      </template>
      <template v-else>
        <el-button @click="visible = false">关闭</el-button>
        <el-button type="primary" :icon="CopyDocument" @click="onCopyAll">
          {{ created.hasPassword ? '复制链接和密码' : '复制链接' }}
        </el-button>
      </template>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  margin-left: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.limit-select {
  width: 140px;
}
.fr-result {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.fr-pwd {
  margin: 0;
  font-size: 13px;
}
.fr-pwd code {
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  font-weight: 600;
}
.fr-hint {
  margin: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
