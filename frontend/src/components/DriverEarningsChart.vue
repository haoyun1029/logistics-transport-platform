<script setup lang="ts">
import { computed } from 'vue'
import { currency } from '../business'
import type { BusinessTask } from '../business'

const props = defineProps<{ tasks: BusinessTask[] }>()

const eligibleTasks = computed(() => props.tasks.filter(task =>
  task.status === 'COMPLETED'
  && task.pricePerKm !== null && Number.isFinite(task.pricePerKm) && task.pricePerKm > 0
  && task.actualDistanceKm !== null && Number.isFinite(task.actualDistanceKm) && task.actualDistanceKm > 0,
))
const totalRevenue = computed(() => eligibleTasks.value.reduce((sum, task) => sum + task.pricePerKm! * task.actualDistanceKm!, 0))
const totalDistance = computed(() => eligibleTasks.value.reduce((sum, task) => sum + task.actualDistanceKm!, 0))
const missingRateCount = computed(() => props.tasks.filter(task => task.status === 'COMPLETED' && !(task.pricePerKm !== null && Number.isFinite(task.pricePerKm) && task.pricePerKm > 0)).length)
const monthBars = computed(() => {
  const totals = new Map<string, number>()
  for (const task of eligibleTasks.value) {
    const date = task.completedAt ? new Date(task.completedAt) : null
    const key = date && Number.isFinite(date.getTime()) ? `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}` : 'unknown'
    totals.set(key, (totals.get(key) ?? 0) + task.pricePerKm! * task.actualDistanceKm!)
  }
  return [...totals.entries()]
    .sort(([left], [right]) => left === 'unknown' ? -1 : right === 'unknown' ? 1 : left.localeCompare(right))
    .slice(-12)
    .map(([key, revenue]) => ({
      key,
      label: key === 'unknown' ? '日期未知' : `${Number(key.slice(5))}月`,
      revenue,
    }))
})
const maximum = computed(() => Math.max(0, ...monthBars.value.map(item => item.revenue)))
const completedCount = computed(() => props.tasks.filter(task => task.status === 'COMPLETED').length)
function barHeight(value: number) { return maximum.value > 0 ? `${Math.max(4, value / maximum.value * 100)}%` : '0%' }
</script>

<template>
  <section class="earnings-panel section" aria-label="司机运输收入估算">
    <div class="section-title">
      <div><p class="kicker">DRIVER EARNINGS</p><h2>运输收入可视化</h2></div>
      <span class="muted">累计 {{ completedCount }} 笔已完成任务</span>
    </div>
    <div class="earnings-summary">
      <article class="metric-card"><span>累计运输收入估算</span><strong>{{ currency(totalRevenue) }}</strong><small>仅统计有单价和实际里程的任务</small></article>
      <article class="metric-card"><span>计收入程</span><strong>{{ totalDistance.toLocaleString('zh-CN', { maximumFractionDigits: 2 }) }} km</strong><small>{{ eligibleTasks.length }} 笔记录</small></article>
      <article class="metric-card"><span>待完善费率</span><strong>{{ missingRateCount }}</strong><small>历史任务未记录每公里单价</small></article>
    </div>

    <div v-if="monthBars.length" class="earnings-chart-wrap">
      <div class="earnings-axis" aria-hidden="true"><span>{{ currency(maximum) }}</span><i></i><span>¥0</span></div>
      <div class="earnings-chart" role="img" :aria-label="`最近月份运输收入柱状图，共 ${monthBars.length} 个时间段`">
        <div v-for="month in monthBars" :key="month.key" class="earnings-column">
          <strong>{{ currency(month.revenue) }}</strong>
          <div class="earnings-bar-track"><div class="earnings-bar" :style="{ height: barHeight(month.revenue) }"></div></div>
          <small>{{ month.label }}</small>
        </div>
      </div>
    </div>
    <div v-else class="empty-panel"><strong>还没有可统计的运输收入</strong><p>完成任务并填写实际里程后，系统会按任务单价计算估算收入。</p></div>
    <p class="source-note">计算口径：任务每公里单价 × 司机填写的实际里程。显示的是运输收入估算，未扣除油费、工资及其他成本；历史任务没有单价时不计入金额。</p>
  </section>
</template>
