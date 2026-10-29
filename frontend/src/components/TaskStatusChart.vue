<script setup lang="ts">
import { computed } from 'vue'
import { useWorkspace, label } from '../workspace'
import DistributionChart from './DistributionChart.vue'
const { overview } = useWorkspace()
const states = [
  { status: 'PENDING_ACCEPTANCE', color: '#dfb16a' },
  { status: 'IN_TRANSIT', color: '#6398bf' },
  { status: 'COMPLETED', color: '#38937b' },
  { status: 'CANCELLED', color: '#b5c1ba' },
]
const items = computed(() => {
  const tasks = overview.value?.tasks ?? []
  const counts = states.map(state => ({ label: label(state.status), value: tasks.filter(task => task.status === state.status).length, color: state.color }))
  const other = tasks.filter(task => !states.some(state => state.status === task.status)).length
  return other ? [...counts, { label: '其他状态', value: other, color: '#a295bc' }] : counts
})
</script>

<template>
  <DistributionChart title="演示任务状态" subtitle="当前任务快照 · 随刷新更新" :items="items" :loading="!overview"
    source="来源：固定演示任务接口；不代表正式运输累计或历史趋势。" />
</template>
