<script setup lang="ts">
import { computed, ref } from 'vue'
import { useWorkspace, label } from '../workspace'
import { businessApi } from '../business'
import type { BusinessSegment } from '../business'
import TransportMap from '../components/TransportMap.vue'
import TruckRouteQuery from '../components/TruckRouteQuery.vue'
import type { SegmentPrefill } from '../truck-routes'
const { businessRoutes, businessError, businessLoaded, loadBusiness } = useWorkspace()
const selectedId = ref('')
const selected = computed(() => businessRoutes.value.find(r => String(r.databaseId) === selectedId.value) ?? businessRoutes.value[0] ?? null)
const busy = ref(false), error = ref(''), notice = ref('')
const name = ref(''), origin = ref(''), destination = ref(''), source = ref('')
const sequence = ref(1), from = ref(''), to = ref(''), road = ref(''), roadType = ref('高速')
const distance = ref<number>(), minutes = ref<number>(), mapSource = ref(''), request = ref('{}'), reference = ref(''), geometry = ref('')
const evidenceSegment = ref<BusinessSegment | null>(null), evidenceSource = ref(''), evidenceReference = ref(''), evidenceNote = ref(''), approve = ref('true')
async function act(action: () => Promise<unknown>, message: string) {
  if (busy.value) return
  busy.value = true; error.value = ''; notice.value = ''
  try { await action(); notice.value = message; evidenceSegment.value = null; await loadBusiness() }
  catch (e) { error.value = e instanceof Error ? e.message : '操作失败。' }
  finally { busy.value = false }
}
function create() { act(async () => { const result = await businessApi<{id:number}>('/routes', { name: name.value, originCity: origin.value, destinationCity: destination.value, source: source.value }); selectedId.value = String(result.id) }, '路线草稿已保存。') }
function saveSegment() { if (selected.value) act(() => businessApi(`/routes/${selected.value!.databaseId}/segments`, { sequence: sequence.value, fromCity: from.value, toCity: to.value, roadName: road.value, roadType: roadType.value, distanceKm: distance.value, estimatedMinutes: minutes.value, mapSource: mapSource.value, mapRequestJson: request.value, mapResponseReference: reference.value, geometryJson: geometry.value || null }), '道路段已保存；修改内容后该段重新进入待核验。') }
function edit(segment: BusinessSegment) { sequence.value = segment.sequence; from.value = segment.fromCity; to.value = segment.toCity; road.value = segment.roadName; roadType.value = segment.roadType; distance.value = segment.distanceKm; minutes.value = segment.estimatedMinutes; mapSource.value = segment.mapSource; request.value = segment.mapRequestJson; reference.value = segment.mapResponseReference; geometry.value = segment.geometryJson ?? '' }
function prefill(value: SegmentPrefill) { from.value = value.fromCity; to.value = value.toCity; road.value = value.roadName; roadType.value = '待核对道路类型'; distance.value = value.distanceKm; minutes.value = value.estimatedMinutes; mapSource.value = value.mapSource; request.value = value.mapRequestJson; reference.value = value.mapResponseReference; geometry.value = value.geometryJson; notice.value = '地图资料已填入道路段表单，尚未保存或通过核验。' }
function evidence(segment: BusinessSegment) { evidenceSegment.value = segment; evidenceSource.value = segment.truckEvidenceSource ?? ''; evidenceReference.value = segment.truckEvidenceReference ?? ''; evidenceNote.value = ''; approve.value = 'true' }
function verify() { if (selected.value && evidenceSegment.value && window.confirm('确认已核对所列货车通行依据，提交本路段核验结论？')) act(() => businessApi(`/routes/${selected.value!.databaseId}/segments/${evidenceSegment.value!.id}/verify`, { approve: approve.value === 'true', source: evidenceSource.value, reference: evidenceReference.value, note: evidenceNote.value }), '路段核验已留存审计。') }
function publish() { if (selected.value && window.confirm('确认发布此路线版本？所有道路段必须已核验且几何连续；发布后需新建版本修改。')) act(() => businessApi(`/routes/${selected.value!.databaseId}/publish`, {}), '路线已发布，可供调度分配任务。') }
function copy() { if (selected.value) act(async () => { const result = await businessApi<{id:number}>(`/routes/${selected.value!.databaseId}/versions`, {}); selectedId.value = String(result.id) }, '已创建新版本；保留旧版本，新版本各路段需重新核验。') }
</script>

