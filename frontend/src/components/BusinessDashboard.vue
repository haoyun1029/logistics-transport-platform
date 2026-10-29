<script setup lang="ts">
import { computed } from 'vue'
import { useWorkspace } from '../workspace'
import { taskStatus, vehicleStatus } from '../business'
import DistributionChart from './DistributionChart.vue'
import WorkspaceShortcuts from '../WorkspaceShortcuts.vue'
import type { Portal } from '../auth'

const props = defineProps<{ portal: Portal }>()
const { businessTasks, businessRoutes, vehicles, businessError, businessLoaded } = useWorkspace()

const taskItems = computed(() => Object.entries(taskStatus).map(([key, label], index) => ({
  label,
  value: businessTasks.value.filter(task => task.status === key).length,
  color: ['#cbb988', '#7ca494', '#6099bd', '#388d75', '#c2816f'][index]!,
})))

const fleetItems = computed(() => Object.entries(vehicleStatus).map(([key, label], index) => ({
  label,
  value: vehicles.value.filter(vehicle => vehicle.status === key).length,
  color: ['#388d75', '#cbb988', '#6099bd', '#c2816f'][index]!,
})))

const current = computed(() => businessTasks.value.find(task => ['IN_TRANSIT', 'PENDING_ACCEPTANCE'].includes(task.status)))
const today = computed(() => {
  const now = new Date()
  return businessTasks.value.filter(task => {
    const created = new Date(task.createdAt)
    return created.getFullYear() === now.getFullYear() && created.getMonth() === now.getMonth() && created.getDate() === now.getDate()
  })
})
const dispatcherMetrics = computed(() => [
  { label: '今日运输任务', value: today.value.length, hint: '按创建时间统计' },
  { label: '待分配任务', value: businessTasks.value.filter(task => task.status === 'PENDING_ASSIGNMENT').length, hint: '等待司机与车辆' },
  { label: '运输中车辆', value: vehicles.value.filter(vehicle => vehicle.status === 'IN_TRANSIT').length, hint: '已接单运输' },
  { label: '异常车辆', value: vehicles.value.filter(vehicle => vehicle.status === 'MAINTENANCE').length, hint: '维修中或不可调度' },
  { label: '完成任务数量', value: businessTasks.value.filter(task => task.status === 'COMPLETED').length, hint: '累计已完成运输' },
])

const dispatcherVehicleGroups = computed(() => Object.entries(vehicleStatus).map(([key, label]) => ({
  key,
  label,
  vehicles: vehicles.value.filter(vehicle => vehicle.status === key),
})))
</script>

<template>
  <div>
    <p v-if="businessError" class="alert error" role="alert">{{ businessError }}</p>

    <template v-if="portal === 'dispatcher'">
      <section class="dispatcher-center section" aria-label="调度中心概览">
        <div class="section-title">
          <div><p class="kicker">DISPATCH CENTER</p><h2>首页 / 调度中心</h2></div>
          <span class="muted">运输任务、车队状态和异常概览</span>
        </div>
        <div class="metric-grid dispatcher-metrics">
          <article v-for="metric in dispatcherMetrics" :key="metric.label" class="metric-card">
            <span>{{ metric.label }}</span>
            <strong>{{ businessLoaded ? metric.value : '—' }}</strong>
            <small>{{ metric.hint }}</small>
          </article>
        </div>
      </section>

      <section class="section dispatcher-status-grid">
        <article class="business-panel">
          <div class="section-title"><div><p class="kicker">FLEET STATUS</p><h2>车队状态</h2></div><a class="text-link" href="/dispatcher/vehicles">查看车辆 →</a></div>
          <div class="fleet-status-list">
            <div v-for="group in dispatcherVehicleGroups" :key="group.key" class="fleet-status-row">
              <div><strong>{{ group.label }}</strong><small>{{ group.vehicles.length }} 辆</small></div>
              <span>{{ group.vehicles.map(vehicle => vehicle.plateNumber).join('、') || '暂无车辆' }}</span>
            </div>
          </div>
        </article>
        <article class="business-panel">
          <div class="section-title"><div><p class="kicker">TODAY'S WORK</p><h2>今日任务</h2></div><a class="text-link" href="/dispatcher/tasks">任务管理 →</a></div>
          <div v-if="today.length" class="center-task-list">
            <div v-for="task in today.slice(0, 5)" :key="task.id" class="center-task-row"><span class="tag">{{ task.code }}</span><strong>{{ task.originCity }} → {{ task.destinationCity }}</strong><small>{{ taskStatus[task.status] }}</small></div>
          </div>
          <div v-else class="empty-panel"><strong>{{ businessLoaded ? '今天还没有运输任务' : '等待业务数据' }}</strong><p>创建任务后会在这里显示。</p></div>
        </article>
      </section>

      <div class="dashboard-charts section">
        <DistributionChart title="业务任务状态" subtitle="当前账号可访问的运输记录" :items="taskItems" source="来源：MySQL 运输任务；未使用固定演示任务。" />
        <DistributionChart title="业务车辆状态" subtitle="空闲、待发车、运输中和维修中" :items="fleetItems" source="来源：MySQL 车辆及任务占用；待接单车辆不可再次分配。" />
      </div>
      <WorkspaceShortcuts portal="dispatcher" />
    </template>

    <template v-else>
      <p class="kicker">BUSINESS DASHBOARD</p>
      <div class="metric-grid">
        <article class="metric-card"><span>{{ portal === 'driver' ? '我的运输任务' : '业务运输任务' }}</span><strong>{{ businessLoaded ? businessTasks.length : '—' }}</strong><small>MySQL 持久化记录</small></article>
        <article class="metric-card"><span>{{ portal === 'driver' ? '已完成运输' : '运输中任务' }}</span><strong>{{ businessLoaded ? businessTasks.filter(task => task.status === (portal === 'driver' ? 'COMPLETED' : 'IN_TRANSIT')).length : '—' }}</strong><small>按当前账号权限查询</small></article>
        <article class="metric-card"><span>{{ portal === 'admin' ? '已发布路线版本' : '可用车辆' }}</span><strong>{{ businessLoaded ? portal === 'admin' ? businessRoutes.filter(route => route.verificationStatus === 'VERIFIED').length : vehicles.filter(vehicle => vehicle.status === 'AVAILABLE').length : '—' }}</strong><small>{{ portal === 'admin' ? '全部道路段核验通过' : '司机与车辆占用由后端校验' }}</small></article>
      </div>
      <div v-if="portal === 'driver'" class="business-panel section"><h2>{{ current ? `${current.originCity} → ${current.destinationCity}` : '暂无待执行任务' }}</h2><p>{{ current ? `${current.code} · ${taskStatus[current.status]}` : '调度员分配给你的任务会显示在当前运输任务中。' }}</p><a class="text-link" href="/driver/tasks">查看我的任务 →</a></div>
      <div class="dashboard-charts section"><DistributionChart title="业务任务状态" subtitle="当前账号可访问的运输记录" :items="taskItems" source="来源：MySQL 运输任务；未使用固定演示任务。" /><DistributionChart v-if="portal !== 'driver'" title="业务车辆状态" subtitle="空闲、待发车、运输中和维修中" :items="fleetItems" source="来源：MySQL 车辆及任务占用；待接单车辆不可再次分配。" /></div>
      <WorkspaceShortcuts :portal="props.portal" />
    </template>
  </div>
</template>
