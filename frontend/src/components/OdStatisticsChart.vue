<script setup lang="ts">
import { computed, ref } from 'vue'
import { useWorkspace } from '../workspace'
import MetricBarChart from './MetricBarChart.vue'
const { statistics } = useWorkspace()
const metrics = [
  { key: 'taskCount', text: '任务数', unit: '次' },
  { key: 'averageActualElapsedMinutes', text: '平均实际耗时', unit: 'min' },
  { key: 'averageActualDistanceKm', text: '平均实际里程', unit: 'km' },
  { key: 'averageActualFuelLiters', text: '平均实际油耗', unit: 'L' },
  { key: 'anomalyCount', text: '异常次数', unit: '次' },
] as const
const selected = ref<(typeof metrics)[number]['key']>('taskCount')
const current = computed(() => metrics.find(metric => metric.key === selected.value)!)
const items = computed(() => statistics.value?.records.map(record => ({
  label: `${record.originCity} → ${record.destinationCity}`, value: record[selected.value],
})) ?? [])
</script>

<template>
  <MetricBarChart :title="`OD · ${current.text}`" subtitle="仅描述各 OD 运输数据，不作跨 OD 路线优劣评分" :items="items" :unit="current.unit"
    :source="`来源：${statistics?.source ?? '等待 Hadoop 离线统计'}；与下方 HDFS 明细表为同一次 Hadoop 作业结果。`">
    <template #controls><label class="metric-selector">指标 <select v-model="selected" aria-label="运输统计指标"><option v-for="metric in metrics" :key="metric.key" :value="metric.key">{{ metric.text }}</option></select></label></template>
  </MetricBarChart>
</template>
