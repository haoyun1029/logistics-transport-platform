import { sessionFetch } from './auth'
export type TruckRequest = { origin: string; destination: string; originCity: string; destinationCity: string; strategy: number; vehicleSize: string; loadTons: number; plateNumber: string; heightMeters?: number; widthMeters?: number; ratedPayloadTons?: number; axleCount?: number }
export type GeometryStep = { number: number; roadName: string; instruction: string; coordinates: number[][] }
export type GeometryIssue = { type: string; stepNumber: number; previousStepNumber: number | null; distanceMeters: number | null; from: number[] | null; to: number[] | null }
export type RoadMode = 'ROAD_GEOMETRY' | 'TRUCK' | 'OPEN_ROAD'
export type RoadOptions = { via: string[]; routeIndex: number }
export type TruckQuote = { originCity: string; destinationCity: string; requestParameters: Record<string,string>; distanceKm: number; durationMinutes: number; rawResponse: string; requestedAt: string; verificationStatus: string; dataSource: string; geometry: { coordinateSystem: 'GCJ02' | 'WGS84'; coordinates: number[][] } | null; roadNames: string[]; restriction: number; geometryStatus: string; steps: GeometryStep[]; geometryIssues: GeometryIssue[] }
export type QuoteResult = { quoteId: number; quote: TruckQuote }
export type QuoteHistory = { id: number; originCity: string; destinationCity: string; requestParametersJson: string; responseJson: string; distanceKm: number; durationMinutes: number; dataSource: string; truckAccessStatus: string; requestedAt: string }
export type SegmentPrefill = { fromCity: string; toCity: string; roadName: string; distanceKm: number; estimatedMinutes: number; mapSource: string; mapRequestJson: string; mapResponseReference: string; geometryJson: string }
export async function queryTruckRoute(request: TruckRequest, mode: RoadMode = 'ROAD_GEOMETRY', options: RoadOptions = { via: [], routeIndex: 0 }): Promise<QuoteResult> {
  const body = mode === 'OPEN_ROAD' ? { origin: request.origin, destination: request.destination, originCity: request.originCity, destinationCity: request.destinationCity, ...options } : request
  const response = await sessionFetch(mode === 'TRUCK' ? '/api/routes/truck-quote' : mode === 'OPEN_ROAD' ? '/api/routes/open-road-quote' : '/api/routes/road-geometry-quote', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
  const payload = await response.json()
  if (!response.ok) throw new Error([payload.message ?? `请求失败（HTTP ${response.status}）`, payload.failureReason, payload.attemptId ? `核验记录 #${payload.attemptId}` : ''].filter(Boolean).join(' · '))
  return payload as QuoteResult
}
export async function truckQuoteHistory(): Promise<QuoteHistory[]> {
  const response = await sessionFetch('/api/routes/truck-quote-history?limit=10')
  if (!response.ok) throw new Error(`查询成功记录失败（HTTP ${response.status}）`)
  return response.json()
}
export async function savedQuote(id: number): Promise<QuoteResult> {
  const response = await sessionFetch(`/api/routes/truck-quote-history/${id}`)
  if (!response.ok) throw new Error(response.status === 404 ? '此道路资料记录不存在。' : `读取已保存资料失败（HTTP ${response.status}）`)
  return response.json()
}
export function geometryIssueText(issue: GeometryIssue): string {
  if (issue.type === 'DISCONNECTED') return `步骤 ${issue.previousStepNumber} → ${issue.stepNumber}：接缝约 ${(issue.distanceMeters ?? 0).toFixed(1)} 米`
  return `步骤 ${issue.stepNumber}：${issue.type === 'MISSING_GEOMETRY' ? '缺少折线' : '折线坐标无效'}`
}
export function quotePrefill(result: QuoteResult): SegmentPrefill {
  const q = result.quote
  return { fromCity: q.originCity, toCity: q.destinationCity, roadName: q.roadNames.join(' → '), distanceKm: Number(q.distanceKm.toFixed(2)), estimatedMinutes: Number(q.durationMinutes.toFixed(2)), mapSource: q.dataSource, mapRequestJson: JSON.stringify({ ...q.requestParameters, requestedAt: q.requestedAt }), mapResponseReference: `route_quotes#${result.quoteId}`, geometryJson: q.geometry && q.geometryStatus === 'COMPLETE_PENDING_REVIEW' ? JSON.stringify(q.geometry) : '' }
}
export function restrictionText(value: number): string {
  return ({ '-1': '接口报告：路线上没有限行', '0': '限行结果未知（可能缺少完整车牌）', '1': '接口报告：已规避限行', '2': '起点限行', '3': '途经点在限行区', '4': '途经限行区域', '5': '终点限行' } as Record<string,string>)[String(value)] ?? '限行结果未知'
}
