import { computed, inject, ref } from 'vue'
import type { InjectionKey } from 'vue'
import { sessionFetch } from './auth'
import type { Portal } from './auth'
import { businessApi } from './business'
import type { BusinessTask, BusinessRoute, BusinessDriver, Vehicle } from './business'

type ServiceStatus = { name: string; endpoint: string; status: string; detail?: string }
type Segment = { sequence: number; fromCity: string; toCity: string; roadName: string; distanceKm: number; estimatedMinutes: number; truckAccessStatus: string; geometryJson?: string | null; mapSource?: string }
export type Route = { id: string; version: number; originCity: string; destinationCity: string; citySequence: string[]; segments: Segment[]; verificationStatus: string; estimateSource: string }
type Difference = { actualOnlyCities: string[]; planOnlyCities: string[]; different: boolean }
type Task = { code: string; originCity: string; destinationCity: string; routeId: string; status: string; sampleStatus: string; actualDistanceKm: number | null; actualFuelLiters: number | null; actualElapsedMinutes: number | null; routeDifference: Difference | null; simulatedTrajectory: boolean }
type Recommendation = { status: string; label: string; evidenceLevel: string; validSampleCount: number; reason: string; planRouteId: string; candidateRouteId: string }
type Overview = { weights: { travelTime: number; distance: number; fuel: number }; routes: Route[]; tasks: Task[]; scores: unknown[]; recommendation: Recommendation; kafka: ServiceStatus; flink: ServiceStatus; mysql: ServiceStatus; mongodb: ServiceStatus; hdfs: ServiceStatus }
type ComponentHealth = { name: string; endpoint: string; status: string; detail: string }
type OdStatistic = { originCity: string; destinationCity: string; taskCount: number; completedFeedbackCount?: number; averageActualElapsedMinutes: number | null; averageActualDistanceKm: number | null; averageActualFuelLiters: number | null; anomalyCount: number; excludedSampleCount?: number; simulatedTaskCount?: number }
type Statistics = { source: string; records: OdStatistic[]; archiveSource: string; archiveIncludesTaskStatistics: boolean; archivePath: string; note: string; dataStatus: string; runId?: string; jobId?: string; completedAt?: string; inputPath?: string; inputSha256?: string; executionMode?: string }
type TelemetryEvidence = { sequence: number | null; validationStatus: string | null; routeMatchStatus: string | null; sourceType: string | null; deviationStatus: string | null }
type Telemetry = { taskId: string; status: string; database: string; pointCount: number; anomalyCount: number; rejectedCount: number; evidenceAvailable: boolean; recentEvidence: TelemetryEvidence[]; detail: string }
type RouteAttempt = { id: number; originCity: string | null; destinationCity: string | null; originCoordinate: string | null; destinationCoordinate: string | null; responseStatus: string; responseExcerpt: string | null; verificationStatus: string; failureReason: string; requestedBy: number; requestedAt: string }


