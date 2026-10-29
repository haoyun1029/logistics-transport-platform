<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import type * as Leaflet from 'leaflet'
import 'leaflet/dist/leaflet.css'
import type { Route } from '../workspace'
import { sessionFetch } from '../auth'
import { cityCoordinates, citySections, latestSimulationRun, trackPointSections, validMapPoint } from '../map-data'
import type { CoordinateSystem, MapPoint, Trajectory } from '../map-data'
import { roadCoordinates, wgs84 } from '../road-geometry'
import { loadAmap, amapPosition } from '../amap'
import type { AmapSdk, AmapMap, AmapOverlay } from '../amap'

const props = defineProps<{ title: string; plan: Route | null; actual?: Route | null; candidate?: Route | null; taskId?: string; trajectoryUrl?: string; presentation?: boolean; live?: boolean }>()
const emit = defineEmits<{ snapshot: [value: { pointCount: number; speedKmh: number | null; eventTime: string | null; anomalyCount: number }] }>()
const container = ref<HTMLElement>()
const trajectory = ref<Trajectory | null>(null)
const requestState = ref<'loading' | 'ready' | 'failed'>('loading')
const mapError = ref('')
const tileError = ref(false)
const provider = ref<'AMAP' | 'OSM_PREVIEW'>('OSM_PREVIEW')
const providerWarning = ref('')
const showPlan = ref(true)
const showCandidate = ref(true)
const showActual = ref(true)
const showGps = ref(true)
const playing = ref(false)
const cursor = ref(0)
const allPoints = computed(() => trajectory.value?.points.filter(validMapPoint) ?? [])
const points = computed(() => latestSimulationRun(allPoints.value))
const activePoint = computed(() => points.value[cursor.value])
const overspeedCount = computed(() => points.value.filter(point => point.anomalies?.includes('OVERSPEED')).length)
const hasRoadGeometry = computed(() => props.plan?.segments.some(segment => roadCoordinates(segment.geometryJson).length) ?? false)
const hasOsmRoads = computed(() => [props.plan, props.actual, props.candidate].some(route => route?.segments.some(segment => segment.mapSource === 'OSRM_OSM_DRIVING_V5')))
function routePositions(route: Route | null | undefined) { const roads = route?.segments.map(segment => roadCoordinates(segment.geometryJson)).filter(section => section.length) ?? []; return roads.length ? roads : citySections(route?.citySequence ?? []) }
const sourceText = computed(() => {
  if (props.presentation) {
    if (requestState.value === 'loading') return '正在获取位置…'
    if (requestState.value === 'failed') return '位置暂不可用'
    return points.value.length ? `位置已更新 · ${points.value.length} 个点` : '等待位置数据'
  }
  return requestState.value === 'loading' ? '正在读取轨迹…' : requestState.value === 'failed' ? '轨迹服务暂不可用' : points.value.length ? `已读取 ${points.value.length} 个位置点` : '暂无位置数据'
})
let library: typeof Leaflet | undefined
let map: Leaflet.Map | undefined
let routeGroup: Leaflet.LayerGroup | undefined
let gpsGroup: Leaflet.LayerGroup | undefined
let positionMarker: Leaflet.Marker | undefined
let tileLayer: Leaflet.TileLayer | undefined
let poll: ReturnType<typeof setInterval> | undefined
let playback: ReturnType<typeof setInterval> | undefined
let resizeObserver: ResizeObserver | undefined
let requestNumber = 0
let disposed = false
let hasFittedGps = false
let amap: AmapSdk | undefined
let amapMap: AmapMap | undefined
let amapRoutes: AmapOverlay[] = []
let amapGps: AmapOverlay[] = []
let amapPositionMarker: AmapOverlay | undefined

function formatTime(value?: string) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }
function popup(text: string) { const element = document.createElement('div'); element.textContent = text; return element }
function pointSystem(point: MapPoint): CoordinateSystem { return point.coordinateSystem === 'GCJ02' ? 'GCJ02' : 'WGS84' }
function amapGpsPosition(point: MapPoint) { return amapPosition([point.latitude, point.longitude], pointSystem(point)) }
function leafletGpsPosition(point: MapPoint) {
  if (pointSystem(point) === 'GCJ02') return wgs84(point.longitude, point.latitude, 'GCJ02')
  return [point.latitude, point.longitude] as [number, number]
}

