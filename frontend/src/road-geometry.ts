import type { MapCoordinate } from './map-data'
// OSM uses WGS84. Convert explicitly labelled GCJ02 geometry for this preview only.
function offset(lon: number, lat: number): [number, number] {
  if (lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271) return [0, 0]
  const x = lon - 105, y = lat - 35, pi = Math.PI
  let dlat = -100 + 2 * x + 3 * y + .2 * y * y + .1 * x * y + .2 * Math.sqrt(Math.abs(x))
  let dlon = 300 + x + 2 * y + .1 * x * x + .1 * x * y + .1 * Math.sqrt(Math.abs(x))
  const common = (20 * Math.sin(6 * x * pi) + 20 * Math.sin(2 * x * pi)) * 2 / 3
  dlat += common + (20 * Math.sin(y * pi) + 40 * Math.sin(y / 3 * pi)) * 2 / 3 + (160 * Math.sin(y / 12 * pi) + 320 * Math.sin(y * pi / 30)) * 2 / 3
  dlon += common + (20 * Math.sin(x * pi) + 40 * Math.sin(x / 3 * pi)) * 2 / 3 + (150 * Math.sin(x / 12 * pi) + 300 * Math.sin(x / 30 * pi)) * 2 / 3
  const rad = lat / 180 * pi, magic = 1 - .006693421622965943 * Math.sin(rad) ** 2
  return [dlon * 180 / (6378245 / Math.sqrt(magic) * Math.cos(rad) * pi), dlat * 180 / ((6378245 * (1 - .006693421622965943)) / (magic * Math.sqrt(magic)) * pi)]
}
export function wgs84(lon: number, lat: number, system: string): MapCoordinate {
  if (system === 'WGS84') return [lat, lon]
  if (system !== 'GCJ02') throw new Error('未知坐标系')
  let x = lon, y = lat
  for (let i = 0; i < 4; i++) { const [dx, dy] = offset(x, y); x -= x + dx - lon; y -= y + dy - lat }
  return [y, x]
}
export function gcj02(lon: number, lat: number): MapCoordinate { const [dx, dy] = offset(lon, lat); return [lat + dy, lon + dx] }
export function roadCoordinates(value?: string | null): MapCoordinate[] {
  if (!value) return []
  try {
    const data = JSON.parse(value)
    if (!['WGS84', 'GCJ02'].includes(data.coordinateSystem) || !Array.isArray(data.coordinates) || data.coordinates.length < 2) return []
    if (!data.coordinates.every((pair: unknown) => Array.isArray(pair) && pair.length === 2 && Number.isFinite(pair[0]) && Number.isFinite(pair[1]) && Math.abs(pair[0]) <= 180 && Math.abs(pair[1]) <= 90)) return []
    return data.coordinates.map(([lon, lat]: [number, number]) => wgs84(lon, lat, data.coordinateSystem))
  } catch { return [] }
}
