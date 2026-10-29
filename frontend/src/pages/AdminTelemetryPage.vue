<script setup lang="ts">
import { useWorkspace } from '../workspace'
const { telemetry } = useWorkspace()

</script>

<template>
  <div class="portal-content">
    <section class="section"><div class="section-title"><div><p class="kicker">MONGODB EVIDENCE</p><h2>轨迹处理证据</h2></div><span class="muted">Flink → MongoDB 只读摘要</span></div><div v-if="telemetry" class="telemetry-panel"><div class="metric-grid compact"><article class="metric-card"><span>任务</span><strong>{{ telemetry.taskId }}</strong><small>{{ telemetry.status === 'CONNECTED' ? 'MongoDB 查询成功' : '等待 VM 内 MongoDB' }}</small></article><article class="metric-card"><span>有效 GPS 点</span><strong>{{ telemetry.pointCount }}</strong><small>sourceType=SIMULATED</small></article><article class="metric-card"><span>异常点</span><strong>{{ telemetry.anomalyCount }}</strong><small>拒收 {{ telemetry.rejectedCount }} 条</small></article></div><div v-if="telemetry.recentEvidence.length" class="evidence-list"><div v-for="(evidence, index) in telemetry.recentEvidence" :key="evidence.sequence ?? index" class="evidence-row"><strong>序号 {{ evidence.sequence }}</strong><span>{{ evidence.validationStatus }}</span><span>{{ evidence.sourceType }}</span><span>{{ evidence.routeMatchStatus }}</span><small>{{ evidence.deviationStatus }}</small></div></div><p class="source-note">{{ telemetry.detail }} 轨迹未接入已核验道路几何，因此不会被标记为已匹配或可优化。</p></div></section>
  </div>
</template>
