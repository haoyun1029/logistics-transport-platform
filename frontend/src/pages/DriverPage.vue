<script setup lang="ts">
import { useWorkspace, label } from '../workspace'
const { pendingTask } = useWorkspace()
import WorkspaceShortcuts from '../WorkspaceShortcuts.vue'
import TaskStatusChart from '../components/TaskStatusChart.vue'
</script>

<template>
  <div class="portal-content">
    <div class="driver-banner"><div><p class="kicker">MY TRANSPORT</p><h2>我的运输任务</h2><p>路线、模拟轨迹和送达反馈集中在任务详情内。</p></div><span class="simulated-badge">SIMULATED GPS</span></div>
    <div class="section empty-panel"><strong>{{ pendingTask ? pendingTask.originCity + ' → ' + pendingTask.destinationCity : '暂无当前任务' }}</strong><p>{{ pendingTask ? pendingTask.code + ' · ' + label(pendingTask.status) : '分配的运输任务会显示在当前任务页面。' }}</p></div>
    <div class="section"><TaskStatusChart /></div>
    <WorkspaceShortcuts portal="driver" />
  </div>
</template>
