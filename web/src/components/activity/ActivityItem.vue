<script setup lang="ts">
import { computed } from 'vue'

import type { ActivityFieldsFragment } from '@/api/gen/graphql'
import {
  actionVerb,
  activityPlace,
  activitySize,
  actorKindLabel,
  actorName,
  notUndoableReason,
} from '@/utils/activity'

/**
 * 动态里的一行(doc/23 二.4):时间、来源、动词、名字、位置、撤回。
 * compact = 文件详情里的「历史」:不按天分组,时间带日期;名字不能点(就是这个文件)。
 */
const props = defineProps<{
  activity: ActivityFieldsFragment
  compact?: boolean
  /** 一组展开后的子行:来源已经写在组标题里 */
  hideActor?: boolean
  busy?: boolean
}>()

const emit = defineEmits<{
  undo: [a: ActivityFieldsFragment]
  open: [a: ActivityFieldsFragment]
}>()

const a = computed(() => props.activity)

const time = computed(() => {
  const d = new Date(a.value.at)
  if (Number.isNaN(d.getTime())) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`
  // 文件历史不按天分组,带上日期
  return props.compact ? `${d.getMonth() + 1}-${d.getDate()} ${hm}` : hm
})
const place = computed(() => activityPlace(a.value))
const size = computed(() => activitySize(a.value))
const openable = computed(() => !!a.value.node)
</script>

<template>
  <div class="act-row" :class="{ undone: !!a.undoneAt }">
    <span class="act-time">{{ time }}</span>
    <div class="act-main">
      <div class="act-line">
        <span class="act-verb" :class="`v-${a.action.toLowerCase()}`">{{ actionVerb[a.action] }}</span>
        <!-- 文件历史里就是这个文件本身,名字只是当时叫什么,不用再点 -->
        <button
          v-if="openable && !compact"
          type="button"
          class="act-name"
          :title="`打开所在位置:${a.name}`"
          @click="emit('open', a)"
        >
          {{ a.name }}
        </button>
        <span v-else class="act-name" :title="a.name">{{ a.name }}</span>
        <el-tag v-if="a.undoneAt" size="small" type="info" effect="plain">已撤回</el-tag>
        <el-tag v-if="a.undoOf" size="small" effect="plain">撤回操作</el-tag>
      </div>
      <div class="act-sub">
        <span v-if="!hideActor" class="act-actor">
          <span class="actor-name" :title="a.actor.label">{{ actorName(a.actor) }}</span>
          <span class="actor-kind">{{ actorKindLabel[a.actor.kind] }}</span>
        </span>
        <span class="act-place" :title="place">{{ place }}</span>
        <span v-if="size" class="act-size">{{ size }}</span>
        <span v-if="a.note" class="act-note" :title="a.note">{{ a.note }}</span>
      </div>
    </div>
    <el-tooltip :disabled="a.undoable" :content="notUndoableReason(a)" placement="left">
      <span class="act-op">
        <el-button size="small" :disabled="!a.undoable || busy" @click="emit('undo', a)">撤回</el-button>
      </span>
    </el-tooltip>
  </div>
</template>

<style scoped>
.act-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 8px 4px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.act-row.undone .act-verb,
.act-row.undone .act-name {
  color: var(--el-text-color-secondary);
  text-decoration: line-through;
}
.act-time {
  flex: none;
  min-width: 40px;
  padding-top: 1px;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  color: var(--el-text-color-secondary);
}
.act-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.act-line {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  font-size: 14px;
}
.act-verb {
  flex: none;
  color: var(--el-text-color-regular);
}
.act-verb.v-delete,
.act-verb.v-purge {
  color: var(--el-color-danger);
}
.act-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--el-text-color-primary);
}
button.act-name {
  padding: 0;
  border: none;
  background: none;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
button.act-name:hover {
  color: var(--el-color-primary);
  text-decoration: underline;
}
.act-sub {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px 10px;
  min-width: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.act-actor {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 100%;
}
.actor-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--el-text-color-regular);
}
.actor-kind {
  flex: none;
  padding: 0 4px;
  border: 1px solid var(--el-border-color);
  border-radius: 3px;
  font-size: 11px;
  line-height: 16px;
}
.act-place,
.act-note {
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.act-op {
  flex: none;
}
</style>