function drawRoutes() {
  if (amap && amapMap) {
    amapMap.remove(amapRoutes); amapRoutes = []
    for (const item of [{ route: props.plan, visible: showPlan.value, color: '#247d68' }, { route: props.actual, visible: showActual.value, color: '#d58c36' }, { route: props.candidate, visible: showCandidate.value, color: '#b184d1' }]) {
      if (!item.route || !item.visible) continue
      const roads = item.route.segments.some(s => roadCoordinates(s.geometryJson).length)
      for (const section of routePositions(item.route)) if (section.length > 1) amapRoutes.push(new amap.Polyline({ path: section.map(position => amapPosition(position, 'WGS84')), strokeColor: item.color, strokeWeight: 5, strokeStyle: roads ? 'solid' : 'dashed', strokeOpacity: .85 }))
      const positions = routePositions(item.route).flat()
      if (positions.length) for (const [index, text] of [[0, item.route.originCity], [positions.length - 1, item.route.destinationCity]] as const) amapRoutes.push(new amap.Text({ position: amapPosition(positions[index]!, 'WGS84'), text, offset: [0, -14], style: { color: item.color, background: '#fff', padding: '3px 6px' } }))
    }
    amapMap.add(amapRoutes); return
  }
  if (!library || !routeGroup) return
  routeGroup.clearLayers()
  const sets = [
    { route: props.plan, visible: showPlan.value, color: '#247d68', name: '计划城市连线' },
    { route: props.candidate, visible: showCandidate.value, color: '#b184d1', name: '候选城市连线' },
    { route: props.actual, visible: showActual.value, color: '#d58c36', name: '实际匹配道路' },
  ]
  for (const item of sets) {
    if (!item.route || !item.visible) continue
    const roadGeometry = item.route.segments.some(segment => roadCoordinates(segment.geometryJson).length)
    for (const section of routePositions(item.route)) {
      if (section.length > 1) library.polyline(section, { color: item.color, weight: 4, dashArray: roadGeometry ? undefined : '8 7', opacity: .8 }).addTo(routeGroup)
    }
    if (roadGeometry) continue
    item.route.citySequence.forEach((city, index) => {
      const position = cityCoordinates[city]
      if (!position) return
      const endpoint = index === 0 || index === item.route!.citySequence.length - 1
      library!.circleMarker(position, { radius: endpoint ? 7 : 4, color: item.color, weight: 2, fillColor: '#fff', fillOpacity: 1 })
        .bindTooltip(popup(`${city}${index === 0 ? ' · 起点' : index === item.route!.citySequence.length - 1 ? ' · 终点' : ''}`), { permanent: endpoint, direction: 'top', className: 'city-map-label' })
        .bindPopup(popup(`${city} · ${item.name}；城市示意位置，不代表已核验道路。`)).addTo(routeGroup!)
    })
  }
}

