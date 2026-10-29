<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  title: string
  subtitle: string
  source: string
  unit: string
  items: { label: string; value: number | null; color?: string }[]
}>()
const rows = computed(() => props.items.map(item => ({
  ...item, value: item.value !== null && Number.isFinite(item.value) && item.value >= 0 ? item.value : null,
})))
const maximum = computed(() => Math.max(0, ...rows.value.map(item => item.value ?? 0)))
function format(value: number) {
  return value.toLocaleString('zh-CN', { maximumFractionDigits: 1 })
}
</script>

<template>
  <section class="chart-card bar-chart-card" :aria-label="title">
    <header class="chart-heading"><div><h2>{{ title }}</h2><p>{{ subtitle }}</p></div><div class="chart-controls"><slot name="controls" /></div></header>
    <div v-if="!rows.length" class="chart-empty">暂无可绘制的记录</div>
    <template v-else>
      <div class="bar-axis" aria-hidden="true"><span>0</span><span>{{ format(maximum / 2) }}</span><span>{{ format(maximum) }} {{ unit }}</span></div>
      <dl class="bar-rows">
        <div v-for="item in rows" :key="item.label" class="bar-row">
          <dt>{{ item.label }}</dt>
          <dd>
            <div class="bar-track" aria-hidden="true"><div class="bar-fill" :style="{ width: `${maximum && item.value !== null ? item.value / maximum * 100 : 0}%`, background: item.color ?? 'var(--green)' }"></div></div>
            <strong>{{ item.value === null ? '暂无数据' : `${format(item.value)} ${unit}` }}</strong>
          </dd>
        </div>
      </dl>
      <p v-if="rows.every(item => item.value === null)" class="chart-insight">当前指标尚无有效数值。</p>
    </template>
    <p class="chart-source">{{ source }}</p>
  </section>
</template>
