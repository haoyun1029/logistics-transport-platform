<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { useWorkspace } from '../workspace'
import { businessApi, vehicleStatus } from '../business'
import type { Vehicle } from '../business'

defineProps<{ manage?: boolean }>()
const { vehicles, drivers, businessError, businessLoaded, loadBusiness } = useWorkspace()
const plate = ref('')
const type = ref('')
const energy = ref('柴油')
const totalWeight = ref<number>()
const load = ref<number>()
const lengthMeters = ref<number>()
const widthMeters = ref<number>()
const heightMeters = ref<number>()
const axleCount = ref<number>()
const hasEtc = ref(false)
const fuel = ref<number>()
const driverId = ref('')
const error = ref('')
const notice = ref('')
const busy = ref(false)
const editingId = ref<number | null>(null)
const vehicleForm = ref<HTMLElement | null>(null)
const editingMaintenanceId = ref<number | null>(null)
const maintenanceDescription = ref('')
const servicedOn = ref(today())
const DEFAULT_MAINTENANCE_INTERVAL_DAYS = 90
const maintenanceIntervalDays = ref(DEFAULT_MAINTENANCE_INTERVAL_DAYS)
const maintenanceNextServiceOn = ref('')
const maintenanceNextServiceTouched = ref(false)

function today() {
  const value = new Date()
  return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}`
}
function addDays(date: string, days: number) {
  if (!date || !Number.isFinite(days) || days < 1) return ''
  const result = new Date(`${date}T00:00:00`)
  result.setDate(result.getDate() + Math.floor(days))
  return `${result.getFullYear()}-${String(result.getMonth() + 1).padStart(2, '0')}-${String(result.getDate()).padStart(2, '0')}`
}
const suggestedNextServiceOn = computed(() => addDays(servicedOn.value, maintenanceIntervalDays.value))
function refreshSuggestedDate() {
  if (!maintenanceNextServiceTouched.value) maintenanceNextServiceOn.value = suggestedNextServiceOn.value
}
function maintenanceDueText(date: string | null) {
  if (!date) return '未设置'
  const days = Math.ceil((Date.parse(`${date}T00:00:00`) - Date.parse(`${today()}T00:00:00`)) / 86400000)
  if (days < 0) return `已逾期 ${Math.abs(days)} 天`
  if (days === 0) return '今天需保养'
  return `${days} 天后需保养`
}

function vehicleBody() {
  return {
    plateNumber: plate.value,
    vehicleType: type.value,
    energyType: energy.value,
    totalWeightTons: totalWeight.value,
    maxLoadTons: load.value,
    lengthMeters: lengthMeters.value,
    widthMeters: widthMeters.value,
    heightMeters: heightMeters.value,
    axleCount: axleCount.value,
    hasEtc: hasEtc.value,
    fuelModelLitersPer100km: fuel.value,
    driverId: Number(driverId.value),
  }
}
function resetForm() {
  editingId.value = null
  plate.value = ''
  type.value = ''
  energy.value = '柴油'
  totalWeight.value = undefined
  load.value = undefined
  lengthMeters.value = undefined
  widthMeters.value = undefined
  heightMeters.value = undefined
  axleCount.value = undefined
  hasEtc.value = false
  fuel.value = undefined
  driverId.value = ''
}
function editVehicle(vehicle: Vehicle) {
  editingId.value = vehicle.id
  editingMaintenanceId.value = null
  plate.value = vehicle.plateNumber
  type.value = vehicle.vehicleType
  energy.value = vehicle.energyType || '柴油'
  totalWeight.value = vehicle.totalWeightTons ?? undefined
  load.value = vehicle.maxLoadTons ?? undefined
  lengthMeters.value = vehicle.lengthMeters ?? undefined
  widthMeters.value = vehicle.widthMeters ?? undefined
  heightMeters.value = vehicle.heightMeters ?? undefined
  axleCount.value = vehicle.axleCount ?? undefined
  hasEtc.value = vehicle.hasEtc ?? false
  fuel.value = vehicle.fuelModelLitersPer100km ?? undefined
  driverId.value = vehicle.driverId ? String(vehicle.driverId) : ''
  error.value = ''
  void nextTick(() => vehicleForm.value?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
}
async function save() {
  busy.value = true
  error.value = ''
  try {
    await businessApi(editingId.value ? `/vehicles/${editingId.value}/profile` : '/vehicles', vehicleBody())
    notice.value = editingId.value ? '车辆档案和绑定司机已更新。' : '车辆已保存。'
    resetForm()
    await loadBusiness()
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '保存失败。'
  } finally {
    busy.value = false
  }
}

async function status(id: number, value: string) {
  if (!window.confirm(`确认将车辆设为${vehicleStatus[value]}？`)) return
  busy.value = true
  error.value = ''
  try {
    await businessApi(`/vehicles/${id}/status`, { status: value })
    await loadBusiness()
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '状态变更失败。'
  } finally {
    busy.value = false
  }
}

function editMaintenance(vehicle: Vehicle) {
  editingMaintenanceId.value = vehicle.id
  maintenanceDescription.value = ''
  servicedOn.value = today()
  maintenanceIntervalDays.value = DEFAULT_MAINTENANCE_INTERVAL_DAYS
  maintenanceNextServiceTouched.value = false
  maintenanceNextServiceOn.value = suggestedNextServiceOn.value
  error.value = ''
}
function cancelMaintenance() {
  editingMaintenanceId.value = null
  maintenanceDescription.value = ''
  maintenanceNextServiceTouched.value = false
}
async function saveMaintenance(vehicle: Vehicle) {
  busy.value = true
  error.value = ''
  try {
    await businessApi('/maintenance', {
      vehicleId: vehicle.id,
      description: maintenanceDescription.value,
      servicedOn: servicedOn.value,
      nextServiceOn: maintenanceNextServiceOn.value || null,
      intervalDays: maintenanceIntervalDays.value,
    })
    notice.value = `已登记 ${vehicle.plateNumber} 的保养记录，下次保养：${maintenanceNextServiceOn.value}`
    cancelMaintenance()
    await loadBusiness()
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '保养记录保存失败。'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section>
    <div class="section-title">
      <div><p class="kicker">BUSINESS FLEET</p><h2>车辆状态</h2></div>
      <button class="outline-button" @click="loadBusiness">刷新车辆</button>
    </div>
    <p v-if="error || businessError" class="alert error" role="alert">{{ error || businessError }}</p>
    <p v-if="notice" class="alert success" role="status">{{ notice }}</p>
    <form v-if="manage" ref="vehicleForm" class="business-panel business-form form-grid" @submit.prevent="save">
      <h3>{{ editingId ? '编辑车辆档案' : '新增车辆档案' }}</h3>
      <label>车牌号<input v-model="plate" required maxlength="24" placeholder="例如：京A12345"></label>
      <label>绑定司机<select v-model="driverId" required><option value="" disabled>选择审核通过的司机</option><option v-for="driver in drivers" :key="driver.id" :value="String(driver.id)">{{ driver.displayName }}（{{ driver.username }}）{{ driver.busy ? ' · 当前有任务' : '' }}</option></select></label>
      <label>车型<input v-model="type" required maxlength="48" placeholder="例如：重型厢式货车"></label>
      <label>能源类型<select v-model="energy" required><option>柴油</option><option>汽油</option><option>纯电动</option><option>油电混动</option><option>插混/增程</option></select></label>
      <label>总质量（t）<input v-model.number="totalWeight" type="number" min="0.01" max="1000" step="0.01" required></label>
      <label>核定载重（t）<input v-model.number="load" type="number" min="0.01" max="1000" step="0.01" required></label>
      <label>车长（m）<input v-model.number="lengthMeters" type="number" min="0.01" max="100" step="0.01" required></label>
      <label>车宽（m）<input v-model.number="widthMeters" type="number" min="0.01" max="20" step="0.01" required></label>
      <label>车高（m）<input v-model.number="heightMeters" type="number" min="0.01" max="20" step="0.01" required></label>
      <label>轴数<input v-model.number="axleCount" type="number" min="1" max="20" step="1" required></label>
      <label>百公里油耗估算（L）<input v-model.number="fuel" type="number" min="0.01" max="200" step="0.01" required></label>
      <label class="checkbox-label"><input v-model="hasEtc" type="checkbox"> 有 ETC</label>
      <div class="review-actions"><button class="primary-button" :disabled="busy" type="submit">{{ editingId ? '保存车辆档案' : '新增车辆' }}</button><button v-if="editingId" class="outline-button" :disabled="busy" type="button" @click="resetForm">取消编辑</button></div>
    </form>

    <div v-for="(text, key) in vehicleStatus" :key="key" class="section">
      <div class="section-title">
        <h3>{{ text }}</h3>
        <span class="muted">{{ vehicles.filter(vehicle => vehicle.status === key).length }} 辆</span>
      </div>
      <div class="fleet-grid">
        <article v-for="vehicle in vehicles.filter(item => item.status === key)" :key="vehicle.id" class="business-panel">
          <strong>{{ vehicle.plateNumber }}</strong>
          <p>{{ vehicle.vehicleType }} · {{ vehicle.energyType || '能源待补录' }} · 核定载重 {{ vehicle.maxLoadTons ?? '—' }} t</p>
          <p>绑定司机：{{ vehicle.driverName || '未绑定（历史车辆）' }}</p>
          <small>总质量：{{ vehicle.totalWeightTons ?? '—' }} t · 尺寸：{{ vehicle.lengthMeters ?? '—' }}×{{ vehicle.widthMeters ?? '—' }}×{{ vehicle.heightMeters ?? '—' }} m · {{ vehicle.axleCount ?? '—' }} 轴 · {{ vehicle.hasEtc ? '有 ETC' : '无 ETC' }} · 油耗 {{ vehicle.fuelModelLitersPer100km ?? '—' }} L/100km</small>
          <small>下次保养：{{ vehicle.nextServiceOn || '未设置' }} · {{ maintenanceDueText(vehicle.nextServiceOn) }}</small>
          <div v-if="manage" class="review-actions">
            <button class="outline-button" :disabled="busy" type="button" @click="editVehicle(vehicle)">编辑车辆档案</button>
            <button class="outline-button" :disabled="busy" type="button" @click="editMaintenance(vehicle)">登记本次保养</button>
            <button v-if="['AVAILABLE', 'MAINTENANCE'].includes(vehicle.status)" class="outline-button" :disabled="busy" type="button" @click="status(vehicle.id, vehicle.status === 'AVAILABLE' ? 'MAINTENANCE' : 'AVAILABLE')">
              {{ vehicle.status === 'AVAILABLE' ? '转为维修中' : '保养完成，恢复空闲' }}
            </button>
          </div>
          <form v-if="manage && editingMaintenanceId === vehicle.id" class="business-form reminder-editor" @submit.prevent="saveMaintenance(vehicle)">
            <h4>登记本次保养</h4>
            <label>本次保养日期<input v-model="servicedOn" type="date" required @change="refreshSuggestedDate"></label>
            <label>默认保养周期（天）<input v-model.number="maintenanceIntervalDays" type="number" min="1" max="3650" required @input="refreshSuggestedDate"></label>
            <small>系统建议下次保养：{{ suggestedNextServiceOn }}（默认周期 {{ DEFAULT_MAINTENANCE_INTERVAL_DAYS }} 天）</small>
            <label>确认下次保养日期<input v-model="maintenanceNextServiceOn" type="date" required @input="maintenanceNextServiceTouched = true"></label>
            <label>保养内容<textarea v-model="maintenanceDescription" maxlength="900" required placeholder="例如：更换机油、机滤，检查制动系统"></textarea></label>
            <small>保存后保养记录进入历史，车辆恢复为空闲状态；管理员可以手动调整提醒日期。</small>
            <div class="review-actions">
              <button class="primary-button" :disabled="busy" type="submit">保存并完成保养</button>
              <button class="outline-button" :disabled="busy" type="button" @click="cancelMaintenance">取消</button>
            </div>
          </form>
        </article>
      </div>
      <p v-if="businessLoaded && !vehicles.some(vehicle => vehicle.status === key)" class="source-note">暂无{{ text }}车辆。</p>
    </div>
  </section>
</template>
