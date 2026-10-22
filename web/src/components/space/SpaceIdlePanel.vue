<script setup lang="ts">
import { computed } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Document, Folder } from '@element-plus/icons-vue'

import { request } from '@/api/client'
import { errorText } from '@/api/errors'
import { RevokeFileRequestDocument, RevokeLinkDocument, RevokeShareDocument, SpaceIdleDocument } from '@/api/gen/graphql'
import { useSpaceCleanup } from '@/composables/space'
import { formatRelative, formatTime } from '@/utils/format'
import { idleReason } from '@/utils/space'

/**
 * 分享与直链(doc/23 三.1):已过期的分享与收集链接、90 天没人访问的分享与直链。
 * 吊销分享 / 直链、删除收集链接;文件本身都不动。
 */

const { refresh } = useSpaceCleanup()

const { data, isFetching } = useQuery({
  queryKey: ['space', 'idle'],
  queryFn: () => request(SpaceIdleDocument),
})
const idle = computed(() => data.value?.spaceIdle ?? null)
const empty = computed(
  () => !!idle.value && !idle.value.shares.length && !idle.value.links.length && !idle.value.requests.length,
)

async function confirmThen(title: string, body: string, button: string, run: () => Promise<unknown>, extra: string) {
  try {
    await ElMessageBox.confirm(body, title, { confirmButtonText: button, cancelButtonText: '取消', type: 'warning' })
  } catch {
    return
  }
  try {
    await run()
    ElMessage.success(`已${button}`)
  } catch (err) {
    ElMessage.error(errorText(err, `${button}失败`))
  }
  refresh([extra])
}

function revokeShare(s: { id: string; name: string }) {
  void confirmThen(`吊销「${s.name}」的分享?`, '分享链接立即失效,文件本身不受影响。', '吊销', () => request(RevokeShareDocument, { id: s.id }), 'myShares')
}

function revokeLink(l: { id: string; name: string }) {
  void confirmThen(
    `吊销「${l.name}」的直链?`,
    '这个地址立即失效(404),引用它的网页会裂图。文件本身不受影响。',
    '吊销',
    () => request(RevokeLinkDocument, { id: l.id }),
    'myLinks',
  )
}

function removeRequest(r: { id: string; title: string }) {
  void confirmThen(
    `删除收集链接「${r.title}」?`,
    '已经收到的文件留在原来的文件夹里,不受影响。',
    '删除',
    () => request(RevokeFileRequestDocument, { id: r.id }),
    'myFileRequests',
  )
}
</script>

<template>
  <div v-loading="isFetching && !idle" class="si">
    <el-empty v-if="empty" description="没有过期或闲置的分享、直链与收集链接" />
    <template v-else-if="idle">
      <section v-if="idle.shares.length" class="sec">
        <h4>分享 <span class="count">{{ idle.shares.length }}</span></h4>
        <ul class="rows">
          <li v-for="s in idle.shares" :key="s.id" class="row">
            <el-icon class="icon"><Folder v-if="s.kind === 'FOLDER'" /><Document v-else /></el-icon>
            <div class="main">
              <span class="name" :title="s.name">{{ s.name }}</span>
              <span class="muted">
                <el-tag size="small" :type="s.expired ? 'danger' : 'info'" effect="plain">
                  {{ idleReason({ expired: s.expired, last: s.lastVisitAt }) }}
                </el-tag>
                {{ formatTime(s.createdAt).slice(0, 10) }} 创建
                <template v-if="s.lastVisitAt"> · 最后访问 {{ formatRelative(s.lastVisitAt) }}</template>
                <template v-if="s.expiresAt"> · {{ s.expired ? '过期于' : '到' }} {{ formatTime(s.expiresAt).slice(0, 10) }}</template>
              </span>
            </div>
            <el-button size="small" link type="primary" tag="a" :href="`/s/${s.token}`" target="_blank">打开</el-button>
            <el-button size="small" type="danger" plain @click="revokeShare(s)">吊销</el-button>
          </li>
        </ul>
      </section>

      <section v-if="idle.links.length" class="sec">
        <h4>直链 <span class="count">{{ idle.links.length }}</span></h4>
        <ul class="rows">
          <li v-for="l in idle.links" :key="l.id" class="row">
            <el-icon class="icon"><Document /></el-icon>
            <div class="main">
              <span class="name" :title="l.name">{{ l.name }}</span>
              <span class="muted">
                <el-tag size="small" :type="l.expired ? 'danger' : 'info'" effect="plain">
                  {{ idleReason({ expired: l.expired, last: l.lastHitAt }) }}
                </el-tag>
                累计 {{ l.hits }} 次
                <template v-if="l.lastHitAt"> · 最后访问 {{ formatRelative(l.lastHitAt) }}</template>
              </span>
            </div>
            <el-button size="small" type="danger" plain @click="revokeLink(l)">吊销</el-button>
          </li>
        </ul>
      </section>

      <section v-if="idle.requests.length" class="sec">
        <h4>收集链接 <span class="count">{{ idle.requests.length }}</span></h4>
        <ul class="rows">
          <li v-for="r in idle.requests" :key="r.id" class="row">
            <el-icon class="icon"><Folder /></el-icon>
            <div class="main">
              <span class="name" :title="r.title">{{ r.title }}</span>
              <span class="muted">
                <el-tag size="small" type="danger" effect="plain">已过期</el-tag>
                过期于 {{ formatTime(r.expiresAt).slice(0, 10) }}
              </span>
            </div>
            <el-button size="small" type="danger" plain @click="removeRequest(r)">删除</el-button>
          </li>
        </ul>
      </section>
    </template>
  </div>
</template>

<style scoped>
.si {
  min-height: 160px;
  max-width: 820px;
}
.sec + .sec {
  margin-top: 18px;
}
.sec h4 {
  margin: 0 0 6px;
  font-size: 14px;
}
.count {
  font-weight: 400;
  color: var(--el-text-color-secondary);
}
.rows {
  margin: 0;
  padding: 0;
  list-style: none;
}
.row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 2px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.icon {
  flex: none;
  color: var(--el-color-primary);
}
.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
}
.muted {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 2px 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