function drawGps() {
  if (amap && amapMap) {
    amapMap.remove(amapGps); if (amapPositionMarker) amapMap.remove(amapPositionMarker); amapPositionMarker = undefined; amapGps = []
    if (!showGps.value) return
    for (const section of trackPointSections(points.value)) if (section.length > 1) amapGps.push(new amap.Polyline({ path: section.map(amapGpsPosition), strokeColor: '#408ed1', strokeWeight: 4 }))
    for (const point of points.value) {
      const anomaly = point.anomalies?.length > 0
      const position = amapGpsPosition(point)
      const marker = new amap.CircleMarker({ center: position, radius: anomaly ? 5 : 3, fillColor: anomaly ? '#d46753' : '#408ed1', fillOpacity: .9, strokeWeight: 1 })
      marker.on?.('click', () => new amap!.InfoWindow({ content: popup(`位置点 #${point.sequence} · ${point.speedKmh.toFixed(1)} km/h · ${formatTime(point.eventTime)} · ${point.anomalies.join(' / ') || '无异常'}`) }).open(amapMap!, position))
      amapGps.push(marker)
    }
    amapMap.add(amapGps); updatePosition(); return
  }
  if (!library || !gpsGroup) return
  gpsGroup.clearLayers()
  positionMarker = undefined
  if (!showGps.value) return
  for (const section of trackPointSections(points.value)) {
    const positions = section.map(leafletGpsPosition)
    if (positions.length > 1) library.polyline(positions, { color: '#408ed1', weight: 4, opacity: .8 }).addTo(gpsGroup)
  }
  for (const point of points.value) {
    const anomaly = point.anomalies?.includes('OVERSPEED')
    library.circleMarker(leafletGpsPosition(point), { radius: anomaly ? 5 : 3, color: anomaly ? '#d46753' : '#408ed1', weight: 1, fillOpacity: .85 })
      .bindPopup(popup(`位置点 #${point.sequence} · ${point.speedKmh.toFixed(1)} km/h · ${formatTime(point.eventTime)}${anomaly ? ' · 超速提示' : ''}`)).addTo(gpsGroup)
  }
  updatePosition()
}

function updatePosition() {
  if (amap && amapMap) {
    const point = activePoint.value
    if (!showGps.value || !point) return
    const position = amapGpsPosition(point)
    if (amapPositionMarker) amapPositionMarker.setPosition?.(position)
    else { amapPositionMarker = new amap.Marker({ position, anchor: 'center', offset: [0, 0], title: props.presentation ? '车辆当前位置（演示）' : '轨迹当前位置', content: popup('▰') }); amapMap.add(amapPositionMarker) }
    return
  }
  if (!library || !gpsGroup || !showGps.value) return
  const point = activePoint.value
  if (!point) return
  if (positionMarker) positionMarker.setLatLng([point.latitude, point.longitude])
  else positionMarker = library.marker([point.latitude, point.longitude], {
    icon: library.divIcon({ className: 'vehicle-map-marker', html: '<span aria-hidden="true">▰</span>', iconSize: [30, 30], iconAnchor: [15, 15] }),
    title: props.presentation ? '车辆当前位置（演示）' : '轨迹当前位置', zIndexOffset: 1000,
  }).addTo(gpsGroup)
  positionMarker.bindPopup(popup(`${props.presentation ? '车辆当前位置' : '轨迹当前位置'} #${point.sequence} · ${point.speedKmh.toFixed(1)} km/h · ${formatTime(point.eventTime)}${props.presentation ? '；演示位置，不是车载设备实时定位。' : ''}`))
}

function fitAll() {
  if (amapMap) { const overlays = [...amapRoutes, ...amapGps]; if (overlays.length) amapMap.setFitView(overlays, true, [48, 48, 48, 48], 12); return }
  if (!library || !map) return
  const positions = [
    ...routePositions(props.plan).flat(),
    ...routePositions(props.candidate).flat(),
    ...routePositions(props.actual).flat(),
    ...points.value.map(leafletGpsPosition),
  ]
  if (positions.length) map.fitBounds(library.latLngBounds(positions), { padding: [48, 48], maxZoom: 10, animate: false })
}

function stopPlayback() { playing.value = false; if (playback) clearInterval(playback); playback = undefined }
function togglePlayback() {
  if (playing.value) { stopPlayback(); return }
  if (!points.value.length) return
  if (cursor.value >= points.value.length - 1) cursor.value = 0
  playing.value = true
  playback = setInterval(() => {
    if (cursor.value >= points.value.length - 1) stopPlayback()
    else cursor.value++
  }, 650)
}

