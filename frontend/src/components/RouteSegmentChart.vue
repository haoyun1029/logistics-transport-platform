<script setup lang="ts">
import { computed, ref } from 'vue'
import { useWorkspace } from '../workspace'
import MetricBarChart from './MetricBarChart.vue'
const { pendingTaskRoute } = useWorkspace()
const metric = ref<'distance' | 'time'>('distance')
const items = computed(() => pendingTaskRoute.value?.segments.map(segment => ({
  label: `${segment.fromCity} → ${segment.toCity}`,
  value: metric.value === 'distance' ? segment.distanceKm : segment.estimatedMinutes,
})) ?? [])
</script>

<template>
  <MetricBarChart title="计划路段概览" subtitle="查看相邻城市段的演示里程与预计耗时" :items="items" :unit="metric === 'distance' ? 'km' : 'min'"
    :source="`来源：${pendingTaskRoute?.estimateSource ?? '暂无计划路线'}；图中为待核验演示估算值，非司机实测或道路匹配结果。`">
    <template #controls><label class="metric-selector">指标 <select v-model="metric" aria-label="计划路段指标"><option value="distance">路段里程</option><option value="time">预计耗时</option></select></label></template>
  </MetricBarChart>
</template>
