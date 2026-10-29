<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useWorkspace, label } from '../workspace'
import { businessApi, time } from '../business'
import type { BusinessRoute } from '../business'
import TransportMap from '../components/TransportMap.vue'

const props = defineProps<{ portal?: string }>()

type Analysis = {
  taskId?: number
  taskCode?: string
  sampleStatus: string
  reason?: string
  demoMode?: boolean
  formalOptimizationEligible?: boolean
  matchedRoute?: BusinessRoute
  actualDistanceKm?: number
  actualFuelLiters?: number
}
type Metric = { routeId: string; estimatedMinutes: number; estimatedDistanceKm: number; estimatedFuelLiters: number }
type Score = { routeId: string; travelTimeScore: number; distanceScore: number; fuelScore: number; compositeScore: number }
type Evaluation = {
  id: number
  createdAt: string
  snapshot: {
    plan: BusinessRoute
    candidate?: BusinessRoute
    label: string
    status: string
    reason: string
    demoMode?: boolean
    formalRecommendationEligible?: boolean
    validSampleCount: number
    demoSampleCount?: number
    candidateDemoOnly?: boolean
    estimateSource?: string
    fuelValueType?: string
    limitation: string
    selectionReasons?: string[]
    metrics?: Metric[]
    scores?: Score[]
    normalizationBounds?: unknown
    weights?: unknown
    actualSamples?: Analysis[]
    candidateCombinationActuallyDriven?: boolean
  }
}

const { businessRoutes, businessTasks, businessError, loadBusiness } = useWorkspace()
const canReviewSamples = computed(() => props.portal !== 'dispatcher')
const history = ref<Evaluation[]>([]), selected = ref<number | null>(null), planId = ref(''), taskId = ref(''), busy = ref(false), error = ref(''), notice = ref(''), analysis = ref<Analysis | null>(null)
const current = computed(() => history.value.find(r => r.id === selected.value) ?? history.value[0] ?? null)
const selectedPlan = computed(() => businessRoutes.value.find(route => route.databaseId === Number(planId.value)) ?? null)
const completedSameOdTasks = computed(() => businessTasks.value.filter(task =>
  selectedPlan.value && task.status === 'COMPLETED' && task.originCity === selectedPlan.value.originCity && task.destinationCity === selectedPlan.value.destinationCity,
))
const taskFlaggedValidCount = computed(() => completedSameOdTasks.value.filter(task => task.sampleStatus === 'VALID').length)
const demoTaskCount = computed(() => completedSameOdTasks.value.filter(task => task.demoMode).length)
async function load() { try { history.value = await businessApi('/optimizations') } catch (e) { error.value = e instanceof Error ? e.message : '读取失败。' } }
async function run(action: () => Promise<unknown>) { busy.value = true; error.value = ''; notice.value = ''; try { await action(); await load(); await loadBusiness() } catch (e) { error.value = e instanceof Error ? e.message : '处理失败。' } finally { busy.value = false } }
function match() { run(async () => { analysis.value = await businessApi(`/tasks/${taskId.value}/match`, {}); notice.value = '匹配复核已保存，结论与排除原因见下方。' }) }
function evaluate() { run(async () => { const result = await businessApi<Evaluation>('/optimizations', { planRouteId: Number(planId.value) }); selected.value = result.id; notice.value = result.snapshot.reason }) }
onMounted(async () => {
  await Promise.all([loadBusiness(), load()])
  if (!planId.value) {
    const completedTask = businessTasks.value.find(task => task.status === 'COMPLETED')
    const route = businessRoutes.value.find(item => item.databaseId === completedTask?.routeId)
    if (route) planId.value = String(route.databaseId)
  }
})
</script>

