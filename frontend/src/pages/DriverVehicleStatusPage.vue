<script setup lang="ts">
import { computed } from 'vue'
import { useWorkspace } from '../workspace'
import { vehicleStatus } from '../business'

const { vehicles, businessLoaded, businessError, loadBusiness } = useWorkspace()

const reminders = computed(() => vehicles.value.map(vehicle => {
  const daysUntilService = daysUntil(vehicle.nextServiceOn)
  if (daysUntilService === null) {
    return { vehicle, daysUntilService, title: '暂无保养计划', tone: 'unset', detail: '请联系调度员或管理员确认下次保养时间。' }
  }
  if (daysUntilService < 0) {
    return { vehicle, daysUntilService, title: `已逾期 ${Math.abs(daysUntilService)} 天`, tone: 'overdue', detail: `下次保养日期：${vehicle.nextServiceOn}` }
  }
  if (daysUntilService === 0) {
    return { vehicle, daysUntilService, title: '今天需保养', tone: 'soon', detail: `下次保养日期：${vehicle.nextServiceOn}` }
  }
  if (daysUntilService <= 7) {
    return { vehicle, daysUntilService, title: `${daysUntilService} 天后需保养`, tone: 'soon', detail: `下次保养日期：${vehicle.nextServiceOn}` }
  }
  return { vehicle, daysUntilService, title: `${daysUntilService} 天后需保养`, tone: 'normal', detail: `下次保养日期：${vehicle.nextServiceOn}` }
}))

function daysUntil(value: string | null) {
  if (!value) return null
  const [year, month, day] = value.slice(0, 10).split('-').map(Number)
  if (!year || !month || !day) return null
  const due = Date.UTC(year, month - 1, day)
  const now = new Date()
  const today = Date.UTC(now.getFullYear(), now.getMonth(), now.getDate())
  return Math.round((due - today) / 86_400_000)
}
</script>

<template>
  <section>
    <div class="section-title">
      <div><p class="kicker">VEHICLE STATUS</p><h2>我的货车与保养提醒</h2></div>
      <button class="outline-button" @click="loadBusiness">刷新车辆状态</button>
    </div>
    <p v-if="businessError" class="alert error" role="alert">{{ businessError }}</p>
    <p class="source-note">仅显示管理员绑定给当前司机的货车；这里根据下次保养日期提醒距离保养还有多久，不需要司机填写或上传保养记录。</p>
    <div v-if="reminders.length" class="vehicle-reminder-list">
      <article v-for="item in reminders" :key="item.vehicle.id" class="vehicle-reminder-card">
        <div>
          <span class="tag">{{ item.vehicle.vehicleType }} · {{ item.vehicle.maxLoadTons ?? '—' }} t · {{ item.vehicle.energyType || '能源待补录' }}</span>
          <h3>{{ item.vehicle.plateNumber }}</h3>
          <p>{{ vehicleStatus[item.vehicle.status] || item.vehicle.status }} · 绑定司机：{{ item.vehicle.driverName || '—' }}</p>
        </div>
        <div class="maintenance-reminder" :class="item.tone">
          <strong>{{ item.title }}</strong>
          <small>{{ item.detail }}</small>
        </div>
      </article>
    </div>
    <div v-else class="empty-panel">
      <strong>{{ businessLoaded ? '暂无分配给你的货车' : '正在读取车辆信息' }}</strong>
      <p>{{ businessLoaded ? '调度员为任务分配车辆后，车辆状态和保养日期会显示在这里。' : '请稍后刷新。' }}</p>
    </div>
  </section>
</template>
