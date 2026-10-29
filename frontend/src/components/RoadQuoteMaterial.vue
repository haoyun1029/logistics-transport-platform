<script setup lang="ts">
import { quotePrefill, restrictionText } from '../truck-routes'
import type { QuoteResult, SegmentPrefill } from '../truck-routes'
import RoadMaterialMap from './RoadMaterialMap.vue'
defineProps<{ result: QuoteResult; editable?: boolean }>()
defineEmits<{ prefill: [value: SegmentPrefill] }>()
</script>
<template>
  <article class="quote-material business-panel">
    <h4>资料 #{{ result.quoteId }} · 待货车通行核验</h4>
    <p>{{ result.quote.originCity }} → {{ result.quote.destinationCity }} · {{ result.quote.distanceKm.toFixed(2) }} km · {{ result.quote.durationMinutes.toFixed(1) }} min</p>
    <p>{{ result.quote.dataSource.includes('TRUCK') ? restrictionText(result.quote.restriction) : '普通驾车资料，不含货车通行结论，需补可靠公开依据' }} · {{ new Date(result.quote.requestedAt).toLocaleString() }}</p>
    <p v-if="result.quote.dataSource === 'OSRM_OSM_DRIVING_V5'" class="source-note">路线来源：<a href="https://project-osrm.org/" target="_blank" rel="noopener">OSRM</a> · 数据 © <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap contributors（ODbL）</a> · <a href="https://www.openstreetmap.org/fixthemap" target="_blank" rel="noopener">修正地图</a> · <a href="https://routing.openstreetmap.de/about.html" target="_blank" rel="noopener">公共服务使用规则</a></p>
    <p>途经道路：{{ result.quote.roadNames.join(' → ') || '接口未提供道路名称，请补充' }}</p>
    <p>几何：{{ result.quote.geometry ? '连续折线已取得，仍需核对' : '缺失或不连续，不能自动填入' }}</p>
    <RoadMaterialMap :key="result.quoteId" :quote="result.quote" />
    <button v-if="editable" class="outline-button" @click="$emit('prefill', quotePrefill(result))">{{ result.quote.geometry ? '将资料填入下方路段表单' : '仅填入指标，几何保持空缺' }}</button>
    <details><summary>脱敏请求参数</summary><pre>{{ JSON.stringify(result.quote.requestParameters, null, 2) }}</pre></details>
    <details><summary>地图原始返回</summary><pre>{{ result.quote.rawResponse }}</pre></details>
  </article>
</template>
<style scoped>pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 24rem; overflow: auto; } details { margin-block: 1rem; } .quote-material { margin-top: 1rem; min-width: 0; }</style>
