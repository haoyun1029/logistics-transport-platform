<script setup lang="ts">
import { computed } from 'vue'
import { useWorkspace } from '../workspace'
const { overview } = useWorkspace()
import WorkspaceShortcuts from '../WorkspaceShortcuts.vue'
import TaskStatusChart from '../components/TaskStatusChart.vue'
import DistributionChart from '../components/DistributionChart.vue'
const verification = computed(() => [
  { label: '已核验', value: overview.value?.routes.filter(route => route.verificationStatus === 'VERIFIED').length ?? 0, color: '#38937b' },
  { label: '未完成核验', value: overview.value?.routes.filter(route => route.verificationStatus !== 'VERIFIED').length ?? 0, color: '#dfb16a' },
])
</script>

<template>
  <div class="portal-content">
    <div class="metric-grid"><article class="metric-card"><span>待核验路线</span><strong>{{ overview?.routes.filter(route => route.verificationStatus !== 'VERIFIED').length ?? '—' }}</strong><small>逐段补充货车依据后才可优化</small></article><article class="metric-card"><span>有效运输样本</span><strong>{{ overview?.tasks.filter(task => task.sampleStatus === 'VALID').length ?? '—' }}</strong><small>初步推荐至少需要 1 次有效运输</small></article><article class="metric-card"><span>推荐状态</span><strong>{{ overview?.recommendation?.label ?? '—' }}</strong><small>{{ overview?.recommendation?.evidenceLevel ?? '等待评分证据' }}</small></article></div>
    <div class="dashboard-charts section"><TaskStatusChart /><DistributionChart title="路线核验分布" subtitle="核验是进入正式优化的前提" :items="verification" :loading="!overview" source="来源：基础路线演示数据；含计划与候选版本，未完成核验的路线排除正式优化。" /></div>
    <WorkspaceShortcuts portal="admin" />
  </div>
</template>
