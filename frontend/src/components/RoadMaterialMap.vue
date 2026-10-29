<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { loadAmap } from '../amap'
import type { AmapMap, AmapOverlay, AmapSdk } from '../amap'
import { gcj02 } from '../road-geometry'
import { geometryIssueText } from '../truck-routes'
import type { TruckQuote } from '../truck-routes'
const props = defineProps<{ quote: TruckQuote }>()
const container = ref<HTMLElement>(), error = ref(''), ready = ref(false), selected = ref(0)
const drawable = computed(() => props.quote.steps.filter(step => step.coordinates.length > 1))
const system = computed(() => props.quote.geometry?.coordinateSystem ?? props.quote.requestParameters.coordinateSystem ?? 'GCJ02')
function position(point: number[]) { if (system.value !== 'WGS84') return point; const [lat, lon] = gcj02(point[0]!, point[1]!); return [lon, lat] }
let sdk: AmapSdk | undefined, map: AmapMap | undefined, resize: ResizeObserver | undefined, disposed = false
let overlays: AmapOverlay[] = [], stepOverlays = new Map<number, AmapOverlay>()
function popup(text: string) { const element = document.createElement('div'); element.textContent = text; return element }
function draw() {
  if (!sdk || !map) return
  map.remove(overlays); overlays = []; stepOverlays = new Map()
  for (const step of drawable.value) {
    // Every source step is a separate overlay. Never connect adjacent step endpoints here.
    const line = new sdk.Polyline({ path: step.coordinates.map(position), strokeColor: !selected.value || selected.value === step.number ? '#287b72' : '#a5b6bb', strokeWeight: selected.value === step.number ? 7 : 4, strokeOpacity: .9 })
    line.on?.('click', () => focus(step.number)); overlays.push(line); stepOverlays.set(step.number, line)
  }
  for (const issue of props.quote.geometryIssues) for (const endpoint of [issue.from, issue.to]) {
    if (!endpoint) continue
    const marker = new sdk.CircleMarker({ center: position(endpoint), radius: 6, fillColor: '#d66e38', fillOpacity: 1, strokeColor: '#fff', strokeWeight: 2 })
    marker.on?.('click', () => new sdk!.InfoWindow({ content: popup(geometryIssueText(issue)) }).open(map!, position(endpoint)))
    overlays.push(marker)
  }
  map.add(overlays)
}
function focus(number: number) {
  selected.value = number; draw()
  const overlay = stepOverlays.get(number)
  if (overlay) map?.setFitView([overlay], true, [45, 45, 45, 45], 18)
}
function fitAll() { selected.value = 0; draw(); if (overlays.length) map?.setFitView(overlays, true, [45, 45, 45, 45], 14) }
onMounted(async () => {
  try {
    const loaded = await loadAmap()
    if (disposed || !container.value) return
    if (!loaded) { error.value = '高德地图尚未配置，可先查看下方步骤与缺口清单。'; return }
    sdk = loaded; map = new sdk.Map(container.value, { center: position(drawable.value[0]?.coordinates[0] ?? [112, 33]), zoom: 7, scrollWheel: false, resizeEnable: true, viewMode: '2D' })
    draw(); fitAll(); ready.value = true
    resize = new ResizeObserver(() => map?.resize()); resize.observe(container.value)
  } catch(e) { error.value = e instanceof Error ? e.message : '道路资料地图加载失败。' }
})
onUnmounted(() => { disposed = true; resize?.disconnect(); map?.destroy() })
</script>
<template>
  <section class="road-material-map" aria-label="待核验道路资料预览">
    <div class="detail-top"><h4>地图原始步骤预览</h4><button class="outline-button" :disabled="!ready || !drawable.length" @click="fitAll">查看全部道路步骤</button></div>
    <p class="source-note"><span class="legend road"></span>原始分步折线 <span class="legend gap"></span>接缝两端（点击可查看距离） · {{ drawable.length }} / {{ quote.steps.length }} 步可绘制 · {{ system }}{{ system === 'WGS84' ? '（仅展示时转 GCJ02）' : '' }}</p>
    <div class="preview-wrap"><div ref="container" class="preview-canvas" role="region" aria-label="道路资料分步交互地图"></div><p v-if="error" class="preview-error" role="alert">{{ error }}</p><p v-else-if="!ready" class="preview-error" role="status">正在加载道路资料地图…</p></div>
    <p class="source-note">每个步骤单独绘制，接缝保持断开。点击下方步骤可放大查看。预览不生成模拟 GPS，也不表示已核验货车通行。</p>
    <div v-if="quote.geometryIssues.length" class="geometry-issues" role="status"><strong>{{ quote.geometryIssues.length }} 处待补齐问题，整段几何暂不可用于模拟和优化</strong><ul><li v-for="(issue, index) in quote.geometryIssues" :key="index">{{ geometryIssueText(issue) }}</li></ul></div>
    <p v-else-if="!quote.steps.length" class="alert error">接口未提供道路步骤，几何仍缺失。</p>
    <details class="step-list"><summary>逐步查看道路（{{ quote.steps.length }} 步）</summary><ol><li v-for="step in quote.steps" :key="step.number"><button class="step-button" :disabled="!ready || step.coordinates.length < 2" :aria-pressed="selected === step.number" @click="focus(step.number)">步骤 {{ step.number }} · {{ step.roadName || '道路名称待补充' }} · {{ step.coordinates.length }} 点</button><p>{{ step.instruction || '接口未提供行驶说明' }}</p></li></ol></details>
  </section>
</template>
<style scoped>
.road-material-map { margin-block: 1.2rem; } .preview-wrap { position: relative; } .preview-canvas { height: 380px; border: 1px solid #d3dfdc; border-radius: 10px; background: #eaf0ee; } .preview-error { position: absolute; inset: 40% 1rem auto; background: #fff; padding: 1rem; text-align: center; border-radius: 8px; } .legend { display: inline-block; width: 12px; height: 6px; background: #287b72; margin-inline: .5rem .2rem; } .legend.gap { background: #d66e38; width: 9px; height: 9px; border-radius: 50%; } .geometry-issues { background: #fff4df; padding: 1rem; border-radius: 8px; color: #78511d; } .geometry-issues ul { margin-bottom: 0; } .step-list { margin-top: 1rem; } .step-list ol { max-height: 22rem; overflow: auto; padding-left: 1.7rem; } .step-list li { padding-block: .5rem; } .step-list p { font-size: .85rem; margin: .3rem 0; color: #5e6d69; } .step-button { color: #216e62; text-align: left; background: #f1f6f4; padding: .5rem; border: 1px solid #ceddd8; border-radius: 6px; cursor: pointer; } .step-button[aria-pressed="true"] { background: #dceee7; border-color: #287b72; } .step-button:disabled { cursor: default; opacity: .6; } @media(max-width: 600px) { .preview-canvas { height: 300px; } }
</style>
