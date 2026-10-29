<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useWorkspace, label } from '../workspace'
import TransportMap from '../components/TransportMap.vue'
const { overview, loadOverview } = useWorkspace()
const selectedCode = ref('')
const tasks = computed(() => overview.value?.tasks ?? [])
const inTransit = computed(() => tasks.value.filter(task => task.status === 'IN_TRANSIT'))
const selectedTask = computed(() => tasks.value.find(task => task.code === selectedCode.value) ?? null)
const selectedRoute = computed(() => overview.value?.routes.find(route => route.id === selectedTask.value?.routeId) ?? null)
watch(tasks, value => {
  if (value.some(task => task.code === selectedCode.value)) return
  selectedCode.value = value.find(task => task.status === 'IN_TRANSIT')?.code
    ?? value.find(task => task.status === 'PENDING_ACCEPTANCE')?.code ?? value[0]?.code ?? ''
}, { immediate: true })
let poll: ReturnType<typeof setInterval> | undefined
onMounted(() => { poll = setInterval(loadOverview, 10000) })
onUnmounted(() => { if (poll) clearInterval(poll) })
</script>

<template>
  <div class="portal-content">
    <section class="section">
      <div class="section-title"><div><p class="kicker">TRANSPORT MONITORING</p><h2>运输地图监控</h2></div><span class="muted">{{ inTransit.length }} 个运输中任务 · 每 10 秒查询快照</span></div>
      <div class="monitor-layout">
        <aside class="monitor-task-list" aria-label="监控任务选择">
          <div class="monitor-list-heading"><strong>选择运输任务</strong><small>待接单也可预览计划路线</small></div>
          <button v-for="task in tasks" :key="task.code" class="monitor-task-button" :class="{ selected: task.code === selectedCode }" :aria-pressed="task.code === selectedCode" @click="selectedCode = task.code">
            <span class="tag">{{ task.code }}</span><strong>{{ task.originCity }} → {{ task.destinationCity }}</strong>
            <span class="task-status">{{ label(task.status) }}</span><small>演示任务 · GPS 单独查询</small>
          </button>
          <p v-if="!tasks.length" class="source-note">暂无运输任务，请刷新数据。</p>
          <p class="monitor-list-note">任务状态和 GPS 分别查询。地图中的历史位置不表示车辆当前仍在该处。</p>
        </aside>
        <TransportMap title="任务路线与轨迹" :plan="selectedRoute" :task-id="selectedTask?.code" />
      </div>
    </section>
  </div>
</template>
