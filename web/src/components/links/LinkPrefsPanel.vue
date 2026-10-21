<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { LinkPrefsDocument, SetLinkPrefsDocument } from '@/api/gen/graphql'
import { useBreakpoints } from '@/composables/breakpoints'
import { formatBytes } from '@/utils/format'
import { parseHostList } from '@/utils/links'

/** 「默认规则」(doc/22 四.2):默认来源白名单、无来源访问、图床文件夹、抹 GPS;全站上限只读 */

const queryClient = useQueryClient()
const { isMobile } = useBreakpoints()

const { data, isFetching } = useQuery({ queryKey: ['linkPrefs'], queryFn: () => request(LinkPrefsDocument) })
const prefs = computed(() => data.value?.linkPrefs ?? null)

const form = ref({ hostsText: '', allowNoReferer: true, imageFolder: '/图床', stripGps: true })
const saving = ref(false)

watch(
  prefs,
  (p) => {
    if (!p) return
    form.value = {
      hostsText: p.refererHosts.join('\n'),
      allowNoReferer: p.allowNoReferer,
      imageFolder: p.imageFolder,
      stripGps: p.stripGps,
    }
  },
  { immediate: true },
)

const parsedHosts = computed(() => parseHostList(form.value.hostsText))
const monthPercent = computed(() => {
  const p = prefs.value
  if (!p || p.monthlyBytesCap <= 0) return 0
  return Math.min(100, Math.round((p.monthlyBytesUsed / p.monthlyBytesCap) * 100))
})

async function onSave() {
  const folder = form.value.imageFolder.trim()
  if (!folder.startsWith('/')) {
    ElMessage.warning('图床文件夹写网盘里的路径,以 / 开头,如 /图床')
    return
  }
  saving.value = true
  try {
    const res = await request(SetLinkPrefsDocument, {
      input: {
        refererHosts: parsedHosts.value,
        allowNoReferer: form.value.allowNoReferer,
        imageFolder: folder,
        stripGps: form.value.stripGps,
      },
    })
    queryClient.setQueryData(['linkPrefs'], { linkPrefs: res.setLinkPrefs })
    ElMessage.success('默认规则已保存,没单独设置的直链立即按新规则')
  } catch (err) {
    ElMessage.error(errorText(err, '保存失败'))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div v-loading="isFetching && !prefs" class="prefs-panel">
    <el-form v-if="prefs" :label-position="isMobile ? 'top' : 'right'" label-width="132px" @submit.prevent>
      <el-form-item label="默认来源白名单">
        <div class="field">
          <el-input
            v-model="form.hostsText"
            type="textarea"
            :rows="4"
            placeholder="一行一个,如 blog.example.com;写 example.com 包含它的子域名。留空 = 不限来源"
          />
          <div class="muted">
            <template v-if="parsedHosts.length">只允许:{{ parsedHosts.join('、') }}(及其子域名)</template>
            <template v-else>不限来源,任何网站都能引用</template>
          </div>
        </div>
      </el-form-item>
      <el-form-item label="允许无来源访问">
        <div class="field">
          <el-switch v-model="form.allowNoReferer" />
          <div class="muted">直接在浏览器里打开、App 里看这类没有 Referer 的访问</div>
        </div>
      </el-form-item>
      <el-form-item label="图床文件夹">
        <div class="field">
          <el-input v-model="form.imageFolder" placeholder="/图床" />
          <div class="muted">图床接口与这里粘贴上传的图片存到这个文件夹下的「年-月」子文件夹,按需创建</div>
        </div>
      </el-form-item>
      <el-form-item label="上传时抹掉 GPS">
        <div class="field">
          <el-switch v-model="form.stripGps" />
          <div class="muted">公开出去的照片最容易泄露住址;只抹拍摄位置,不重新压缩,方向等信息保留</div>
        </div>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </el-form-item>
    </el-form>

    <div v-if="prefs" class="limits">
      <div class="limit-cell">
        <span class="limit-label">本月全站直链流量</span>
        <span class="limit-value">{{ formatBytes(prefs.monthlyBytesUsed) }} / {{ formatBytes(prefs.monthlyBytesCap) }}</span>
        <el-progress
          :percentage="monthPercent"
          :status="monthPercent >= 100 ? 'exception' : monthPercent >= 80 ? 'warning' : undefined"
          :stroke-width="6"
          :show-text="false"
        />
        <span class="muted">到上限后本月所有直链暂停(503),由管理员在服务器上调整</span>
      </div>
      <div class="limit-cell">
        <span class="limit-label">每条直链每天默认上限</span>
        <span class="limit-value">{{ formatBytes(prefs.dailyBytesCapDefault) }}</span>
        <span class="muted">超了自动暂停到次日并提醒;单条可在详情里改</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.prefs-panel {
  max-width: 760px;
  min-height: 120px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
}
.muted {
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}
.limits {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-top: 8px;
}
.limit-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}
.limit-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.limit-value {
  font-size: 16px;
  font-weight: 600;
}
@media (max-width: 767px) {
  .limits {
    grid-template-columns: 1fr;
  }
}
</style>