async function loadTrajectory() {
  const request = ++requestNumber
  const taskId = props.taskId
  if (!taskId) { trajectory.value = null; requestState.value = 'ready'; return }
  try {
    const response = await sessionFetch(props.trajectoryUrl ?? `/api/telemetry/demo-tasks/${encodeURIComponent(taskId)}/trajectory`)
    if (!response.ok) throw new Error('轨迹读取失败')
    const data = await response.json() as Trajectory
    if (request !== requestNumber || disposed) return
    const previousId = activePoint.value?.eventId
    const atEnd = cursor.value >= points.value.length - 1
    trajectory.value = data
    requestState.value = data.status === 'CONNECTED' ? 'ready' : 'failed'
    const previousIndex = points.value.findIndex(point => point.eventId === previousId)
    cursor.value = previousIndex >= 0 && (playing.value || !atEnd) ? previousIndex : Math.max(0, points.value.length - 1)
    drawGps()
    if (!hasFittedGps && points.value.length) { fitAll(); hasFittedGps = true }
  } catch {
    if (request !== requestNumber || disposed) return
    requestState.value = 'failed' // Keep any last successful snapshot, explicitly labelled below.
  }
}

watch(() => props.taskId, () => {
  requestNumber++; stopPlayback(); trajectory.value = null; cursor.value = 0; hasFittedGps = false
  requestState.value = 'loading'; drawGps(); fitAll(); loadTrajectory()
})
watch(() => [props.plan?.id, props.plan?.citySequence.join('|'), props.plan?.segments.map(s => s.geometryJson).join('|'), props.candidate?.id, props.candidate?.citySequence.join('|'), props.candidate?.segments.map(s => s.geometryJson).join('|'),props.actual?.id,props.actual?.segments.map(s=>s.geometryJson).join('|')].join('::'), () => { drawRoutes(); fitAll() })
watch([showPlan, showCandidate, showActual], drawRoutes)
watch(showGps, drawGps)
watch(cursor, updatePosition)
onMounted(async () => {
  poll = setInterval(loadTrajectory, 10000)
  loadTrajectory()
  try {
    try { amap = await loadAmap() ?? undefined } catch (error) { providerWarning.value = error instanceof Error ? error.message : '高德加载失败，使用临时预览。' }
    if (disposed || !container.value) return
    if (amap) {
      provider.value = 'AMAP'; amapMap = new amap.Map(container.value, { zoom: 5, center: [112, 33], scrollWheel: false, resizeEnable: true, viewMode: '2D' })
      drawRoutes(); drawGps(); fitAll()
      resizeObserver = new ResizeObserver(() => amapMap?.resize()); resizeObserver.observe(container.value)
      return
    }
    library = await import('leaflet')
    if (disposed || !container.value) return
    map = library.map(container.value, { scrollWheelZoom: false }).setView([33, 112], 5)
    map.zoomControl.setPosition('topright')
    tileLayer = library.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 18, attribution: '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a> contributors',
    }).addTo(map)
    tileLayer.on('tileerror', () => { tileError.value = true })
    tileLayer.on('tileload', () => { tileError.value = false })
    routeGroup = library.layerGroup().addTo(map)
    gpsGroup = library.layerGroup().addTo(map)
    drawRoutes(); drawGps(); fitAll()
    resizeObserver = new ResizeObserver(() => map?.invalidateSize({ pan: false }))
    resizeObserver.observe(container.value)
  } catch { mapError.value = '地图组件加载失败，请刷新页面重试。' }
})
onUnmounted(() => {
  disposed = true; requestNumber++; stopPlayback(); if (poll) clearInterval(poll)
  resizeObserver?.disconnect(); map?.remove(); amapMap?.destroy()
})
</script>

