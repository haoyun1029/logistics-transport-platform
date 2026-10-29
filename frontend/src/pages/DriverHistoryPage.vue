<script setup lang="ts">
import { useWorkspace, label } from '../workspace'
const { completedTask } = useWorkspace()

</script>

<template>
  <div class="portal-content">
    <section class="section" id="history"><div class="section-title"><div><p class="kicker">DRIVER FEEDBACK</p><h2>我的历史反馈</h2></div><span class="muted">实测数据单独展示</span></div><div class="feedback-card"><div><strong>{{ completedTask?.code }}</strong><small>模拟轨迹 · {{ label(completedTask?.sampleStatus ?? '') }}</small></div><div><span>实际耗时</span><strong>{{ completedTask?.actualElapsedMinutes ?? '—' }} min</strong></div><div><span>实际里程</span><strong>{{ completedTask?.actualDistanceKm ?? '—' }} km</strong></div><div><span>实际油耗</span><strong>{{ completedTask?.actualFuelLiters ?? '—' }} L</strong></div></div></section>
  </div>
</template>
