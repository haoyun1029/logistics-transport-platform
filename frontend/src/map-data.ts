export type CoordinateSystem = 'WGS84' | 'GCJ02'
export type MapCoordinate = [number, number] // latitude, longitude; fixture coordinates, not surveyed road geometry.
export type MapPoint = {
  eventId: string; sequence: number; latitude: number; longitude: number; speedKmh: number
  eventTime: string; processedAt: string; sourceType: string; routeMatchStatus: string; anomalies: string[]; runId?: string
  coordinateSystem?: CoordinateSystem
}
export type Trajectory = {
  taskId: string; status: string; source: string; fetchedAt: string; truncated: boolean; points: MapPoint[]; detail: string
}

// Same city-centre fixture positions as the existing GPS simulator. No road geometry is inferred.
export const cityCoordinates: Record<string, MapCoordinate> = {
  北京: [39.9042, 116.4074], 天津: [39.0842, 117.2009], 济南: [36.6512, 117.1201],
  徐州: [34.2058, 117.2841], 南京: [32.0603, 118.7969], 上海: [31.2304, 121.4737], 沧州: [38.3045, 116.8387],
  广州: [23.1291, 113.2644], 清远: [23.6818, 113.0560], 韶关: [24.8104, 113.5972],
  郴州: [25.7705, 113.0149], 长沙: [28.2282, 112.9388], 武汉: [30.5928, 114.3055],
  成都: [30.5728, 104.0668], 德阳: [31.1270, 104.3979], 绵阳: [31.4675, 104.6791],
  广元: [32.4355, 105.8434], 汉中: [33.0676, 107.0233], 西安: [34.3416, 108.9398],
}

export function citySections(cities: string[]) {
  const sections: MapCoordinate[][] = []
  let current: MapCoordinate[] = []
  for (const city of cities) {
    const coordinate = cityCoordinates[city]
    if (coordinate) current.push(coordinate)
    else { if (current.length) sections.push(current); current = [] }
  }
  if (current.length) sections.push(current)
  return sections
}

export function validMapPoint(point: MapPoint) {
  return Number.isFinite(point.latitude) && Math.abs(point.latitude) <= 90
    && Number.isFinite(point.longitude) && Math.abs(point.longitude) <= 180
    && Number.isFinite(point.speedKmh) && point.speedKmh >= 0
    && Number.isInteger(point.sequence) && point.sequence > 0
    && Number.isFinite(Date.parse(point.eventTime))
    && (!point.coordinateSystem || point.coordinateSystem === 'WGS84' || point.coordinateSystem === 'GCJ02')
}

// A sequence gap or a new simulation run starts a new line. Missing points are never silently bridged.
export function trackPointSections(points: MapPoint[]) {
  const sections: MapPoint[][] = []
  let current: MapPoint[] = []
  let previous: MapPoint | undefined
  for (const point of points) {
    if (!validMapPoint(point)) { if (current.length) sections.push(current); current = []; previous = undefined; continue }
    if (previous && (point.runId !== previous.runId || point.sequence !== previous.sequence + 1 || Date.parse(point.eventTime) <= Date.parse(previous.eventTime))) {
      if (current.length) sections.push(current)
      current = []
    }
    current.push(point)
    previous = point
  }
  if (current.length) sections.push(current)
  return sections
}

export function latestSimulationRun(points: MapPoint[]) {
  let latestRunId: string | undefined
  for (const point of points) if (point.runId) latestRunId = point.runId
  return latestRunId ? points.filter(point => point.runId === latestRunId) : points
}

export function trackSections(points: MapPoint[]) {
  return trackPointSections(points).map(section => section.map(point => [point.latitude, point.longitude] as MapCoordinate))
}
