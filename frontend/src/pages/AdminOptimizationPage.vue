<script setup lang="ts">
import { useWorkspace, label } from '../workspace'
import TransportMap from '../components/TransportMap.vue'
const { overview, planRoute, candidateRoute, completedTask } = useWorkspace()

</script>

<template>
  <div class="portal-content">
    <TransportMap title="计划、GPS 与候选路线图层" :plan="planRoute" :candidate="candidateRoute" :task-id="completedTask?.code" />
    <section class="section" id="recommendation"><div class="section-title"><div><p class="kicker">RECOMMENDATION EVIDENCE</p><h2>推荐证据</h2></div><span class="muted">同一 OD · 40/30/30</span></div><div class="empty-panel"><strong>{{ overview?.recommendation?.label ?? '等待数据' }}</strong><p>{{ overview?.recommendation?.reason ?? '暂无推荐状态。' }}</p><small>有效样本 {{ overview?.recommendation?.validSampleCount ?? 0 }} 次 · 证据等级 {{ overview?.recommendation?.evidenceLevel ?? '—' }} · 计划 {{ overview?.recommendation?.planRouteId ?? '—' }} / 候选 {{ overview?.recommendation?.candidateRouteId ?? '—' }}</small></div></section>
    <section class="section"><div class="section-title"><div><p class="kicker">OPTIMIZATION EVIDENCE</p><h2>计划 · 实际 · 候选</h2></div><span class="muted">同一 OD 内比较</span></div><div class="compare-grid"><article class="compare-card"><span class="card-label plan">计划路线</span><h3>{{ planRoute?.citySequence.join(' → ') }}</h3><p>版本 {{ planRoute?.version }} · {{ label(planRoute?.verificationStatus ?? '') }}</p></article><article class="compare-card"><span class="card-label actual">实际 GPS（未道路匹配）</span><h3>以地图已落库 GPS 图层为准</h3><p>实测 {{ completedTask?.actualDistanceKm ?? '—' }} km · {{ completedTask?.actualFuelLiters ?? '—' }} L</p></article><article class="compare-card"><span class="card-label candidate">候选版本</span><h3>{{ candidateRoute?.citySequence.join(' → ') }}</h3><p>待核验 · 不宣称全局最优</p></article></div></section>
  </div>
</template>
