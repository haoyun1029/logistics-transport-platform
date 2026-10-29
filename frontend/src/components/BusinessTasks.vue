<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useWorkspace } from '../workspace'
import { businessApi, currency, taskStatus, time, elapsed } from '../business'
import type { BusinessTask } from '../business'
import TransportMap from './TransportMap.vue'
import DriverEarningsChart from './DriverEarningsChart.vue'
const props = defineProps<{ mode: 'dispatcher' | 'driver' | 'history' | 'admin'; view?: 'management' | 'scheduling' }>()
const { businessTasks, businessRoutes, drivers, vehicles, businessError, businessLoaded, loadBusiness } = useWorkspace()
const selected = ref<number | null>(null)
const filter = ref('')
const tasks = computed(() => businessTasks.value.filter(t => {
  if (props.mode === 'history') return ['COMPLETED', 'CANCELLED'].includes(t.status)
  if (props.mode === 'driver') return !['COMPLETED', 'CANCELLED'].includes(t.status)
  if (props.view === 'scheduling') return t.status === 'PENDING_ASSIGNMENT'
  return !filter.value || t.status === filter.value
}))
const current = computed(() => tasks.value.find(t => t.id === selected.value) ?? tasks.value[0] ?? null)
const route = computed(() => businessRoutes.value.find(r => r.databaseId === current.value?.routeId) ?? null)
function taskDetailsIncomplete(task: BusinessTask) {
  return !task.cargoDescription?.trim() || task.cargoWeightTons == null || task.pricePerKm == null
    || task.plannedDistanceKm == null || task.plannedDurationMinutes == null
    || !task.deliveryDeadline || !task.details?.trim()
}
const createRouteId = ref('')
const selectedCreateRoute = computed(() => businessRoutes.value.find(r => String(r.databaseId) === createRouteId.value))
const createDistance = computed(() => selectedCreateRoute.value?.segments.reduce((sum, segment) => sum + (segment.distanceKm || 0), 0).toFixed(2) ?? '')
const cargoDescription = ref('')
const cargoWeightTons = ref<number>()
const pricePerKm = ref<number>()
const deliveryDeadline = ref('')
const taskDetails = ref('')
const busy = ref(false)
const error = ref('')
const notice = ref('')
const assigning = ref<BusinessTask | null>(null)
const editingDetails = ref<BusinessTask | null>(null)
const completing = ref<BusinessTask | null>(null)
const issueFeedbackTask = ref<BusinessTask | null>(null)
const simulationRetryTaskId = ref<number | null>(null)
const driverId = ref('')
const vehicleId = ref('')
const selectedVehicle = computed(() => vehicles.value.find(vehicle => String(vehicle.id) === vehicleId.value) ?? null)
const distance = ref<number>()
const fuel = ref<number>()
const comment = ref('')
const issueType = ref('TIME_EXTENSION')
const issueMessage = ref('')
const requestedDeadline = ref('')
const detailsCargo = ref('')
const detailsWeight = ref<number>()
const detailsPrice = ref<number>()
const detailsDeadline = ref('')
const detailsText = ref('')
const historicalRate = ref<number>()
const historicalRateSource = ref('')
const dialog = ref<HTMLDialogElement>()
watch(vehicleId, () => {
  if (selectedVehicle.value?.driverId) driverId.value = String(selectedVehicle.value.driverId)
  else if (vehicleId.value) driverId.value = ''
})
watch([assigning, completing, issueFeedbackTask], async () => { if (assigning.value || completing.value || issueFeedbackTask.value) { await nextTick(); dialog.value?.showModal() } })
function close() { if (!busy.value) { assigning.value = null; editingDetails.value = null; completing.value = null; issueFeedbackTask.value = null } }
async function act(action: () => Promise<unknown>, success: string): Promise<boolean> {
  if (busy.value) return false
  busy.value = true; error.value = ''; notice.value = ''
  try { await action(); assigning.value = null; completing.value = null; issueFeedbackTask.value = null; notice.value = success; await loadBusiness(); return true }
  catch (failure) { error.value = failure instanceof Error ? failure.message : '操作失败。'; return false }
  finally { busy.value = false }
}
async function saveDetails() {
  if (!editingDetails.value) return
  const saved = await act(() => businessApi(`/tasks/${editingDetails.value!.id}/details`, {
    cargoDescription: detailsCargo.value, cargoWeightTons: detailsWeight.value, pricePerKm: detailsPrice.value,
    deliveryDeadline: detailsDeadline.value, details: detailsText.value,
  }), '任务资料已补齐，可以重新尝试分配。')
  if (saved) editingDetails.value = null
}
function create() {
  act(() => businessApi('/tasks', { routeId: Number(createRouteId.value), cargoDescription: cargoDescription.value,
    cargoWeightTons: cargoWeightTons.value, pricePerKm: pricePerKm.value, deliveryDeadline: deliveryDeadline.value, details: taskDetails.value }), '运输任务已保存，等待分配。')
}
function cancel(task: BusinessTask) { if (window.confirm(`确认取消 ${task.code}？`)) act(() => businessApi(`/tasks/${task.id}/cancel`, {}), '任务已取消。') }
async function accept(task: BusinessTask) {
  if (!window.confirm(`确认接受 ${task.code} 并开始运输？开始时间由系统记录。`)) return
  if (busy.value) return
  busy.value = true; error.value = ''; notice.value = ''; simulationRetryTaskId.value = null
  try {
    await businessApi(`/tasks/${task.id}/accept`, {})
    await loadBusiness()
    try {
      const simulation = await businessApi<{ reusedExistingRun: boolean }>(`/tasks/${task.id}/simulate`, {})
      notice.value = simulation.reusedExistingRun
        ? '已接单，检测到已有完整模拟轨迹，未重复发布。'
        : '已接单，系统已自动启动模拟 GPS 轨迹。'
    } catch (failure) {
      simulationRetryTaskId.value = task.id
      error.value = `已接单，但模拟 GPS 启动失败：${failure instanceof Error ? failure.message : '服务暂不可用'}。可点击“重试轨迹”。`
    }
    await loadBusiness()
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '接单失败。'
  } finally { busy.value = false }
}
async function retrySimulation(task: BusinessTask) {
  if (busy.value) return
  busy.value = true; error.value = ''; notice.value = ''
  try {
    const simulation = await businessApi<{ reusedExistingRun: boolean }>(`/tasks/${task.id}/simulate`, {})
    simulationRetryTaskId.value = null
    notice.value = simulation.reusedExistingRun
      ? '检测到已有完整轨迹，本次未重复发布；地图只回放最近一轮。'
      : '模拟 GPS 已重新启动，任务地图会持续更新。'
    await loadBusiness()
  } catch (failure) {
    simulationRetryTaskId.value = task.id
    error.value = `模拟 GPS 仍未启动：${failure instanceof Error ? failure.message : '服务暂不可用'}。`
  } finally { busy.value = false }
}
function feedback(task: BusinessTask) {
  issueFeedbackTask.value = task
  issueType.value = 'TIME_EXTENSION'
  issueMessage.value = ''
  requestedDeadline.value = ''
  error.value = ''
}
function assign() {
  if (assigning.value) {
    const boundDriverId = selectedVehicle.value?.driverId
    const effectiveDriverId = boundDriverId ?? Number(driverId.value)
    act(() => businessApi(`/tasks/${assigning.value!.id}/assign`, { driverId: effectiveDriverId, vehicleId: Number(vehicleId.value) }), '任务已分配，等待绑定司机接单。')
  }
}
function complete() {
  if (completing.value && window.confirm('确认已送达，并提交实际里程和油耗？')) act(() => businessApi(`/tasks/${completing.value!.id}/complete`, { actualDistanceKm: distance.value, actualFuelLiters: fuel.value, comment: comment.value }), '送达数据已保存，请在运输历史查看样本状态和排除原因。')
}
function submitIssueFeedback() {
  if (!issueFeedbackTask.value) return
  act(() => businessApi(`/tasks/${issueFeedbackTask.value!.id}/feedback`, {
    type: issueType.value,
    message: issueMessage.value,
    requestedDeadline: requestedDeadline.value || null,
  }), '问题反馈已提交，等待调度员处理。')
}
function beginAssign(task: BusinessTask) { assigning.value = task; driverId.value = ''; vehicleId.value = ''; error.value = '' }
function beginEditDetails(task: BusinessTask) {
  editingDetails.value = task
  detailsCargo.value = task.cargoDescription ?? ''
  detailsWeight.value = task.cargoWeightTons ?? undefined
  detailsPrice.value = task.pricePerKm ?? undefined
  detailsDeadline.value = task.deliveryDeadline ? task.deliveryDeadline.slice(0, 16) : ''
  detailsText.value = task.details ?? ''
  error.value = ''
}
function beginHistoricalRate() {
  historicalRate.value = undefined
  historicalRateSource.value = ''
  error.value = ''
}
async function saveHistoricalRate(task: BusinessTask) {
  const saved = await act(() => businessApi(`/tasks/${task.id}/historical-rate`, {
    pricePerKm: historicalRate.value, sourceReference: historicalRateSource.value,
  }), '历史单价已按来源凭据补录，收入统计已更新。')
  if (saved) beginHistoricalRate()
}
function beginComplete(task: BusinessTask) { completing.value = task; distance.value = undefined; fuel.value = undefined; comment.value = ''; error.value = '' }
</script>

