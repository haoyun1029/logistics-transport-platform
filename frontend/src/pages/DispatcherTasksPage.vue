<script setup lang="ts">
import { useWorkspace, label } from '../workspace'
const { overview, busyTask, changeTask } = useWorkspace()

</script>

<template>
  <div class="portal-content">
    <section class="section" id="tasks"><div class="section-title"><div><p class="kicker">TASK DISPATCH</p><h2>运输任务</h2></div><span class="muted">状态变更需要权限</span></div><div class="task-table"><div class="table-head"><span>任务</span><span>路线</span><span>状态</span><span>来源</span><span>操作</span></div><div v-for="task in overview?.tasks" :key="task.code" class="table-row task-row"><span><strong>{{ task.code }}</strong><small>{{ task.simulatedTrajectory ? '模拟轨迹' : '待采集' }}</small></span><span>{{ task.originCity }} → {{ task.destinationCity }}</span><span><em class="task-status">{{ label(task.status) }}</em></span><span>{{ task.sampleStatus }}</span><span class="actions"><button v-if="task.status === 'PENDING_ACCEPTANCE'" class="cancel" :disabled="busyTask === task.code" @click="changeTask(task, 'cancel')">取消任务</button><a v-else-if="task.status === 'IN_TRANSIT'" class="text-link" href="/dispatcher/monitoring">查看监控</a><span v-else class="done">—</span></span></div></div></section>
  </div>
</template>