<template>
  <div class="portal-content">
    <div class="section-title"><div><p class="kicker">DATABASE ROUTES</p><h2>基础路线与版本</h2></div><button class="outline-button" :disabled="busy" @click="loadBusiness">刷新路线</button></div>
    <p v-if="businessError || error" class="alert error" role="alert">{{ error || businessError }}</p><p v-if="notice" class="alert success" role="status">{{ notice }}</p>
    <details class="business-panel"><summary>新建不同 OD 的基础路线</summary><form class="business-form form-grid" @submit.prevent="create"><label>路线名称<input v-model="name" required maxlength="120"></label><label>起点城市<input v-model="origin" required maxlength="80"></label><label>终点城市<input v-model="destination" required maxlength="80"></label><label>数据来源<input v-model="source" required maxlength="80" placeholder="填写来源名称；示例必须标注待核验"></label><button class="primary-button" type="submit" :disabled="busy">保存路线草稿</button></form></details>
    <label class="business-filter">选择路线版本<select :value="selected?.databaseId ?? ''" @change="selectedId = ($event.target as HTMLSelectElement).value; evidenceSegment = null"><option v-for="r in businessRoutes" :key="r.databaseId" :value="r.databaseId">{{ r.name }} · {{ r.originCity }} → {{ r.destinationCity }} v{{ r.version }} · {{ label(r.verificationStatus) }}</option></select></label>
    <div v-if="!selected && businessLoaded" class="empty-panel"><strong>尚无基础路线</strong><p>先建立路线草稿，再逐段补充道路、地图请求、几何和货车依据。</p></div>
    <template v-if="selected">
      <div class="business-panel"><div class="detail-top"><div><span class="tag">DB-{{ selected.databaseId }} · v{{ selected.version }}</span><h3>{{ selected.name }}</h3><p>{{ selected.originCity }} → {{ selected.destinationCity }} · {{ label(selected.verificationStatus) }}</p></div><button class="outline-button" :disabled="busy" @click="copy">创建新版本</button></div><p class="source-note">所有路段的城市顺序、道路几何、货车依据必须齐备，发布后才能分配运输。普通驾车地图结果不能单独证明货车可通行。</p></div>
      <TransportMap title="基础路线道路地图" :plan="selected" />
      <TruckRouteQuery :key="selected.databaseId" :origin-city="selected.originCity" :destination-city="selected.destinationCity" :editable="selected.verificationStatus !== 'VERIFIED'" @prefill="prefill" />
      <div class="section business-segments"><article v-for="segment in selected.segments" :key="segment.id" class="business-panel"><div class="detail-top"><div><span class="tag">路段 {{ segment.sequence }}</span><h3>{{ segment.fromCity }} → {{ segment.toCity }}</h3><p>{{ segment.roadName }} · {{ segment.distanceKm }} km · {{ segment.estimatedMinutes }} min</p></div><span :class="segment.truckAccessStatus === 'VERIFIED' ? 'verified' : 'unverified'">{{ label(segment.truckAccessStatus) }}</span></div><p class="source-note">地图：{{ segment.mapSource }} · 引用：{{ segment.mapResponseReference }}<br>货车依据：{{ segment.truckEvidenceSource || '待补充' }} · {{ segment.truckEvidenceReference || '待补充' }}</p><div v-if="selected.verificationStatus !== 'VERIFIED'" class="review-actions"><button class="outline-button" @click="edit(segment)">编辑路段 {{ segment.sequence }}</button><button class="primary-button" @click="evidence(segment)">核验路段 {{ segment.sequence }}</button></div></article></div>
      <form v-if="evidenceSegment" class="business-panel business-form" @submit.prevent="verify"><h3>路段 {{ evidenceSegment.sequence }} 货车通行核验</h3><label>依据来源<input v-model="evidenceSource" required maxlength="160"></label><label>公开依据链接<input v-model="evidenceReference" required maxlength="512" placeholder="https://"></label><label>核验说明（车型、载重、限制与查询时间）<textarea v-model="evidenceNote" required maxlength="1000" rows="4"></textarea></label><label>核验结论<select v-model="approve"><option value="true">通过</option><option value="false">拒绝</option></select></label><button class="primary-button" :disabled="busy" type="submit">提交路段核验</button></form>
      <details v-if="selected.verificationStatus !== 'VERIFIED'" class="business-panel" open><summary>维护道路段（相同顺序号会更新该段并撤销原核验）</summary><form class="business-form form-grid" @submit.prevent="saveSegment"><label>顺序号<input v-model.number="sequence" type="number" min="1" max="1000" required></label><label>出发城市<input v-model="from" required maxlength="80"></label><label>到达城市<input v-model="to" required maxlength="80"></label><label>道路名称<input v-model="road" required maxlength="160"></label><label>道路类型<input v-model="roadType" required maxlength="48"></label><label>地图里程（km）<input v-model.number="distance" type="number" min="0.01" step="0.01" required></label><label>地图预计耗时（min）<input v-model.number="minutes" type="number" min="0.01" step="0.01" required></label><label>地图来源<input v-model="mapSource" required maxlength="80"></label><label class="form-wide">地图返回结果引用<input v-model="reference" required maxlength="512"></label><label class="form-wide">脱敏地图请求 JSON（含查询时间，不含 Key）<textarea v-model="request" required rows="3"></textarea></label><label class="form-wide">道路几何 JSON（可先空缺，核验通过前必须补齐）<textarea v-model="geometry" rows="5" placeholder='{"coordinateSystem":"GCJ02","coordinates":[[经度,纬度],…]}'></textarea></label><button class="primary-button" :disabled="busy" type="submit">保存道路段</button></form></details>
      <div v-if="selected.verificationStatus !== 'VERIFIED'" class="review-actions"><button class="primary-button" :disabled="busy || !selected.segments.length" @click="publish">检查全部路段并发布路线</button></div>
    </template>
  </div>
</template>
