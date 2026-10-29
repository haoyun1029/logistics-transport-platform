<script setup lang="ts">
import TruckQuoteHistory from '../components/TruckQuoteHistory.vue'
import { useWorkspace, label } from '../workspace'
const { routeAttempts } = useWorkspace()

</script>

<template>
  <div class="portal-content">
    <TruckQuoteHistory />
    <section class="section"><div class="section-title"><div><p class="kicker">VERIFICATION AUDIT</p><h2>未取得可用路线的请求</h2></div><span class="muted">失败请求也保留证据</span></div><div v-if="routeAttempts.length" class="task-table"><div class="table-head"><span>OD</span><span>响应</span><span>状态</span><span>请求时间</span><span>失败原因</span></div><div v-for="attempt in routeAttempts" :key="attempt.id" class="table-row"><span><strong>{{ attempt.originCity || '—' }} → {{ attempt.destinationCity || '—' }}</strong><small>#{{ attempt.id }}</small></span><span>{{ attempt.responseStatus }}</span><span><em class="task-status">{{ label(attempt.verificationStatus) }}</em></span><span>{{ new Date(attempt.requestedAt).toLocaleString() }}</span><span class="pending-text">{{ attempt.failureReason }}</span></div></div><div v-else class="empty-panel"><strong>暂无路线核验请求</strong><p>调用货车路线服务后，无论成功或失败都会保存请求时间、响应状态和排除原因。</p></div></section>
  </div>
</template>
