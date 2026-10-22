<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'

import { request } from '@/api/client'
import { SpaceEmptyFoldersDocument, SpaceLargeFilesDocument, SpaceStaleFilesDocument } from '@/api/gen/graphql'
import { FILE_CATEGORIES, categoryLabel } from '@/utils/space'
import type { FileCategoryValue } from '@/utils/space'
import SpaceOverviewPanel from '@/components/space/SpaceOverviewPanel.vue'
import SpaceFoldersPanel from '@/components/space/SpaceFoldersPanel.vue'
import SpaceFileList from '@/components/space/SpaceFileList.vue'
import SpaceDuplicatesPanel from '@/components/space/SpaceDuplicatesPanel.vue'
import SpaceIdlePanel from '@/components/space/SpaceIdlePanel.vue'

/**
 * 空间分析(doc/23 三.1):一页看清空间花在哪。全是现查的只读查询;清理一律移进回收站(能还原、记进动态)。
 * 当前区块放在 ?tab= 里,刷新不丢。
 */

const route = useRoute()
const router = useRouter()

const TABS = ['overview', 'folders', 'large', 'duplicates', 'stale', 'empty', 'idle'] as const
type Tab = (typeof TABS)[number]

const tab = computed<Tab>({
  get: () => (TABS.includes(route.query.tab as Tab) ? (route.query.tab as Tab) : 'overview'),
  set: (v) => void router.replace({ query: v === 'overview' ? {} : { tab: v } }),
})

// ---------- 大文件 ----------

const category = ref<FileCategoryValue | null>(null)
const large = useQuery({
  queryKey: computed(() => ['space', 'large', category.value]),
  queryFn: () => request(SpaceLargeFilesDocument, { category: category.value, limit: 100 }),
  enabled: computed(() => tab.value === 'large'),
})

// ---------- 很久没动 / 空文件夹 ----------

const stale = useQuery({
  queryKey: ['space', 'stale'],
  queryFn: () => request(SpaceStaleFilesDocument, {}),
  enabled: computed(() => tab.value === 'stale'),
})

const empty = useQuery({
  queryKey: ['space', 'empty'],
  queryFn: () => request(SpaceEmptyFoldersDocument, {}),
  enabled: computed(() => tab.value === 'empty'),
})
</script>

<template>
  <div class="space">
    <h2 class="page-title">空间</h2>

    <el-tabs v-model="tab" class="space-tabs">
      <el-tab-pane label="总览" name="overview" lazy>
        <SpaceOverviewPanel @go="(t) => (tab = t as Tab)" />
      </el-tab-pane>

      <el-tab-pane label="文件夹" name="folders" lazy>
        <SpaceFoldersPanel />
      </el-tab-pane>

      <el-tab-pane label="大文件" name="large" lazy>
        <p class="intro">最大的 100 个文件。</p>
        <div class="chips">
          <el-check-tag :checked="category === null" @change="category = null">全部</el-check-tag>
          <el-check-tag
            v-for="c in FILE_CATEGORIES"
            :key="c"
            :checked="category === c"
            @change="category = c"
          >
            {{ categoryLabel[c] }}
          </el-check-tag>
        </div>
        <SpaceFileList
          :items="large.data.value?.spaceLargeFiles ?? []"
          :loading="large.isFetching.value"
          empty-text="没有这类文件"
        />
      </el-tab-pane>

      <el-tab-pane label="重复文件" name="duplicates" lazy>
        <SpaceDuplicatesPanel />
      </el-tab-pane>

      <el-tab-pane label="很久没动" name="stale" lazy>
        <p class="intro">一年以上没改过、不小于 100 MB 的文件(按修改时间算,不记谁看过)。</p>
        <SpaceFileList
          :items="stale.data.value?.spaceStaleFiles ?? []"
          :loading="stale.isFetching.value"
          empty-text="没有很久没动的大文件"
        />
      </el-tab-pane>

      <el-tab-pane label="空文件夹" name="empty" lazy>
        <p class="intro">子树里一个文件都没有的文件夹(只列最上面一层;收集链接、订阅、传输助手用着的不算)。</p>
        <SpaceFileList
          :items="empty.data.value?.spaceEmptyFolders ?? []"
          :loading="empty.isFetching.value"
          empty-text="没有空文件夹"
          folders
        />
      </el-tab-pane>

      <el-tab-pane label="分享与直链" name="idle" lazy>
        <p class="intro">已过期的分享与收集链接、90 天没人访问的分享与直链。</p>
        <SpaceIdlePanel />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.page-title {
  margin: 0 0 8px;
  font-size: 18px;
  font-weight: 600;
}
.space-tabs {
  min-width: 0;
}
.intro {
  margin: 0 0 10px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 10px;
}
</style>
