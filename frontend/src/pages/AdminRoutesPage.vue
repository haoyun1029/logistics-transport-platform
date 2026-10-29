<script setup lang="ts">
import { useWorkspace, label } from '../workspace'
import TransportMap from '../components/TransportMap.vue'
const { overview, selectedRouteId, selectedRoute } = useWorkspace()

</script>

<template>
  <div class="portal-content">
    <section class="section" id="routes"><div class="section-title"><div><p class="kicker">ROUTE CONTROL</p><h2>路线核验与版本</h2></div><span class="muted">管理员端专属</span></div><div class="route-layout"><div class="route-list"><button v-for="route in overview?.routes" :key="route.id" class="route-row" :class="{ selected: route.id === selectedRouteId }" @click="selectedRouteId = route.id"><span><strong>{{ route.originCity }} → {{ route.destinationCity }}</strong><small>{{ route.citySequence.join(' · ') }}</small></span><em :class="route.verificationStatus === 'VERIFIED' ? 'verified' : 'unverified'">{{ label(route.verificationStatus) }}</em></button></div><div v-if="selectedRoute" class="route-detail"><div class="detail-top"><div><span class="tag">{{ selectedRoute.id }}</span><h3>{{ selectedRoute.originCity }} → {{ selectedRoute.destinationCity }}</h3></div><span class="detail-status" :class="selectedRoute.verificationStatus === 'VERIFIED' ? 'verified' : 'unverified'">{{ label(selectedRoute.verificationStatus) }}</span></div><div class="city-line"><span v-for="(city, index) in selectedRoute.citySequence" :key="city" class="city-node"><i></i>{{ city }}<b v-if="index < selectedRoute.citySequence.length - 1">→</b></span></div><div class="segment-table"><div class="table-head"><span>道路段</span><span>里程</span><span>耗时</span><span>货车依据</span></div><div v-for="segment in selectedRoute.segments" :key="segment.sequence" class="table-row"><span><strong>{{ segment.fromCity }} → {{ segment.toCity }}</strong><small>{{ segment.roadName }}</small></span><span>{{ segment.distanceKm }} km</span><span>{{ segment.estimatedMinutes }} min</span><span class="pending-text">{{ segment.truckAccessStatus === 'VERIFIED' ? '已核验' : '待核验' }}</span></div></div><p class="source-note">数据来源：{{ selectedRoute.estimateSource }} · 未完成货车依据核验，不进入正式评分。</p></div></div></section>
    <div v-if="selectedRoute" class="section"><TransportMap title="所选路线地图" :plan="selectedRoute" /></div>
  </div>
</template>
