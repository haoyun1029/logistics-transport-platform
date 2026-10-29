<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useWorkspace } from '../workspace'
import { taskStatus } from '../business'
import TransportMap from '../components/TransportMap.vue'
const { businessTasks, businessRoutes, businessError, loadBusiness } = useWorkspace()
const selected = ref<number | null>(null)
const monitorTasks = computed(() => businessTasks.value.filter(task => task.status === 'IN_TRANSIT'))
const current = computed(() => monitorTasks.value.find(t => t.id === selected.value) ?? monitorTasks.value.find(t => t.status === 'IN_TRANSIT') ?? monitorTasks.value[0] ?? null)
const route = computed(() => businessRoutes.value.find(r => r.databaseId === current.value?.routeId) ?? null)
let poll: ReturnType<typeof setInterval> | undefined
onMounted(() => { poll = setInterval(loadBusiness, 10000) })
onUnmounted(() => { if (poll) clearInterval(poll) })
</script>
<template><section><div class="section-title"><div><p class="kicker">BUSINESS MONITORING</p><h2>运输中的车辆</h2></div><button class="outline-button" @click="loadBusiness">刷新任务</button></div><p v-if="businessError" class="alert error" role="alert">{{ businessError }}</p><p class="source-note">车辆任务信息每 10 秒刷新一次。地图中的位置为演示数据，不是车载设备实时回传。</p><div class="monitor-layout"><aside class="monitor-task-list"><button v-for="task in monitorTasks" :key="task.id" class="monitor-task-button" :class="{ selected: current?.id === task.id }" @click="selected = task.id"><span class="tag">{{ task.code }}</span><strong>{{ task.originCity }} → {{ task.destinationCity }}</strong><span>{{ taskStatus[task.status] }} · {{ task.plateNumber || '待分配车辆' }}</span></button><p v-if="!monitorTasks.length" class="source-note">暂无进行中的运输任务。</p></aside><TransportMap v-if="current" title="车辆位置与运输路线" :plan="route" :task-id="current.code" :trajectory-url="`/api/business/tasks/${current.id}/trajectory`" :presentation="true" :live="true" /><div v-else class="empty-panel"><strong>暂无进行中的运输任务</strong><p>司机接单后，车辆位置会显示在这里。</p></div></div></section></template>
