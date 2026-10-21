<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useMutation, useQueryClient } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { FetchUrlsDocument } from '@/api/gen/graphql'
import { useUploadsStore } from '@/stores/uploads'
import { MAX_FETCH_URLS, parseUrls } from '@/utils/jobs'

/**
 * 离线下载(doc/21 三):粘贴链接,服务器在后台下载到当前文件夹。
 * 提交后进上传抽屉的「后台任务」分组看进度,关掉页面也会接着下。
 */

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ folderId: string | null; folderName: string }>()

const queryClient = useQueryClient()
const uploads = useUploadsStore()
const text = ref('')
const urls = computed(() => parseUrls(text.value))

watch(visible, (v) => {
  if (v) text.value = ''
})

const mutation = useMutation({
  mutationFn: () => request(FetchUrlsDocument, { urls: urls.value, parentId: props.folderId }),
  onSuccess: (res) => {
    ElMessage.success(`已加入后台任务:${res.fetchUrls.length} 个链接`)
    visible.value = false
    uploads.collapsed = false
    void queryClient.invalidateQueries({ queryKey: ['jobs'] })
  },
  onError: (err) => ElMessage.error(errorText(err, '提交失败')),
})

const tooMany = computed(() => urls.value.length > MAX_FETCH_URLS)
</script>

<template>
  <el-dialog v-model="visible" title="离线下载" width="560px" append-to-body>
    <p class="hint">
      粘贴文件的下载链接(一行一个,最多 {{ MAX_FETCH_URLS }} 条),服务器会在后台下载到
      <b>{{ props.folderName }}</b>,关掉页面也不影响。只支持 http / https 直链,网盘分享页和需要登录的链接下不了。
    </p>
    <el-input
      v-model="text"
      type="textarea"
      :rows="6"
      placeholder="https://example.com/file.zip"
      autofocus
      @keydown.ctrl.enter="urls.length && !tooMany && mutation.mutate()"
    />
    <div class="count" :class="{ error: tooMany }">
      <template v-if="urls.length === 0">没有识别到链接</template>
      <template v-else-if="tooMany">识别到 {{ urls.length }} 条,一次最多 {{ MAX_FETCH_URLS }} 条</template>
      <template v-else>识别到 {{ urls.length }} 条链接</template>
    </div>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button
        type="primary"
        :disabled="urls.length === 0 || tooMany"
        :loading="mutation.isPending.value"
        @click="mutation.mutate()"
      >
        开始下载
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
}
.count {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.count.error {
  color: var(--el-color-danger);
}
</style>