<template>
  <section class="transport-map-card" :aria-label="title">
    <header class="map-heading"><div><p class="kicker">TRANSPORT MAP</p><h2>{{ title }}</h2><p>{{ plan ? `${plan.originCity} → ${plan.destinationCity}` : '等待任务路线' }} <span v-if="taskId">· {{ taskId }}</span></p></div><span class="map-source-badge">{{ presentation ? '位置演示 / 路线参考' : hasRoadGeometry ? '已录入道路折线' : '路线城市示意' }}</span></header>
    <div class="map-toolbar">
      <label><input v-model="showPlan" type="checkbox"><i class="map-line plan"></i>{{ hasRoadGeometry ? '计划道路折线' : '计划城市连线' }}</label>
      <label v-if="candidate"><input v-model="showCandidate" type="checkbox"><i class="map-line candidate"></i>候选路线</label>
      <label v-if="actual"><input v-model="showActual" type="checkbox"><i class="map-line" style="background:#d58c36"></i>实际匹配道路</label>
      <label><input v-model="showGps" type="checkbox"><i class="map-line gps"></i>{{ presentation ? '车辆位置' : '位置点' }}</label>
      <button class="outline-button" type="button" @click="fitAll">查看全程</button>
      <button class="outline-button" type="button" @click="loadTrajectory">刷新轨迹</button>
    </div>
    <div class="map-canvas-wrap"><div ref="container" class="transport-map-canvas" role="region" :aria-label="`${title}交互地图`"></div>
      <div v-if="mapError" class="map-feedback" role="alert">{{ mapError }}</div>
      <div v-else-if="tileError" class="map-feedback" role="status">底图加载受阻，当前仅显示坐标图层。<button @click="tileLayer?.redraw()">重试底图</button></div>
      <span class="map-overlay-badge">{{ sourceText }}</span>
    </div>
    <div v-if="!live" class="map-replay">
      <button class="primary-button" :disabled="points.length < 2" @click="togglePlayback">{{ playing ? '暂停回放' : '回放路线' }}</button>
      <label class="map-slider-label">回放进度<input v-model.number="cursor" type="range" aria-label="路线回放进度" min="0" :max="Math.max(0, points.length - 1)" :disabled="!points.length" @input="stopPlayback"></label>
      <span>{{ points.length ? `${cursor + 1} / ${points.length}` : '0 / 0' }} 点</span>
    </div>
    <div v-else class="map-live-status"><span>位置状态</span><strong>{{ sourceText }}</strong><small>页面会自动刷新最新位置</small></div>
    <div class="map-evidence-row">
      <span>{{ live ? '最新位置' : '回放位置' }} <strong>{{ activePoint ? `#${activePoint.sequence} · ${activePoint.speedKmh.toFixed(1)} km/h` : '暂无位置' }}</strong></span>
      <span>位置时间 <strong>{{ formatTime(activePoint?.eventTime) }}</strong></span>
      <span>超速点 <strong>{{ overspeedCount }} <i class="anomaly-dot"></i></strong></span>
    </div>
    <p class="map-data-note" role="status">{{ requestState === 'failed' ? (points.length ? '位置刷新失败，保留上次快照。' : '位置服务暂不可用，仍可查看计划路线。') : !points.length ? '暂无该任务的位置数据，地图先显示计划路线。司机确认接单后，系统会自动发布模拟 GPS 轨迹。' : presentation ? '蓝线表示最近一轮模拟 GPS 位置轨迹，红点为超速提示；这里显示演示位置，不代表真实车载定位。' : '蓝线连接最近一轮模拟 GPS 的连续位置点，红点为超速提示；回放不改变运输任务状态。' }}</p>
    <p v-if="providerWarning" class="map-data-note" role="status">{{ providerWarning }}</p>
    <p v-if="hasOsmRoads" class="map-data-note">道路资料：<a href="https://project-osrm.org/" target="_blank" rel="noopener">OSRM</a> · © <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap contributors（ODbL）</a> · <a href="https://www.openstreetmap.org/fixthemap" target="_blank" rel="noopener">修正地图</a>。普通汽车路线仍需逐段核验货车依据。</p>
    <p class="map-data-note">底图：{{ provider === 'AMAP' ? '高德地图' : 'OpenStreetMap（预览）' }}。{{ hasRoadGeometry ? '地图显示用于路线参考。' : '虚线为城市位置连线。' }}{{ presentation ? '位置为演示数据，不代表真实车载定位。' : '位置点仅用于任务过程展示。' }}{{ trajectory?.truncated ? '仅展示最近 2000 条记录。' : '' }}{{ trajectory ? ` 查询时间：${formatTime(trajectory.fetchedAt)}` : '' }}</p>
  </section>
</template>

