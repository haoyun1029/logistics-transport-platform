import { sessionFetch } from './auth'
import type { Route } from './workspace'
export type BusinessTask = { id: number; code: string; routeId: number; originCity: string; destinationCity: string; routeName: string; cargoDescription: string | null; cargoWeightTons: number | null; plannedDistanceKm: number | null; plannedDurationMinutes: number | null; pricePerKm: number | null; deliveryDeadline: string | null; details: string | null; driverId: number | null; driverName: string | null; vehicleId: number | null; plateNumber: string | null; status: string; startedAt: string | null; completedAt: string | null; createdAt: string; sourceType: string; demoMode: boolean; sampleStatus: string; exclusionReason: string | null; actualDistanceKm: number | null; actualFuelLiters: number | null; comment: string | null }
export type Vehicle = { id: number; plateNumber: string; vehicleType: string; energyType: string | null; totalWeightTons: number | null; maxLoadTons: number | null; lengthMeters: number | null; widthMeters: number | null; heightMeters: number | null; axleCount: number | null; hasEtc: boolean | null; fuelModelLitersPer100km: number | null; driverId: number | null; driverName: string | null; status: string; nextServiceOn: string | null }
export type BusinessDriver = { id: number; username: string; displayName: string; phone: string | null; busy: boolean }
export type BusinessRoute = Route & { databaseId: number; name: string; parentRouteId: number | null; segments: BusinessSegment[] }
export type BusinessSegment = { id: number; sequence: number; fromCity: string; toCity: string; roadName: string; roadType: string; distanceKm: number; estimatedMinutes: number; mapSource: string; mapRequestJson: string; mapResponseReference: string; geometryJson: string | null; truckAccessStatus: string; truckEvidenceSource: string | null; truckEvidenceReference: string | null; verifiedAt: string | null }
export type Profile = { username: string; displayName: string; phone: string | null; role: string }
export async function businessApi<T>(path: string, body?: unknown): Promise<T> {
  const response = await sessionFetch(`/api/business${path}`, body === undefined ? {} : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
  const text = await response.text()
  const payload = text ? JSON.parse(text) : undefined
  if (!response.ok) throw new Error(payload?.message ?? `请求失败（HTTP ${response.status}）`)
  return payload as T
}
export const taskStatus: Record<string, string> = { PENDING_ASSIGNMENT: '待分配', PENDING_ACCEPTANCE: '待接单', IN_TRANSIT: '运输中', COMPLETED: '已完成', CANCELLED: '已取消' }
export const vehicleStatus: Record<string, string> = { AVAILABLE: '空闲', RESERVED: '待发车', IN_TRANSIT: '运输中', MAINTENANCE: '维修中' }
export function time(value: string | null | undefined) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }
export function elapsed(task: BusinessTask) { return task.startedAt && task.completedAt ? ((Date.parse(task.completedAt) - Date.parse(task.startedAt)) / 60000).toFixed(1) : '—' }
export function currency(value: number) { return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(value) }
