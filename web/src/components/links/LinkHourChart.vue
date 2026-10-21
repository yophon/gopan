<script setup lang="ts">
import { computed, ref } from 'vue'

import { formatBytes } from '@/utils/format'
import { fillHourBuckets } from '@/utils/links'
import type { HourBucket, HourStatLike } from '@/utils/links'

/** 最近 N 小时的柱状图(doc/22 四.4):纯 SVG,不引图表库。点 / 悬停看某一小时的数 */
const props = withDefaults(defineProps<{ stats: HourStatLike[]; hours?: number }>(), { hours: 168 })

const metric = ref<'hits' | 'bytes'>('hits')
const buckets = computed(() => fillHourBuckets(props.stats, props.hours))
const max = computed(() => Math.max(0, ...buckets.value.map((b) => b[metric.value])))
const totals = computed(() =>
  buckets.value.reduce(
    (acc, b) => ({ hits: acc.hits + b.hits, bytes: acc.bytes + b.bytes, blocked: acc.blocked + b.blocked }),
    { hits: 0, bytes: 0, blocked: 0 },
  ),
)

/** 柱高(0–100);有数但很小的也给一点高度,免得看不见 */
function height(b: HourBucket): number {
  const v = b[metric.value]
  if (!v || !max.value) return 0
  return Math.max(2, (v / max.value) * 100)
}

function blockedHeight(b: HourBucket): number {
  if (metric.value !== 'hits' || !b.blocked || !max.value) return 0
  return Math.max(1, (Math.min(b.blocked, b.hits || b.blocked) / max.value) * 100)
}

/** 每天零点一个刻度 */
const ticks = computed(() =>
  buckets.value
    .map((b, i) => ({ i, d: new Date(b.at) }))
    .filter(({ d }) => d.getHours() === 0)
    .map(({ i, d }) => ({ left: (i / buckets.value.length) * 100, label: `${d.getMonth() + 1}/${d.getDate()}` })),
)

const active = ref<number | null>(null)
const svgEl = ref<SVGSVGElement | null>(null)

function onPointer(e: PointerEvent) {
  const el = svgEl.value
  if (!el) return
  const rect = el.getBoundingClientRect()
  if (rect.width <= 0) return
  const i = Math.floor(((e.clientX - rect.left) / rect.width) * buckets.value.length)
  active.value = Math.min(Math.max(i, 0), buckets.value.length - 1)
}

const activeText = computed(() => {
  const b = active.value === null ? null : buckets.value[active.value]
  if (!b) return ''
  const d = new Date(b.at)
  const pad = (n: number) => String(n).padStart(2, '0')
  const when = `${d.getMonth() + 1}/${d.getDate()} ${pad(d.getHours())}:00`
  const blocked = b.blocked ? ` · 拦下 ${b.blocked}` : ''
  return `${when} · ${b.hits} 次 · ${formatBytes(b.bytes)}${blocked}`
})
</script>

<template>
  <div class="hour-chart">
    <div class="chart-head">
      <el-segmented
        v-model="metric"
        size="small"
        :options="[
          { label: '次数', value: 'hits' },
          { label: '流量', value: 'bytes' },
        ]"
      />
      <span class="chart-info">
        {{ activeText || `${Math.round(props.hours / 24)} 天共 ${totals.hits} 次 · ${formatBytes(totals.bytes)}${totals.blocked ? ` · 拦下 ${totals.blocked}` : ''}` }}
      </span>
    </div>
    <svg
      ref="svgEl"
      class="bars"
      :viewBox="`0 0 ${buckets.length} 100`"
      preserveAspectRatio="none"
      role="img"
      aria-label="按小时的访问柱状图"
      @pointermove="onPointer"
      @pointerdown="onPointer"
      @pointerleave="active = null"
    >
      <rect
        v-for="(b, i) in buckets"
        :key="b.at"
        :x="i + 0.1"
        :width="0.8"
        :y="100 - height(b)"
        :height="height(b)"
        class="bar"
        :class="{ active: active === i }"
      />
      <template v-for="(b, i) in buckets" :key="`blk-${b.at}`">
        <rect
          v-if="blockedHeight(b) > 0"
          :x="i + 0.1"
          :width="0.8"
          :y="100 - blockedHeight(b)"
          :height="blockedHeight(b)"
          class="bar blocked"
        />
      </template>
    </svg>
    <div class="axis">
      <span v-for="t in ticks" :key="t.left" class="tick" :style="{ left: `${t.left}%` }">{{ t.label }}</span>
    </div>
    <div v-if="max === 0" class="chart-empty">这段时间没有访问</div>
  </div>
</template>

<style scoped>
.hour-chart {
  position: relative;
}
.chart-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}
.chart-info {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
  text-align: right;
}
.bars {
  display: block;
  width: 100%;
  height: 120px;
  border-bottom: 1px solid var(--el-border-color);
  touch-action: pan-y;
}
.bar {
  fill: var(--el-color-primary-light-3);
}
.bar.active {
  fill: var(--el-color-primary);
}
.bar.blocked {
  fill: var(--el-color-danger-light-3);
  pointer-events: none;
}
.axis {
  position: relative;
  height: 18px;
}
.tick {
  position: absolute;
  top: 2px;
  transform: translateX(-50%);
  font-size: 11px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}
.chart-empty {
  position: absolute;
  top: 70px;
  left: 0;
  right: 0;
  text-align: center;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  pointer-events: none;
}
</style>