<template>
  <div class="portal-content">
    <section class="section">
      <div class="section-title">
        <div><p class="kicker">PERSISTED OPTIMIZATION EVIDENCE</p><h2>实际道路样本与路线优化</h2></div>
        <button class="outline-button" :disabled="busy" @click="load">刷新评估历史</button>
      </div>
      <p class="source-note">默认只使用已逐段核验的同 OD 道路生成正式候选。后端显式开启 DEMO_MODE 后，待核验道路只用于隔离的演示评估，并会标记为不可正式推荐或发布。油耗统一标为估算，司机实测值单独保留。</p>
      <p v-if="error || businessError" class="alert error" role="alert">{{ error || businessError }}</p>
      <p v-if="notice" class="alert success" role="status">{{ notice }}</p>
      <form v-if="canReviewSamples" class="business-form inline-form" @submit.prevent="match">
        <label>复核已完成运输
          <select v-model="taskId" required>
            <option value="" disabled>选择完成任务</option>
            <option v-for="t in businessTasks.filter(t => t.status === 'COMPLETED')" :key="t.id" :value="String(t.id)">{{ t.code }}{{ t.demoMode ? ' · DEMO_MODE' : '' }} · {{ t.originCity }} → {{ t.destinationCity }} · {{ label(t.sampleStatus) }}</option>
          </select>
        </label>
        <button class="outline-button" :disabled="busy || !taskId" type="submit">执行道路匹配复核</button>
      </form>
      <p v-if="analysis" class="source-note">{{ analysis.demoMode ? 'DEMO_MODE · ' : '' }}{{ label(analysis.sampleStatus) }} · {{ analysis.reason }}</p>
      <form class="business-form inline-form" @submit.prevent="evaluate">
        <label>计划基础路线
          <select v-model="planId" required>
            <option value="" disabled>选择计划路线</option>
            <option v-for="r in businessRoutes" :key="r.databaseId" :value="String(r.databaseId)">{{ r.name }} v{{ r.version }} · {{ label(r.verificationStatus) }}</option>
          </select>
        </label>
        <button class="primary-button" :disabled="busy || !planId" type="submit">保存一轮同 OD 评估</button>
      </form>
      <p v-if="selectedPlan" class="source-note">
        {{ selectedPlan.originCity }} → {{ selectedPlan.destinationCity }}：任务清单有 {{ completedSameOdTasks.length }} 条已完成运输，其中 {{ taskFlaggedValidCount }} 条状态标为有效、{{ demoTaskCount }} 条为演示任务。任务状态标记不等于评估样本；仍需最新的服务端轨迹匹配、合理反馈和路线约束全部通过。
      </p>
    </section>

    <section class="section">
      <label class="business-filter">评估历史
        <select v-model="selected">
          <option v-for="e in history" :key="e.id" :value="e.id">#{{ e.id }} · {{ e.snapshot.plan.originCity }} → {{ e.snapshot.plan.destinationCity }} · {{ time(e.createdAt) }}</option>
        </select>
      </label>
      <div v-if="!current" class="empty-panel"><strong>暂无持久化评估</strong><p>选择计划路线开始评估；没有合格样本时会保存无推荐原因。</p></div>
      <template v-else>
        <p v-if="current.snapshot.demoMode" class="alert info">DEMO_MODE - 演示评估。{{ current.snapshot.demoSampleCount ?? 0 }} 个演示样本；结果不计入正式推荐，候选不能直接发布。</p>
        <div class="business-feedback">
          <h3>{{ current.snapshot.label }}</h3>
          <p>{{ current.snapshot.reason }}</p>
          <small>{{ current.snapshot.demoMode ? `${current.snapshot.demoSampleCount ?? 0} 个演示样本` : `${current.snapshot.validSampleCount} 次有效运输` }} · {{ current.snapshot.estimateSource || '尚无可评分的同源估算集合' }} · {{ current.snapshot.limitation }}</small>
        </div>
        <TransportMap title="计划、实际匹配与候选道路" :plan="current.snapshot.plan" :actual="current.snapshot.actualSamples?.[0]?.matchedRoute" :candidate="current.snapshot.candidate" :task-id="current.snapshot.actualSamples?.[0]?.taskCode" :trajectory-url="current.snapshot.actualSamples?.[0]?.taskId ? `/api/business/tasks/${current.snapshot.actualSamples[0].taskId}/trajectory` : undefined" />
        <div v-if="current.snapshot.scores?.length" class="compare-grid">
          <article v-for="s in current.snapshot.scores" :key="s.routeId" class="compare-card">
            <h3>{{ s.routeId === 'PLAN' ? '计划路线' : '贪心候选' }}</h3>
            <p>综合分 {{ s.compositeScore.toFixed(2) }}</p>
            <p>耗时 {{ s.travelTimeScore.toFixed(2) }} · 里程 {{ s.distanceScore.toFixed(2) }} · 油耗 {{ s.fuelScore.toFixed(2) }}</p>
            <template v-for="m in current.snapshot.metrics?.filter(m => m.routeId === s.routeId)" :key="m.routeId"><small>同源估算：{{ m.estimatedMinutes.toFixed(2) }} min · {{ m.estimatedDistanceKm.toFixed(2) }} km · {{ m.estimatedFuelLiters.toFixed(2) }} L</small></template>
          </article>
        </div>
        <div v-if="current.snapshot.candidate && current.snapshot.demoMode" class="source-note">候选 v{{ current.snapshot.candidate.version }}，状态 {{ label(current.snapshot.candidate.verificationStatus) }}。这是演示候选，待核验状态仍保留；如需转正式路线，请创建新版本并重新补齐逐段货车依据。</div>
        <div v-else-if="current.snapshot.candidate" class="source-note">候选 v{{ current.snapshot.candidate.version }}，状态 {{ label(current.snapshot.candidate.verificationStatus) }}；{{ current.snapshot.candidateCombinationActuallyDriven ? '道路组合已被完整观测' : '新道路组合，油耗为估算' }}。前往路线核验页复核发布，原计划与历史版本保留。</div>
        <ul v-if="current.snapshot.selectionReasons?.length"><li v-for="reason in current.snapshot.selectionReasons" :key="reason">{{ reason }}</li></ul>
        <details><summary>评分边界、权重及实测样本快照</summary><pre class="analysis-json">{{ JSON.stringify({ bounds: current.snapshot.normalizationBounds, weights: current.snapshot.weights, actualSamples: current.snapshot.actualSamples }, null, 2) }}</pre></details>
      </template>
    </section>
  </div>
</template>
