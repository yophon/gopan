<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useMutation, useQueryClient } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { CopyDocument, Link } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { CreateShareDocument } from '@/api/gen/graphql'
import type { CreateShareMutation } from '@/api/gen/graphql'

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ node: { id: string; name: string } | null }>()

const queryClient = useQueryClient()

// ---------- 表单 ----------

const usePassword = ref(false)
const password = ref('')
/** 有效期(天),0 = 永久 */
const expireDays = ref(7)
const expireOptions = [
  { label: '1 天', value: 1 },
  { label: '7 天', value: 7 },
  { label: '30 天', value: 30 },
  { label: '永久有效', value: 0 },
]

const created = ref<CreateShareMutation['createShare'] | null>(null)

// 重新打开对话框回到表单态
watch(visible, (v) => {
  if (v) {
    created.value = null
    usePassword.value = false
    password.value = ''
    expireDays.value = 7
  }
})

const shareUrl = computed(() =>
  created.value ? `${location.origin}/s/${created.value.token}` : '',
)

// ---------- 提交 ----------

const createMutation = useMutation({
  mutationFn: () => {
    const expiresAt =
      expireDays.value > 0
        ? new Date(Date.now() + expireDays.value * 86400_000).toISOString()
        : null
    return request(CreateShareDocument, {
      nodeId: props.node!.id,
      password: usePassword.value && password.value ? password.value : null,
      expiresAt,
    })
  },
  onSuccess: (data) => {
    created.value = data.createShare
    void queryClient.invalidateQueries({ queryKey: ['myShares'] })
  },
  onError: (err) => ElMessage.error(errorText(err, '创建分享失败')),
})

function onCreate() {
  if (!props.node) return
  if (usePassword.value && !password.value.trim()) {
    ElMessage.warning('请输入分享密码,或关闭密码保护')
    return
  }
  createMutation.mutate()
}

// ---------- 复制 ----------

async function copyText(text: string, tip: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(tip)
  } catch {
    ElMessage.error('复制失败,请手动复制')
  }
}

function onCopyLink() {
  void copyText(shareUrl.value, '链接已复制')
}

function onCopyAll() {
  const parts = [`链接:${shareUrl.value}`]
  if (created.value?.hasPassword) parts.push(`密码:${password.value}`)
  void copyText(parts.join('  '), '链接与密码已复制')
}
</script>

<template>
  <el-dialog v-model="visible" :title="`分享「${props.node?.name ?? ''}」`" width="440px">
    <!-- 创建表单 -->
    <el-form v-if="!created" label-width="80px" @submit.prevent="onCreate">
      <el-form-item label="有效期">
        <el-radio-group v-model="expireDays">
          <el-radio-button
            v-for="opt in expireOptions"
            :key="opt.value"
            :value="opt.value"
          >
            {{ opt.label }}
          </el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="密码保护">
        <el-switch v-model="usePassword" />
      </el-form-item>
      <el-form-item v-if="usePassword" label="密码">
        <el-input
          v-model="password"
          placeholder="访客需输入此密码"
          maxlength="32"
          show-password
        />
      </el-form-item>
    </el-form>

    <!-- 创建成功:展示链接 -->
    <div v-else class="share-result">
      <el-input :model-value="shareUrl" readonly>
        <template #prefix><el-icon><Link /></el-icon></template>
      </el-input>
      <p v-if="created.hasPassword" class="share-pwd">
        密码:<code>{{ password }}</code>
      </p>
      <p class="share-expire">
        {{ created.expiresAt ? `有效期至 ${new Date(created.expiresAt).toLocaleString()}` : '永久有效' }}
      </p>
    </div>

    <template #footer>
      <template v-if="!created">
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="createMutation.isPending.value" @click="onCreate">
          创建分享
        </el-button>
      </template>
      <template v-else>
        <el-button @click="visible = false">关闭</el-button>
        <el-button v-if="created.hasPassword" :icon="CopyDocument" @click="onCopyAll">
          复制链接和密码
        </el-button>
        <el-button type="primary" :icon="CopyDocument" @click="onCopyLink">复制链接</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<style scoped>
.share-result {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.share-pwd {
  margin: 0;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.share-pwd code {
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  font-weight: 600;
}
.share-expire {
  margin: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