export function createWorkspace(portal: Portal) {
  const businessTasks = ref<BusinessTask[]>([])
  const businessRoutes = ref<BusinessRoute[]>([])
  const vehicles = ref<Vehicle[]>([])
  const drivers = ref<BusinessDriver[]>([])
  const businessError = ref('')
  const businessLoaded = ref(false)
  async function loadBusiness() {
    businessError.value = ''
    try {
      const [tasks, routes, fleet, people] = await Promise.all([
        businessApi<BusinessTask[]>('/tasks'), businessApi<BusinessRoute[]>('/routes'),
        businessApi<Vehicle[]>('/vehicles'), portal === 'driver' ? Promise.resolve([]) : businessApi<BusinessDriver[]>('/drivers'),
      ])
      businessTasks.value = tasks; businessRoutes.value = routes; vehicles.value = fleet; drivers.value = people
      businessLoaded.value = true
    } catch (failure) { businessError.value = failure instanceof Error ? failure.message : '业务数据读取失败。' }
  }
  const overview = ref<Overview | null>(null)
  const components = ref<ComponentHealth[]>([])
  const statistics = ref<Statistics | null>(null)
  const telemetry = ref<Telemetry | null>(null)
  const routeAttempts = ref<RouteAttempt[]>([])
  const selectedRouteId = ref('PLAN-BJ-SH')
  const busyTask = ref('')
  const errorMessage = ref('')
  const notice = ref('')
  const selectedRoute = computed(() => overview.value?.routes.find(route => route.id === selectedRouteId.value) ?? null)
  const planRoute = computed(() => overview.value?.routes.find(route => route.id === 'PLAN-BJ-SH') ?? null)
  const candidateRoute = computed(() => overview.value?.routes.find(route => route.id === 'CANDIDATE-BJ-SH-01') ?? null)
  const completedTask = computed(() => overview.value?.tasks.find(task => task.code === 'DEMO-2026-001') ?? null)
  const pendingTask = computed(() => overview.value?.tasks.find(task => task.status === 'PENDING_ACCEPTANCE' || task.status === 'IN_TRANSIT') ?? null)
  const pendingTaskRoute = computed(() => overview.value?.routes.find(route => route.id === pendingTask.value?.routeId) ?? null)
  async function loadOverview() {
    await loadBusiness()
    errorMessage.value = ''
    try {
      const response = await sessionFetch('/api/demo/overview')
      if (!response.ok) throw new Error('运输数据加载失败，请检查后端服务。')
      overview.value = await response.json() as Overview
    } catch (error) {
      errorMessage.value = error instanceof Error ? error.message : '运输数据暂不可用。'
    }
    if (portal !== 'admin') return
    const sections = await Promise.allSettled([
      readJson<{ components: ComponentHealth[] }>('/api/health/components'),
      readJson<Statistics>('/api/statistics/offline'),
      readJson<Telemetry>('/api/telemetry/gps'),
      readJson<RouteAttempt[]>('/api/routes/truck-quotes?limit=10'),
    ])
    const [health, stats, gps, attempts] = sections
    if (health.status === 'fulfilled') components.value = health.value.components
    if (stats.status === 'fulfilled') statistics.value = stats.value
    if (gps.status === 'fulfilled') telemetry.value = gps.value
    if (attempts.status === 'fulfilled') routeAttempts.value = attempts.value
    if (sections.some(section => section.status === 'rejected')) {
      errorMessage.value = '部分数据暂不可用，请检查服务状态后刷新。'
    }
  }

  async function readJson<T>(url: string): Promise<T> {
    const response = await sessionFetch(url)
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    return response.json() as Promise<T>
  }

  async function changeTask(task: Task, action: 'accept' | 'cancel' | 'complete') {
    const prompt = action === 'accept' ? '确认接受该运输任务？' : action === 'cancel' ? '确认取消该运输任务？' : '确认司机已送达，并提交演示实测数据？'
    if (!window.confirm(prompt)) return
    busyTask.value = task.code
    try {
      const body = action === 'complete' ? JSON.stringify({ actualDistanceKm: 902, actualFuelLiters: 280, routeDifferenceRecoverable: true }) : undefined
      const headers: Record<string, string> = {}
      if (body) headers['Content-Type'] = 'application/json'
      const response = await sessionFetch(`/api/demo/tasks/${task.code}/${action}`, { method: 'POST', headers, body })
      const payload = await response.json().catch(() => null)
      if (!response.ok) throw new Error(payload?.message ?? `HTTP ${response.status}`)
      notice.value = `${task.code} 已更新为 ${label(payload.status)}`
      await loadOverview()
    } catch (error) { notice.value = error instanceof Error ? error.message : '状态更新失败' }
    finally { busyTask.value = '' }
  }

  async function startSimulation(task: Task) {
    if (!window.confirm('确认启动该任务的模拟 GPS 轨迹并发布到 Kafka？')) return
    busyTask.value = task.code
    try {
      const response = await sessionFetch(`/api/simulations/tasks/${task.code}/start`, {
        method: 'POST',
        })
      const payload = await response.json().catch(() => null)
      if (!response.ok) throw new Error(payload?.message ?? `HTTP ${response.status}`)
      notice.value = `${task.code} 已发布 ${payload.publishedCount} 条 ${payload.sourceType} GPS 事件到 ${payload.topic}`
    } catch (error) { notice.value = error instanceof Error ? error.message : '模拟 GPS 发布失败' }
    finally { busyTask.value = '' }
  }

  return { businessTasks, businessRoutes, vehicles, drivers, businessError, businessLoaded, loadBusiness,
    overview, components, statistics, telemetry, routeAttempts, selectedRouteId,
    selectedRoute, planRoute, candidateRoute, completedTask, pendingTask, pendingTaskRoute,
    busyTask, errorMessage, notice, loadOverview, changeTask, startSimulation }
}

function label(status: string) {
  return ({ DRAFT: '草稿', PENDING: '待核验', REJECTED: '已拒绝', PENDING_ASSIGNMENT: '待分配', PENDING_VERIFICATION: '待核验', VERIFIED: '已核验', CANDIDATE: '演示候选', COMPLETED: '已完成', PENDING_ACCEPTANCE: '待接单', IN_TRANSIT: '运输中', CANCELLED: '已取消', VALID: '有效样本', INVALID: '无效样本', PENDING_MATCH: '待匹配', FAILED: '核验失败', NO_RECOMMENDATION: '暂无推荐', PRELIMINARY: '初步推荐', DEMO_ONLY: '仅演示' } as Record<string, string>)[status] ?? status
}

export { label }
export const workspaceKey: InjectionKey<ReturnType<typeof createWorkspace>> = Symbol('workspace')
export function useWorkspace() {
  const workspace = inject(workspaceKey)
  if (!workspace) throw new Error('Business page must be inside an authenticated workspace.')
  return workspace
}
