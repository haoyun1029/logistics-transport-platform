<script setup lang="ts">
import { useWorkspace, label } from '../workspace'
import RouteSegmentChart from '../components/RouteSegmentChart.vue'
import TransportMap from '../components/TransportMap.vue'
const { pendingTask, pendingTaskRoute, busyTask, changeTask, startSimulation } = useWorkspace()

</script>

<template>
  <div class="portal-content">
    <section class="section" id="current-task"><div class="section-title"><div><p class="kicker">CURRENT TASK</p><h2>{{ pendingTask ? label(pendingTask.status) : '暂无当前任务' }}</h2></div><span class="muted">司机端专属</span></div><div v-if="pendingTask" class="driver-task"><div class="driver-task-head"><div><span class="tag">{{ pendingTask.code }}</span><h3>{{ pendingTask.originCity }} → {{ pendingTask.destinationCity }}</h3></div><span class="detail-status unverified">{{ label(pendingTask.status) }}</span></div><div class="driver-route"><span v-for="(route, index) in (pendingTaskRoute?.citySequence ?? [])" :key="route"><i></i>{{ route }}<b v-if="index < (pendingTaskRoute?.citySequence.length ?? 0) - 1">→</b></span></div><p class="source-note">任务路线和位置会显示在任务详情地图中。</p><div class="driver-actions"><button v-if="pendingTask.status === 'PENDING_ACCEPTANCE'" class="primary-button" :disabled="busyTask === pendingTask.code" @click="changeTask(pendingTask, 'accept')">确认接单</button><button v-if="pendingTask.status === 'IN_TRANSIT'" class="primary-button" :disabled="busyTask === pendingTask.code" @click="startSimulation(pendingTask)">开始位置演示</button><button v-if="pendingTask.status === 'IN_TRANSIT'" class="primary-button" :disabled="busyTask === pendingTask.code" @click="changeTask(pendingTask, 'complete')">二次确认送达</button></div></div><div v-else class="empty-panel"><strong>暂无待处理任务</strong><p>调度员分配任务后会显示在这里。</p></div></section>
    <div v-if="pendingTaskRoute" class="section"><TransportMap title="本次运输地图" :plan="pendingTaskRoute" :task-id="pendingTask?.code" /></div>
    <div v-if="pendingTaskRoute" class="section"><RouteSegmentChart /></div>
  </div>
</template>
