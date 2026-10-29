import { sessionFetch } from './auth'
import type { CoordinateSystem, MapCoordinate } from './map-data'
import { gcj02 } from './road-geometry'
export type AmapOverlay = { setPosition?(position: number[]): void; on?(event: string, callback: () => void): void }
export type AmapMap = { add(overlay: AmapOverlay | AmapOverlay[]): void; remove(overlay: AmapOverlay | AmapOverlay[]): void; setFitView(overlays?: AmapOverlay[], immediately?: boolean, padding?: number[], maxZoom?: number): void; destroy(): void; resize(): void }
type Options = Record<string, unknown>
export type AmapSdk = { Map: new (container: HTMLElement, options: Options) => AmapMap; Polyline: new (options: Options) => AmapOverlay; CircleMarker: new (options: Options) => AmapOverlay; Marker: new (options: Options) => AmapOverlay; Text: new (options: Options) => AmapOverlay; InfoWindow: new (options: Options) => { open(map: AmapMap, position: number[]): void } }
declare global { interface Window { AMap?: AmapSdk; _AMapSecurityConfig?: { securityJsCode: string } } }
let sdkPromise: Promise<AmapSdk | null> | undefined
/** Convert a latitude/longitude pair into the GCJ02 longitude/latitude format used by AMap. */
export function amapPosition(position: MapCoordinate, coordinateSystem: CoordinateSystem = 'WGS84'): number[] {
  const [latitude, longitude] = coordinateSystem === 'WGS84' ? gcj02(position[1], position[0]) : position
  return [longitude, latitude]
}
export function loadAmap(): Promise<AmapSdk | null> {
  if (sdkPromise) return sdkPromise
  sdkPromise = (async () => {
    const response = await sessionFetch('/api/map/configuration')
    if (!response.ok) throw new Error('地图配置读取失败。')
    const config = await response.json() as { provider: string; browserKey?: string; securityJsCode?: string }
    if (config.provider !== 'AMAP' || !config.browserKey) return null
    const browserKey = config.browserKey
    if (window.AMap) return window.AMap
    if (config.securityJsCode) window._AMapSecurityConfig = { securityJsCode: config.securityJsCode }
    return new Promise<AmapSdk>((resolve, reject) => {
      const script = document.createElement('script')
      const timeout = window.setTimeout(() => { script.remove(); reject(new Error('高德 SDK 加载超时，请检查 JavaScript Key、来源白名单与网络。')) }, 10000)
      script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(browserKey)}`
      script.referrerPolicy = 'strict-origin-when-cross-origin'
      script.onload = () => { clearTimeout(timeout); if (window.AMap) resolve(window.AMap); else reject(new Error('高德 SDK 未就绪，请检查 Key 和安全配置。')) }
      script.onerror = () => { clearTimeout(timeout); reject(new Error('高德 SDK 加载失败，请检查网络和 JavaScript Key。')) }
      document.head.appendChild(script)
    })
  })()
  return sdkPromise
}