<template>
  <section class="business-tasks">
    <div class="section-title"><div><p class="kicker">{{ view === 'scheduling' ? 'TASK SCHEDULING' : 'PERSISTENT TRANSPORT' }}</p><h2>{{ mode === 'history' ? '运输历史与反馈' : mode === 'driver' ? '我的运输任务' : view === 'scheduling' ? '任务调度' : '运输任务管理' }}</h2></div><button class="outline-button" :disabled="busy" @click="loadBusiness">刷新业务数据</button></div>
    <p class="source-note">{{ view === 'scheduling' ? '选择待分配任务，为任务安排司机和车辆。' : mode === 'driver' ? '确认接单后系统自动启动模拟 GPS；确认送达时填写实际里程、油耗和文字反馈。' : '来源：业务记录。司机只能查看和操作本人任务；已完成记录与反馈会保留。' }}</p>
    <p v-if="businessError" class="alert error" role="alert">{{ businessError }}{{ businessLoaded ? ' 当前保留上次快照。' : '' }}</p>
    <p v-if="error && !assigning && !completing" class="alert error" role="alert">{{ error }}</p><p v-if="notice" class="alert success" role="status">{{ notice }}</p>
    <DriverEarningsChart v-if="mode === 'history'" :tasks="tasks" />
    <form v-if="mode === 'dispatcher' && view !== 'scheduling'" class="business-form task-create-form" @submit.prevent="create">
      <div class="form-grid"><label>任务路线<select v-model="createRouteId" required :disabled="busy"><option value="" disabled>选择起终点对应的基础路线</option><option v-for="r in businessRoutes" :key="r.databaseId" :value="String(r.databaseId)">{{ r.originCity }} → {{ r.destinationCity }} · {{ r.name }} v{{ r.version }}</option></select></label><label>运送货物<input v-model="cargoDescription" required maxlength="120" placeholder="例如：冷链水果、电子配件"></label><label>货物重量（吨）<input v-model.number="cargoWeightTons" required type="number" min="0.01" max="1000" step="0.01" placeholder="例如：8.5"></label><label>每公里单价（元/km）<input v-model.number="pricePerKm" required type="number" min="0.01" max="10000" step="0.01" placeholder="例如：6.50"></label><label>预计距离（km）<input :value="createDistance" readonly placeholder="选择路线后自动计算"></label><label>计划送达时间<input v-model="deliveryDeadline" required type="datetime-local"></label></div>
      <label>任务详情<textarea v-model="taskDetails" required maxlength="1000" rows="3" placeholder="填写装卸要求、收货联系人、温控要求等司机需要知道的信息"></textarea></label>
      <div class="task-create-actions"><button class="primary-button" :disabled="busy || !createRouteId" type="submit">创建待分配任务</button><small>创建后，司机端会先看到任务简介，点开任务可查看货物、重量、时限、距离和详情。</small></div>
    </form>
    <label v-if="(mode === 'dispatcher' && view !== 'scheduling') || mode === 'admin'" class="business-filter">任务状态<select v-model="filter"><option value="">全部状态</option><option v-for="(text,key) in taskStatus" :key="key" :value="key">{{ text }}</option></select></label>
    <div class="business-task-layout">
      <div class="business-task-list"><button v-for="task in tasks" :key="task.id" class="business-task-choice" :class="{ selected: current?.id === task.id }" :aria-pressed="current?.id === task.id" @click="selected = task.id"><span class="tag">{{ task.code }}</span><strong>{{ task.cargoDescription || (task.demoMode ? '演示货物' : '历史任务待补录') }}</strong><span>{{ task.originCity }} → {{ task.destinationCity }} · {{ taskStatus[task.status] }}</span><small>{{ task.cargoWeightTons != null ? `${task.cargoWeightTons} 吨` : '重量待补录' }} · {{ task.plannedDistanceKm != null ? `${task.plannedDistanceKm} km` : '距离待补录' }} · {{ task.pricePerKm != null && task.pricePerKm > 0 ? `${task.pricePerKm} 元/km` : '单价待补录' }} · {{ task.deliveryDeadline ? time(task.deliveryDeadline) : '时限待补录' }}</small></button><div v-if="!tasks.length" class="empty-panel"><strong>{{ businessLoaded ? (view === 'scheduling' ? '暂无待调度任务' : '暂无符合条件的任务') : '等待业务数据' }}</strong><p>{{ view === 'scheduling' ? '新建任务后会出现在这里。' : '调度员创建并分配后，司机账号才会看到自己的任务。' }}</p></div></div>
      <article v-if="current" class="business-task-detail">
        <div class="detail-top"><div><span class="tag">{{ current.code }}</span><h3>{{ current.originCity }} → {{ current.destinationCity }}</h3></div><span class="detail-status verified">{{ taskStatus[current.status] }}</span></div>
        <p v-if="current.demoMode" class="alert info">演示任务：路线与位置仅用于流程展示。</p>
        <dl class="business-details"><div><dt>运送货物</dt><dd>{{ current.cargoDescription || (current.demoMode ? '演示货物' : '历史任务待补录') }}</dd></div><div><dt>货物重量</dt><dd>{{ current.cargoWeightTons != null ? `${current.cargoWeightTons} 吨` : '待补录' }}</dd></div><div><dt>每公里单价</dt><dd>{{ current.pricePerKm != null && current.pricePerKm > 0 ? `${current.pricePerKm.toFixed(2)} 元/km` : '待补录' }}</dd></div><div><dt>预计距离</dt><dd>{{ current.plannedDistanceKm != null ? `${current.plannedDistanceKm} km` : '待补录' }}</dd></div><div><dt>预计耗时</dt><dd>{{ current.plannedDurationMinutes != null ? `${current.plannedDurationMinutes} 分钟` : '待补录' }}</dd></div><div><dt>计划送达</dt><dd>{{ current.deliveryDeadline ? time(current.deliveryDeadline) : '待补录' }}</dd></div><div><dt>任务详情</dt><dd class="task-detail-text">{{ current.details || (current.demoMode ? '固定演示任务资料。' : '历史任务暂无详情，待补录。') }}</dd></div><div><dt>基础路线</dt><dd>{{ current.routeName }}</dd></div><div><dt>司机 / 车辆</dt><dd>{{ current.driverName || '未分配' }} / {{ current.plateNumber || '未分配' }}</dd></div><div><dt>创建时间</dt><dd>{{ time(current.createdAt) }}</dd></div><div><dt>接单时间</dt><dd>{{ time(current.startedAt) }}</dd></div><div><dt>完成时间</dt><dd>{{ time(current.completedAt) }}</dd></div><div><dt>位置来源</dt><dd>{{ current.sourceType === 'SIMULATED' ? '演示位置' : current.sourceType }}</dd></div></dl>
        <form v-if="mode === 'dispatcher' && current.status === 'PENDING_ASSIGNMENT' && editingDetails?.id === current.id" class="business-panel business-form form-grid" @submit.prevent="saveDetails">
          <h4>补充待分配任务资料</h4>
          <label>运送货物<input v-model="detailsCargo" required maxlength="120"></label>
          <label>货物重量（吨）<input v-model.number="detailsWeight" required type="number" min="0.01" step="0.01"></label>
          <label>每公里单价（元/km）<input v-model.number="detailsPrice" required type="number" min="0.01" step="0.01"></label>
          <label>计划送达时间<input v-model="detailsDeadline" required type="datetime-local"></label>
          <label class="form-wide">任务详情<textarea v-model="detailsText" required maxlength="1000" rows="3"></textarea></label>
          <div class="review-actions"><button class="primary-button" :disabled="busy" type="submit">保存任务资料</button><button class="outline-button" :disabled="busy" type="button" @click="editingDetails = null">取消</button></div>
        </form>
        <div v-if="current.status === 'COMPLETED'" class="business-feedback"><h4>司机填写的实际数据</h4><p>{{ current.actualDistanceKm ?? '—' }} km · {{ current.actualFuelLiters ?? '—' }} L · {{ elapsed(current) }} min</p><p>本次运输收入估算：<strong>{{ current.actualDistanceKm != null && current.pricePerKm != null && current.pricePerKm > 0 ? currency(current.actualDistanceKm * current.pricePerKm) : '待补录单价或实际里程' }}</strong></p><p>{{ current.comment || '未填写文字反馈' }}</p><small>{{ current.exclusionReason ? '该记录暂未用于路线比较。' : '运输反馈已保存。' }}</small>
          <form v-if="['admin','dispatcher'].includes(mode) && current.pricePerKm == null" class="business-form form-grid historical-rate-form" @submit.prevent="saveHistoricalRate(current)">
            <h4>补录历史任务单价</h4><p class="form-wide">只有根据原始运单、合同或结算凭据核实后才补录；该记录不会覆盖已有单价。</p>
            <label>每公里单价（元/km）<input v-model.number="historicalRate" required type="number" min="0.01" max="10000" step="0.01" :disabled="busy"></label>
            <label class="form-wide">单价来源凭据<input v-model="historicalRateSource" required maxlength="512" placeholder="例如：运单编号、结算单号或档案位置" :disabled="busy"></label>
            <div class="review-actions"><button class="primary-button" type="submit" :disabled="busy">{{ busy ? '正在保存…' : '保存有凭据的单价' }}</button></div>
          </form>
        </div>
        <div class="driver-actions">
          <button v-if="mode === 'driver' && simulationRetryTaskId === current.id" class="outline-button" :disabled="busy" @click="retrySimulation(current)">重试轨迹</button>
          <button v-if="mode === 'dispatcher' && current.status === 'PENDING_ASSIGNMENT'" class="primary-button" :disabled="busy || taskDetailsIncomplete(current) || (route?.verificationStatus !== 'VERIFIED' && !current.demoMode)" :title="taskDetailsIncomplete(current) ? '请先补充完整的任务资料' : route?.verificationStatus !== 'VERIFIED' && !current.demoMode ? '路线尚未完成逐段货车核验，暂不能分配' : '安排司机和车辆'" @click="beginAssign(current)">{{ taskDetailsIncomplete(current) ? '任务资料待补充' : route?.verificationStatus !== 'VERIFIED' && !current.demoMode ? '路线待核验' : '安排司机和车辆' }}</button>
          <button v-if="mode === 'dispatcher' && current.status === 'PENDING_ASSIGNMENT'" class="outline-button" :disabled="busy" @click="beginEditDetails(current)">补充任务资料</button>
          <button v-if="mode === 'dispatcher' && ['PENDING_ASSIGNMENT','PENDING_ACCEPTANCE'].includes(current.status)" class="outline-button" :disabled="busy" @click="cancel(current)">取消任务</button>
          <button v-if="mode === 'driver' && current.status === 'PENDING_ACCEPTANCE'" class="primary-button" :disabled="busy" @click="accept(current)">确认接单</button>
          <button v-if="mode === 'driver' && current.status === 'IN_TRANSIT'" class="primary-button" :disabled="busy" @click="beginComplete(current)">确认送达</button>
          <button v-if="mode === 'driver' && ['PENDING_ACCEPTANCE', 'IN_TRANSIT'].includes(current.status)" class="outline-button" :disabled="busy" @click="feedback(current)">反馈</button>
        </div>
        <p v-if="current.status === 'PENDING_ASSIGNMENT' && route?.verificationStatus !== 'VERIFIED' && !current.demoMode" class="source-note">当前路线资料未完成，暂不能安排执行。</p>
        <p v-if="current.status === 'PENDING_ASSIGNMENT' && current.demoMode && route?.verificationStatus !== 'VERIFIED'" class="source-note">该任务为演示流程，路线资料只用于本次展示。</p>
        <p v-if="current.status === 'PENDING_ASSIGNMENT' && taskDetailsIncomplete(current)" class="source-note">缺少运单资料时不可分配。点击“补充任务资料”录入原始业务数据；路线距离和预计耗时会从该任务路线重新计算。</p>
      </article>
    </div>
    <div v-if="current && route" class="section"><TransportMap title="运输任务路线地图" :plan="route" :task-id="current.code" :trajectory-url="`/api/business/tasks/${current.id}/trajectory`" :presentation="true" /></div>
    <dialog v-if="assigning || completing || issueFeedbackTask" ref="dialog" class="review-dialog" aria-labelledby="task-dialog-title" @cancel.prevent="close">
      <h2 id="task-dialog-title">{{ assigning ? '确认任务分配' : completing ? '确认送达' : '运输问题反馈' }}</h2><p>{{ (assigning || completing || issueFeedbackTask)?.code }}</p>
      <form class="business-form" @submit.prevent="assigning ? assign() : completing ? complete() : submitIssueFeedback()">
        <template v-if="assigning"><label>车辆<select v-model="vehicleId" required :disabled="busy"><option value="" disabled>选择空闲车辆</option><option v-for="v in vehicles.filter(v => v.status === 'AVAILABLE')" :key="v.id" :value="String(v.id)">{{ v.plateNumber }} · {{ v.vehicleType }} · 绑定司机：{{ v.driverName || '历史车辆未绑定' }}</option></select></label><label>绑定司机<select v-model="driverId" required :disabled="busy || !!selectedVehicle?.driverId"><option value="" disabled>选择审核通过且空闲的司机</option><option v-for="d in drivers.filter(d => !d.busy)" :key="d.id" :value="String(d.id)">{{ d.displayName }}（{{ d.username }}）</option></select></label><small v-if="selectedVehicle?.driverId">该车辆已绑定司机“{{ selectedVehicle.driverName }}”，任务只能分配给该司机。</small><small v-else>该车辆是历史未绑定车辆；建议先在管理员车辆管理中补绑定司机。</small></template>
        <template v-else-if="completing"><label>实际里程（km）<input v-model.number="distance" type="number" required min="0.01" max="10000000" step="0.01" :disabled="busy"></label><label>实际油耗（L）<input v-model.number="fuel" type="number" required min="0.01" max="10000000" step="0.01" :disabled="busy"></label><label>送达备注（选填）<textarea v-model="comment" maxlength="300" rows="3" :disabled="busy"></textarea></label><small>实际耗时由系统接单/完成时间计算。</small></template>
        <template v-else><label>反馈类型<select v-model="issueType" required :disabled="busy"><option value="TIME_EXTENSION">计划时间太短，申请延长</option><option value="ACCIDENT">运输中遭遇事故</option><option value="EXTREME_WEATHER">遭遇极端天气</option><option value="OTHER">其他运输问题</option></select></label><label v-if="issueType === 'TIME_EXTENSION'">申请送达时间（选填）<input v-model="requestedDeadline" type="datetime-local" :disabled="busy"></label><label>问题说明<textarea v-model="issueMessage" required maxlength="500" rows="4" placeholder="请说明具体情况和需要调度员协助的内容" :disabled="busy"></textarea></label></template>
        <p v-if="error" class="alert error" role="alert">{{ error }}</p><div class="review-actions"><button class="outline-button" type="button" :disabled="busy" @click="close">返回</button><button class="primary-button" type="submit" :disabled="busy">{{ busy ? '正在提交…' : assigning ? '确认分配' : completing ? '确认送达并保存' : '提交问题反馈' }}</button></div>
      </form>
    </dialog>
  </section>
</template>
