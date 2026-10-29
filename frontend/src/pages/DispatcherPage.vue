<script setup lang="ts">
import { useWorkspace } from '../workspace'
const { overview } = useWorkspace()
import WorkspaceShortcuts from '../WorkspaceShortcuts.vue'
import TaskStatusChart from '../components/TaskStatusChart.vue'
import DistributionChart from '../components/DistributionChart.vue'
import { demoFleet } from '../fleet'
const fleetDistribution = [
  { label: '空闲', color: '#38937b' }, { label: '运输中', color: '#6398bf' }, { label: '维修中', color: '#dfb16a' },
].map(state => ({ ...state, value: demoFleet.filter(vehicle => vehicle.status === state.label).length }))
</script>

<template>
  <div class="portal-content">
    <div class="metric-grid"><article class="metric-card"><span>待接单</span><strong>{{ overview?.tasks.filter(task => task.status === 'PENDING_ACCEPTANCE').length ?? '—' }}</strong><small>等待司机确认</small></article><article class="metric-card"><span>运输中</span><strong>{{ overview?.tasks.filter(task => task.status === 'IN_TRANSIT').length ?? '—' }}</strong><small>实时监控任务</small></article><article class="metric-card"><span>可用车辆</span><strong>{{ demoFleet.filter(vehicle => vehicle.status === '空闲').length }}</strong><small>空闲车辆 · 演示车队数据</small></article></div>
    <div class="dashboard-charts section"><TaskStatusChart /><DistributionChart title="演示车辆状态" subtitle="与下方车辆列表一致" :items="fleetDistribution" source="来源：固定演示车队，共 3 辆；尚未连接正式车辆状态接口。" /></div>
    <WorkspaceShortcuts portal="dispatcher" />
    <section class="section" id="vehicles"><div class="section-title"><div><p class="kicker">FLEET STATUS</p><h2>车辆状态</h2></div><span class="muted">固定演示车队 · 3 辆</span></div><div class="fleet-grid"><article v-for="vehicle in demoFleet" :key="vehicle.plate" class="fleet-card"><span class="vehicle-icon">▣</span><div><strong>{{ vehicle.plate }}</strong><small>{{ vehicle.type }}</small></div><em :class="vehicle.status === '空闲' ? 'verified' : vehicle.status === '运输中' ? 'moving' : 'unverified'">{{ vehicle.status }}</em></article></div></section>
  </div>
</template>
