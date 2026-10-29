<script setup lang="ts">
import { computed, ref } from 'vue'

const props = defineProps<{
  title: string
  subtitle: string
  source: string
  items: { label: string; value: number; color: string }[]
  loading?: boolean
}>()
const selected = ref<string | null>(null)
const total = computed(() => props.items.reduce((sum, item) => sum + (Number.isFinite(item.value) && item.value > 0 ? item.value : 0), 0))
const slices = computed(() => {
  let offset = 0
  return props.items.map(item => {
    const value = Number.isFinite(item.value) && item.value > 0 ? item.value : 0
    const percent = total.value ? value / total.value * 100 : 0
    const slice = { ...item, value, percent, offset }
    offset += percent
    return slice
  })
})
const detail = computed(() => slices.value.find(item => item.label === selected.value))
</script>

<template>
  <section class="chart-card" :aria-label="title">
    <header class="chart-heading"><div><h2>{{ title }}</h2><p>{{ subtitle }}</p></div><span class="chart-badge">分布</span></header>
    <div v-if="loading" class="chart-empty" role="status">正在加载图表…</div>
    <template v-else>
      <div class="distribution-body">
        <div class="donut-wrap">
          <svg viewBox="0 0 160 160" class="donut-chart" aria-hidden="true">
            <circle cx="80" cy="80" r="57" fill="none" stroke="#edf2ef" stroke-width="17" />
            <circle v-for="item in slices.filter(slice => slice.value > 0)" :key="item.label"
              cx="80" cy="80" r="57" fill="none" :stroke="item.color" stroke-width="17" pathLength="100"
              :stroke-dasharray="`${item.percent} ${100 - item.percent}`" :stroke-dashoffset="-item.offset"
              transform="rotate(-90 80 80)" :opacity="selected && selected !== item.label ? 0.22 : 1">
              <title>{{ item.label }}：{{ item.value }}（{{ item.percent.toFixed(1) }}%）</title>
            </circle>
          </svg>
          <div class="donut-center"><strong>{{ total }}</strong><span>总计</span></div>
        </div>
        <ul class="chart-legend">
          <li v-for="item in slices" :key="item.label">
            <button type="button" :aria-pressed="selected === item.label" @click="selected = selected === item.label ? null : item.label">
              <i :style="{ background: item.color }" aria-hidden="true"></i><span>{{ item.label }}</span>
              <strong>{{ item.value }}</strong><small>{{ item.percent.toFixed(0) }}%</small>
            </button>
          </li>
        </ul>
      </div>
      <p class="chart-insight" aria-live="polite">{{ total === 0 ? '暂无记录' : detail ? `${detail.label} ${detail.value} 个，占比 ${detail.percent.toFixed(1)}%` : '点击图例可突出查看对应状态' }}</p>
    </template>
    <p class="chart-source">{{ source }}</p>
  </section>
</template>
