<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { FolderOpened } from '@element-plus/icons-vue'

import { getErrorCode, request } from '@/api/client'
import { errorText } from '@/api/errors'
import { SearchContentDocument } from '@/api/gen/graphql'
import type { SearchContentQuery } from '@/api/gen/graphql'
import { formatTime } from '@/utils/format'
import { splitHighlights } from '@/utils/highlight'
import { openPreview } from '@/composables/preview'
import NodeIcon from '@/components/nodes/NodeIcon.vue'

/** 搜索页的「内容」标签:按文件里的文字找,结果带命中片段与高亮,PDF 点开直接到命中页(doc/20)。 */

type Hit = SearchContentQuery['searchContent']['items'][number]

const props = defineProps<{ q: string }>()
const router = useRouter()

const hits = ref<Hit[]>([])
const nextCursor = ref<string | null>(null)
const loading = ref(false)
const notice = ref<string | null>(null)

async function fetchPage(cursor: string | null) {
  if (!props.q) {
    hits.value = []
    nextCursor.value = null
    return
  }
  loading.value = true
  notice.value = null
  try {
    const res = await request(SearchContentDocument, { q: props.q, cursor })
    hits.value = cursor ? [...hits.value, ...res.searchContent.items] : [...res.searchContent.items]
    nextCursor.value = res.searchContent.nextCursor ?? null
  } catch (err) {
    hits.value = []
    nextCursor.value = null
    const code = getErrorCode(err)
    notice.value =
      code === 'QUERY_TOO_SHORT'
        ? '按内容搜索至少要两个汉字或三个字母'
        : code === 'CONTENT_SEARCH_DISABLED'
          ? '本站没有开启内容搜索'
          : errorText(err, '搜索失败')
  } finally {
    loading.value = false
  }
}

watch(() => props.q, () => void fetchPage(null), { immediate: true })

function open(i: number) {
  const pages: Record<string, number> = {}
  for (const h of hits.value) if (h.page) pages[h.node.id] = h.page
  openPreview(
    hits.value.map((h) => h.node.id),
    i,
    pages,
  )
}

function locate(h: Hit) {
  void router.push(h.node.parentId ? `/drive/${h.node.parentId}` : '/drive')
}
</script>

<template>
  <div class="content-results">
    <p v-if="notice" class="notice">{{ notice }}</p>
    <div v-else-if="loading && hits.length === 0" v-loading="true" class="placeholder" />
    <el-empty v-else-if="hits.length === 0" description="没有文件的内容包含这个关键词" :image-size="80">
      <template #default>
        <p class="hint">支持 PDF、Word / Excel / PowerPoint、OpenDocument、文本与代码;扫描件里的字搜不到。</p>
      </template>
    </el-empty>

    <ul v-else class="hits">
      <li v-for="(h, i) in hits" :key="h.node.id" class="hit" @click="open(i)">
        <NodeIcon :node="h.node" :size="32" class="hit-icon" />
        <div class="hit-main">
          <div class="hit-title">
            <span class="hit-name" :title="h.node.name">{{ h.node.name }}</span>
            <el-tag v-if="h.page" size="small" type="info">第 {{ h.page }} 页</el-tag>
          </div>
          <p class="snippet">
            <template v-for="(s, k) in splitHighlights(h.snippet, h.highlights)" :key="k">
              <mark v-if="s.hit">{{ s.text }}</mark>
              <template v-else>{{ s.text }}</template>
            </template>
          </p>
          <span class="hit-meta">
            {{ formatTime(h.node.updatedAt) }}
            <el-button link size="small" :icon="FolderOpened" @click.stop="locate(h)">所在目录</el-button>
          </span>
        </div>
      </li>
    </ul>

    <div v-if="nextCursor" class="load-more">
      <el-button :loading="loading" @click="fetchPage(nextCursor)">加载更多</el-button>
    </div>
  </div>
</template>

<style scoped>
.notice,
.hint {
  margin: 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.notice {
  padding: 24px 0;
  text-align: center;
}
.placeholder {
  min-height: 160px;
}
.hits {
  list-style: none;
  margin: 0;
  padding: 0;
}
.hit {
  display: flex;
  gap: 12px;
  padding: 12px 8px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  cursor: pointer;
}
.hit:hover {
  background: var(--el-fill-color-light);
}
.hit-icon {
  flex-shrink: 0;
}
.hit-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.hit-title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.hit-name {
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.snippet {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
  word-break: break-all;
  white-space: pre-line;
}
.snippet mark {
  padding: 0 1px;
  border-radius: 2px;
  background: var(--el-color-warning-light-7);
  color: inherit;
}
.hit-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.load-more {
  display: flex;
  justify-content: center;
  padding: 16px;
}
</style>
