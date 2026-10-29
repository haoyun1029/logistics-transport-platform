<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { truckQuoteHistory, savedQuote } from '../truck-routes'
import type { QuoteHistory, QuoteResult, SegmentPrefill } from '../truck-routes'
import RoadQuoteMaterial from './RoadQuoteMaterial.vue'
defineProps<{ editable?: boolean }>()
const emit = defineEmits<{ prefill: [value: SegmentPrefill] }>()
const records = ref<QuoteHistory[]>([]), error = ref(''), loading = ref(false)
const selected = ref<QuoteResult | null>(null), opening = ref(false)
async function refresh() { loading.value = true; error.value = ''; try { records.value = await truckQuoteHistory() } catch(e) { error.value = e instanceof Error ? e.message : '读取失败' } finally { loading.value = false } }
async function open(id: number) {
  if (opening.value) return
  opening.value = true; error.value = ''
  try { selected.value = await savedQuote(id) } catch(e) { selected.value = null; error.value = e instanceof Error ? e.message : '读取资料失败' }
  finally { opening.value = false }
}
onMounted(refresh)
defineExpose({ refresh })
</script>
<template>
  <section class="business-panel">
    <div class="detail-top"><h3>成功取得的道路资料</h3><button class="outline-button" :disabled="loading" @click="refresh">刷新成功记录</button></div>
    <p class="source-note">查询成功仍为待核验；重新预览读取数据库已保存的原始资料，不再次调用地图路线接口。</p>
    <p v-if="error" class="alert error" role="alert">{{ error }}</p>
    <p v-else-if="!records.length" class="muted">{{ loading ? '正在读取…' : '暂无成功查询记录。' }}</p>
    <div v-for="q in records" :key="q.id" class="saved-quote"><div><strong>#{{ q.id }} · {{ q.originCity }} → {{ q.destinationCity }}</strong><p>{{ q.distanceKm.toFixed(2) }} km · {{ q.durationMinutes.toFixed(1) }} min · 待核验</p><small>{{ new Date(q.requestedAt).toLocaleString() }} · {{ q.dataSource }}</small></div><button class="outline-button" :disabled="opening" @click="open(q.id)">{{ editable ? '预览并复用资料' : '预览道路资料' }} #{{ q.id }}</button></div>
    <template v-if="selected"><button class="outline-button" @click="selected = null">关闭资料预览</button><RoadQuoteMaterial :result="selected" :editable="editable" @prefill="emit('prefill', $event)" /></template>
  </section>
</template>
<style scoped>.saved-quote { display: flex; gap: 1rem; align-items: center; justify-content: space-between; padding-block: 1rem; border-bottom: 1px solid #e1e9e5; margin-bottom: 1rem; } .saved-quote p { margin: .3rem 0; } small { color: #5e6d69; } @media(max-width: 600px) { .saved-quote { align-items: start; flex-direction: column; } }</style>
