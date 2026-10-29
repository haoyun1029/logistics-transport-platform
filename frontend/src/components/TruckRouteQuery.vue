<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { queryTruckRoute } from '../truck-routes'
import type { TruckRequest, QuoteResult, SegmentPrefill, RoadMode } from '../truck-routes'
import TruckQuoteHistory from './TruckQuoteHistory.vue'
import RoadQuoteMaterial from './RoadQuoteMaterial.vue'
const props = defineProps<{ originCity: string; destinationCity: string; editable: boolean }>()
const emit = defineEmits<{ prefill: [value: SegmentPrefill] }>()
const form = reactive<TruckRequest>({ origin: '', destination: '', originCity: props.originCity, destinationCity: props.destinationCity, strategy: 10, vehicleSize: '3', loadTons: 10, plateNumber: '' })
const mode = ref<RoadMode>('ROAD_GEOMETRY'), via = ref(''), routeIndex = ref(0)
const busy = ref(false), error = ref(''), notice = ref(''), result = ref<QuoteResult | null>(null)
const history = ref<InstanceType<typeof TruckQuoteHistory>>()
watch(() => [props.originCity, props.destinationCity], () => { form.originCity = props.originCity; form.destinationCity = props.destinationCity; result.value = null; error.value = ''; notice.value = '' })
watch(mode, (value, previous) => {
  result.value = null; error.value = ''; notice.value = ''
  if ((value === 'OPEN_ROAD') !== (previous === 'OPEN_ROAD')) { form.origin = ''; form.destination = ''; notice.value = '已切换坐标体系，请重新填写对应坐标；系统不把 GCJ02 和 WGS84 当作同一坐标。' }
})
async function query() {
  busy.value = true; result.value = null; error.value = ''; notice.value = ''
  try { result.value = await queryTruckRoute({ ...form }, mode.value, { via: via.value.split(';').map(p => p.trim()).filter(Boolean), routeIndex: routeIndex.value }) } catch(e) { error.value = e instanceof Error ? e.message : '查询失败' }
  finally { busy.value = false; await history.value?.refresh() }
}
function prefill(value: SegmentPrefill) {
  emit('prefill', value)
  notice.value = value.geometryJson ? '已填入下方道路段表单，请核对后保存；货车通行仍待核验。' : '地图指标已填入，原返回折线存在缺口，几何留空等待补齐；不能据此通过核验。'
}
</script>
<template>
  <section class="business-panel">
    <p class="kicker">TRUCK ROUTE MATERIALS</p><h3>查询相邻城市的道路资料</h3>
    <p class="source-note">每次查询相邻地级市之间的一段。坐标使用{{ mode === 'OPEN_ROAD' ? ' WGS84' : ' GCJ02' }} 经度,纬度；沿途城市须另行核对，接口不会自动生成可信城市序列。普通驾车服务不评估货车限制。</p>
    <label class="business-filter">资料方式<select v-model="mode" :disabled="busy"><option value="ROAD_GEOMETRY">普通驾车道路资料（使用现有配额，另补货车公开依据）</option><option value="OPEN_ROAD">OSRM 开源道路资料（无需 Key，公共演示服务）</option><option value="TRUCK">货车规划（需单独试用或授权）</option></select></label>
    <p v-if="mode === 'ROAD_GEOMETRY'" class="source-note">此方式仅获取普通驾车的道路几何、里程、耗时，不评估车型和货车限制；收费接口不会自动调用。采用标准速度优先策略，结果始终待补货车依据。</p>
    <p v-if="mode === 'OPEN_ROAD'" class="source-note">OSRM / OpenStreetMap 普通汽车路线，无需 API Key。仅限低频演示：最多每秒一次，不批量采集，优先复用历史资料；公共服务不保证可用性。完整折线来自服务返回，不用高德指标替代其估算值，仍需公开货车通行依据。</p>
    <form class="business-form form-grid" @submit.prevent="query">
      <label>出发地级市<input v-model="form.originCity" required maxlength="80"></label><label>到达地级市<input v-model="form.destinationCity" required maxlength="80"></label>
      <label>出发坐标（经度,纬度）<input v-model="form.origin" required placeholder="116.397000,39.908000"></label><label>到达坐标（经度,纬度）<input v-model="form.destination" required placeholder="最多六位小数"></label>
      <template v-if="mode === 'OPEN_ROAD'"><label>途经道路坐标（WGS84，可选）<input v-model="via" placeholder="最多三个，用英文分号分隔"></label><label>服务返回路线序号<select v-model.number="routeIndex"><option :value="0">0 · 首条路线</option><option :value="1">1 · 第一条备选（如有）</option><option :value="2">2 · 第二条备选（如有）</option></select></label></template>
      <template v-if="mode === 'TRUCK'">
      <label>货车类型<select v-model="form.vehicleSize"><option value="1">微型货车</option><option value="2">轻型货车</option><option value="3">中型货车</option><option value="4">重型货车</option></select></label>
      <label>路线策略<select v-model.number="form.strategy"><option :value="10">10 · 无路况，速度优先</option><option :value="1">1 · 躲避拥堵</option><option :value="2">2 · 不走高速</option><option :value="3">3 · 避免收费</option><option :value="4">4 · 躲避拥堵且不走高速</option><option :value="5">5 · 避免收费且不走高速</option><option :value="6">6 · 躲避拥堵且避免收费</option><option :value="7">7 · 躲避拥堵、避免收费且不走高速</option><option :value="8">8 · 高速优先</option><option :value="9">9 · 躲避拥堵且高速优先</option><option :value="11">11 · 高德推荐（考虑路况）</option><option :value="12">12 · 无路况，不走高速</option><option :value="13">13 · 无路况，距离优先</option></select></label>
      <label>车辆总质量（吨，含车重）<input v-model.number="form.loadTons" type="number" min="0" max="100" step="0.001" required></label><label>完整车牌（可选）<input v-model="form.plateNumber" placeholder="省份简称 + 大写字母数字" maxlength="8"></label>
      <label>车高（米，可选）<input v-model.number="form.heightMeters" type="number" min="0" max="25.5" step="0.001"></label><label>车宽（米，可选）<input v-model.number="form.widthMeters" type="number" min="0" max="25.5" step="0.001"></label>
      <label>核定载重（吨，可选）<input v-model.number="form.ratedPayloadTons" type="number" min="0" max="99.999" step="0.001"></label><label>轴数（可选）<input v-model.number="form.axleCount" type="number" min="0" max="255" step="1"></label>
      </template>
      <button class="primary-button" type="submit" :disabled="busy">{{ busy ? '查询中…' : mode === 'TRUCK' ? '获取货车路线资料' : mode === 'OPEN_ROAD' ? '获取 OSRM 道路资料' : '获取普通道路资料' }}</button>
    </form>
    <p class="source-note">货车规划是单独授权的物流接口。若提示权限不足，可向高德申请课设试用；不必先充值。也可用普通地图资料配合可靠公开的货车通行依据逐段补证。</p>
    <p v-if="error" class="alert error" role="alert">{{ error }}</p><p v-if="notice" class="alert success" role="status">{{ notice }}</p>
    <RoadQuoteMaterial v-if="result" :result="result" :editable="editable" @prefill="prefill" />
  </section>
  <TruckQuoteHistory ref="history" :editable="editable" @prefill="prefill" />
</template>
<style scoped>pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 24rem; overflow: auto; } h3 { margin-bottom: 1rem; }</style>
