import assert from 'node:assert/strict'
import { createServer } from 'vite'
import { createSSRApp, defineComponent, h, provide } from 'vue'
import { renderToString } from 'vue/server-renderer'

// Render the actual page components and exercise authentication without changing any accounts.
const server = await createServer({ server: { middlewareMode: true }, appType: 'custom' })
const originalFetch = globalThis.fetch
const originalWindow = globalThis.window
const storage = () => {
  const values = new Map()
  return { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) }
}
const previousSession = globalThis.sessionStorage
const previousLocal = globalThis.localStorage
let checks = 0
try {
  const auth = await server.ssrLoadModule('/src/auth.ts')
  const { createWorkspace, workspaceKey } = await server.ssrLoadModule('/src/workspace.ts')
  const { navigation, resolvePage } = await server.ssrLoadModule('/src/navigation.ts')
  const session = storage()
  const local = storage()
  let redirect = ''
  Object.defineProperty(globalThis, 'sessionStorage', { configurable: true, value: session })
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: local })
  globalThis.window = { location: { replace: url => { redirect = url } } }

  for (const [role, portal] of [['SUPER_ADMIN', 'admin'], ['ADMIN', 'admin'], ['DISPATCHER', 'dispatcher'], ['DRIVER', 'driver']]) {
    assert.equal(auth.portalForRole(role), portal)
    checks++
  }
  assert.throws(() => auth.portalForRole('UNKNOWN'))
  checks++

  local.setItem('logistics-role', 'SUPER_ADMIN')
  auth.saveToken('test-session')
  globalThis.fetch = async (url, options) => {
    assert.equal(url, '/api/auth/me')
    assert.equal(options.headers.get('Authorization'), 'Bearer test-session')
    return new Response(JSON.stringify({ id: 9, username: 'fixture', displayName: '测试司机', role: 'DRIVER' }))
  }
  assert.equal(auth.portalForRole((await auth.currentAccount()).role), 'driver')
  checks++

  globalThis.fetch = async () => new Response('', { status: 401 })
  await assert.rejects(auth.currentAccount())
  assert.equal(redirect, '/login?expired=1')
  assert.equal(auth.readToken(), '')
  assert.equal(local.getItem('logistics-role'), null)
  checks++

  auth.saveToken('test-session')
  redirect = ''
  globalThis.fetch = async () => new Response(JSON.stringify({ code: 'PASSWORD_CHANGE_REQUIRED', message: 'Change password first' }), { status: 403 })
  await assert.rejects(auth.sessionFetch('/api/business/tasks'), /临时密码/)
  assert.equal(redirect, '/change-password')
  assert.equal(auth.readToken(), 'test-session', 'Keep the restricted session so the driver can change the temporary password')
  checks++
  redirect = ''
  globalThis.fetch = async () => new Response(JSON.stringify({ code: 'FORBIDDEN' }), { status: 403 })
  assert.equal((await auth.sessionFetch('/api/business/users')).status, 403)
  assert.equal(redirect, '', 'An ordinary permission error must not trigger password recovery')
  checks++

  const forcedPasswordPage = (await server.ssrLoadModule('/src/pages/ChangePasswordPage.vue')).default
  const forcedHtml = await renderToString(createSSRApp(forcedPasswordPage, {
    account: { id: 9, username: 'fixture', displayName: '测试司机', role: 'DRIVER', passwordChangeRequired: true },
  }))
  assert.ok(forcedHtml.includes('请先修改临时密码'))
  assert.ok(forcedHtml.includes('临时密码'))
  assert.ok(forcedHtml.includes('确认新密码'))
  assert.ok(forcedHtml.includes('保存新密码并重新登录'))
  checks++

  globalThis.fetch = async (url, options) => {
    assert.equal(url, '/api/auth/logout')
    assert.equal(options.method, 'POST')
    return new Response('', { status: 200 })
  }
  await auth.signOut()
  assert.equal(auth.readToken(), '')
  assert.equal(redirect, '/login')
  checks++

  const markers = {
    UsersPage: 'USER DIRECTORY',
    AdminAccountsPage: 'USER DIRECTORY',
    PolicyPage: 'SCORING AND DETECTION POLICY', BusinessOptimizationPage: 'PERSISTED OPTIMIZATION EVIDENCE',
    BusinessAdminHomePage: 'BUSINESS DASHBOARD', BusinessDispatcherHomePage: 'DISPATCH CENTER', BusinessDriverHomePage: 'BUSINESS DASHBOARD',
    BusinessRoutesPage: 'DATABASE ROUTES', AdminVehiclesPage: 'BUSINESS FLEET', BusinessVehiclesPage: 'BUSINESS FLEET',
    BusinessAdminTasksPage: 'PERSISTENT TRANSPORT', BusinessDispatchPage: 'PERSISTENT TRANSPORT', BusinessDriverPage: 'PERSISTENT TRANSPORT', BusinessHistoryPage: 'PERSISTENT TRANSPORT',
    BusinessMonitoringPage: 'BUSINESS MONITORING', ProfilePage: 'MY PROFILE',
    BusinessTaskSchedulingPage: 'TASK SCHEDULING', DriverVehicleStatusPage: 'VEHICLE STATUS',
    AdminPage: 'QUICK ACCESS', AdminRoutesPage: 'ROUTE CONTROL',
    AdminVerificationPage: 'VERIFICATION AUDIT', AdminOptimizationPage: 'RECOMMENDATION EVIDENCE',
    AdminDriversPage: 'ACCESS CONTROL', AdminStatisticsPage: 'HADOOP MAPREDUCE',
    AdminTelemetryPage: 'MONGODB EVIDENCE', AdminSystemPage: 'PLATFORM STATUS',
    DispatcherPage: 'QUICK ACCESS', DispatcherVehiclesPage: 'FLEET STATUS',
    DispatcherTasksPage: 'TASK DISPATCH', DispatcherMonitoringPage: 'TRANSPORT MONITORING',
    DriverPage: 'QUICK ACCESS', DriverTasksPage: 'CURRENT TASK', DriverHistoryPage: 'DRIVER FEEDBACK',
  }
  async function renderPage(portal, file, prepare = () => {}) {
    const page = (await server.ssrLoadModule(`/src/pages/${file}.vue`)).default
    const workspace = createWorkspace(portal)
    prepare(workspace)
    const app = createSSRApp(defineComponent({ setup() {
      provide(workspaceKey, workspace)
      return () => h(page)
    } }))
    return renderToString(app)
  }
  for (const [portal, items] of Object.entries(navigation)) {
    assert.equal(new Set(items.map(item => item.path)).size, items.length)
    assert.equal(resolvePage(portal, `/${portal}`), items[0], `${portal} root should open its primary page`)
    if (portal === 'dispatcher') assert.equal(items[0].component, 'BusinessDispatcherHomePage')
    else assert.ok(items.every(item => !item.component.includes('HomePage')), `${portal} must not expose an unintended dashboard page`)
    assert.ok(items.every(item => !item.path.includes('demo')), `${portal} must not expose a demo-only page`)
    assert.equal(resolvePage(portal, `/${portal}/missing`), undefined)
    assert.equal(resolvePage(portal, '/login'), undefined)
    for (const other of Object.keys(navigation).filter(key => key !== portal)) {
      for (const item of navigation[other]) assert.equal(resolvePage(portal, item.path), undefined)
    }
    checks++
    for (const item of items) {
      assert.equal(resolvePage(portal, item.path), item)
      assert.equal(resolvePage(portal, `${item.path}/`), item)
      assert.ok(!item.path.includes('#'))
      const html = await renderPage(portal, item.component)
      assert.ok(html.includes(markers[item.component]), `${item.path} must render its own content`)
      const allowed = new Set([markers[item.component]])
      if (item.component === 'AdminAccountsPage') allowed.add('ACCESS CONTROL')
      if (item.component.startsWith('Business') && item.component.includes('HomePage')) allowed.add('QUICK ACCESS')
      if (item.component === 'DispatcherPage') allowed.add('FLEET STATUS') // Required home fleet summary.
      if (item.component === 'BusinessDispatcherHomePage') allowed.add('FLEET STATUS')
      if (item.component === 'DispatcherVehiclesPage') allowed.add('BUSINESS FLEET')
      for (const marker of new Set(Object.values(markers))) {
        if (!allowed.has(marker)) assert.ok(!html.includes(marker), `${item.path} must not include ${marker}`)
      }
      checks++
    }
  }
  assert.ok(navigation.admin.some(item => item.path === '/admin/optimization' && item.component === 'BusinessOptimizationPage'))
  assert.equal(resolvePage('admin', '/admin/routes')?.component, 'BusinessRoutesPage', 'administrator route management must be directly reachable')
  assert.equal(resolvePage('dispatcher', '/dispatcher/vehicles')?.component, 'DispatcherVehiclesPage', 'dispatcher fleet page must be directly reachable')
  assert.ok(!navigation.dispatcher.some(item => item.path.includes('optimization')), 'Path optimization belongs to the administrator portal')
  assert.equal(navigation.dispatcher[1]?.title, '运输任务管理')
  assert.equal(navigation.dispatcher.find(item => item.path === '/dispatcher/scheduling')?.title, '任务调度')
  assert.equal(navigation.driver.find(item => item.path === '/driver/vehicles')?.title, '车辆状态')

  const task = { id: 3, code: 'FIXTURE-TASK', routeId: 7, originCity: '北京', destinationCity: '上海', routeName: '测试路线', status: 'PENDING_ACCEPTANCE', demoMode: true, sampleStatus: 'PENDING_MATCH', sourceType: 'SIMULATED', createdAt: '2026-10-26T00:00:00Z' }
  const prepare = status => workspace => {
    workspace.businessTasks.value = [{ ...task, status }]
    workspace.businessLoaded.value = true
    workspace.overview.value = { routes: [], tasks: [{ ...task, status }] }
  }
  const pending = await renderPage('driver', 'BusinessDriverPage', prepare('PENDING_ACCEPTANCE'))
  assert.ok(pending.includes('确认接单'))
  assert.ok(!pending.includes('二次确认接单'))
  assert.ok(!pending.includes('二次确认送达'))
  const active = await renderPage('driver', 'BusinessDriverPage', prepare('IN_TRANSIT'))
  assert.ok(active.includes('确认送达'))
  assert.ok(active.includes('反馈'))
  assert.ok(!active.includes('开始位置演示'))
  assert.ok(!active.includes('实际里程（km）'))
  assert.ok(!active.includes('申请送达时间'))
  assert.ok(!active.includes('二次确认接单'))
  const dispatch = await renderPage('dispatcher', 'DispatcherTasksPage', prepare('PENDING_ACCEPTANCE'))
  assert.ok(dispatch.includes('取消任务'))
  const monitor = await renderPage('dispatcher', 'DispatcherMonitoringPage', prepare('IN_TRANSIT'))
  assert.ok(monitor.includes('FIXTURE-TASK'))
  assert.ok(monitor.includes('运输地图监控'))
  assert.ok(monitor.includes('任务路线与轨迹交互地图'))
  const schedulingTask = {
    id: 38, code: 'SCHEDULE-FIXTURE', routeId: 7, originCity: '杭州', destinationCity: '上海', routeName: '路线 7',
    driverId: null, driverName: null, vehicleId: null, plateNumber: null, status: 'PENDING_ASSIGNMENT',
    startedAt: null, completedAt: null, createdAt: '2026-10-26T00:00:00Z', sourceType: 'SIMULATED', demoMode: true,
    sampleStatus: 'PENDING_MATCH', exclusionReason: null, actualDistanceKm: null, actualFuelLiters: null, pricePerKm: null, comment: null,
  }
  const schedulingHtml = await renderPage('dispatcher', 'BusinessTaskSchedulingPage', workspace => {
    workspace.businessTasks.value = [schedulingTask]
    workspace.businessLoaded.value = true
  })
  assert.ok(schedulingHtml.includes('任务调度'))
  assert.ok(schedulingHtml.includes('安排司机和车辆'))
  const manageHtml = await renderPage('dispatcher', 'BusinessDispatchPage')
  assert.ok(manageHtml.includes('运输任务管理'))
  assert.ok(manageHtml.includes('创建待分配任务'))
  assert.ok(manageHtml.includes('每公里单价（元/km）'))
  const historyTasks = [
    { ...schedulingTask, id: 61, code: 'EARNINGS-FIXTURE', status: 'COMPLETED', pricePerKm: 4.5, actualDistanceKm: 100, actualFuelLiters: 20, completedAt: '2026-10-20T10:00:00Z' },
    { ...schedulingTask, id: 62, code: 'NO-RATE-FIXTURE', status: 'COMPLETED', pricePerKm: null, actualDistanceKm: 50, actualFuelLiters: 10, completedAt: '2026-10-21T10:00:00Z' },
  ]
  const historyHtml = await renderPage('driver', 'BusinessHistoryPage', workspace => {
    workspace.businessTasks.value = historyTasks
    workspace.businessLoaded.value = true
  })
  assert.ok(historyHtml.includes('运输收入可视化'))
  assert.ok(historyHtml.includes('450.00'))
  assert.ok(historyHtml.includes('待完善费率'))
  assert.ok(historyHtml.includes('历史任务没有单价时不计入金额'))
  assert.ok(!historyHtml.includes('保存有凭据的单价'), 'drivers may not backfill task rates')
  const adminHistoryHtml = await renderPage('admin', 'BusinessAdminTasksPage', workspace => {
    workspace.businessTasks.value = [...historyTasks].reverse()
    workspace.businessLoaded.value = true
  })
  assert.ok(adminHistoryHtml.includes('补录历史任务单价'))
  assert.ok(adminHistoryHtml.includes('单价来源凭据'))
  assert.ok(adminHistoryHtml.includes('保存有凭据的单价'))
  const dispatchHomeHtml = await renderPage('dispatcher', 'BusinessDispatcherHomePage', workspace => {
    workspace.businessTasks.value = [schedulingTask]
    workspace.vehicles.value = []
    workspace.businessLoaded.value = true
  })
  assert.ok(dispatchHomeHtml.includes('DISPATCH CENTER'))
  assert.ok(dispatchHomeHtml.includes('今日运输任务'))
  assert.ok(dispatchHomeHtml.includes('运输中车辆'))
  assert.ok(dispatchHomeHtml.includes('异常车辆'))
  const realFleetHtml = await renderPage('dispatcher', 'DispatcherVehiclesPage', workspace => {
    workspace.vehicles.value = [{ id: 73, plateNumber: '浙A54321', vehicleType: '重型厢式货车', energyType: '纯电动', totalWeightTons: 18, maxLoadTons: 8, lengthMeters: 8, widthMeters: 2.5, heightMeters: 3.2, axleCount: 2, hasEtc: true, fuelModelLitersPer100km: 0.5, driverId: 8, driverName: '张师傅', status: 'IN_TRANSIT', nextServiceOn: '2026-12-31' }]
    workspace.businessLoaded.value = true
  })
  assert.ok(realFleetHtml.includes('浙A54321'))
  assert.ok(realFleetHtml.includes('纯电动'))
  assert.ok(realFleetHtml.includes('张师傅'))
  assert.ok(!realFleetHtml.includes('京A·D001'), 'dispatcher vehicle page must not use the obsolete fixed demo fleet')
  const vehicleHtml = await renderPage('driver', 'DriverVehicleStatusPage', workspace => {
    workspace.vehicles.value = [{ id: 51, plateNumber: '浙A12345', vehicleType: '厢式货车', maxLoadTons: 8, fuelModelLitersPer100km: 20, status: 'AVAILABLE', nextServiceOn: '2099-05-05' }]
    workspace.businessLoaded.value = true
  })
  assert.ok(vehicleHtml.includes('浙A12345'))
  assert.ok(vehicleHtml.includes('天后需保养'))
  assert.ok(vehicleHtml.includes('不需要司机填写或上传保养记录'))
  assert.ok(!vehicleHtml.includes('保存维护记录'))
  assert.ok(!vehicleHtml.includes('<form'))
  const adminVehiclesHtml = await renderPage('admin', 'AdminVehiclesPage', workspace => {
    workspace.vehicles.value = [{ id: 52, plateNumber: '浙A12346', vehicleType: '厢式货车', maxLoadTons: 8, fuelModelLitersPer100km: 20, status: 'AVAILABLE', nextServiceOn: null }]
    workspace.businessLoaded.value = true
  })
  assert.ok(adminVehiclesHtml.includes('登记本次保养'))
  assert.ok(adminVehiclesHtml.includes('下次保养：未设置'))
  checks += 10

  const bars = (await server.ssrLoadModule('/src/components/MetricBarChart.vue')).default
  const barHtml = await renderToString(createSSRApp(bars, {
    title: 'Missing metrics', subtitle: 'Fixture', source: 'TEST', unit: 'km',
    items: [{ label: 'Measured zero', value: 0 }, { label: 'Not collected', value: null }, { label: 'Recorded value', value: 123.4 }],
  }))
  assert.ok(barHtml.includes('0 km'))
  assert.ok(barHtml.includes('暂无数据'))
  assert.ok(barHtml.includes('123.4 km'))
  assert.ok(!barHtml.includes('NaN'))
  const emptyHtml = await renderToString(createSSRApp(bars, { title: 'Empty', subtitle: '', source: 'TEST', unit: 'km', items: [] }))
  assert.ok(emptyHtml.includes('暂无可绘制的记录'))
  checks++

  const distribution = (await server.ssrLoadModule('/src/components/DistributionChart.vue')).default
  const zeroHtml = await renderToString(createSSRApp(distribution, { title: 'Empty', subtitle: '', source: 'TEST', items: [{ label: 'Empty status', value: 0, color: '#000' }] }))
  assert.ok(zeroHtml.includes('暂无记录'))
  assert.ok(!zeroHtml.includes('NaN'))
  const loadedHtml = await renderToString(createSSRApp(distribution, { title: 'Loaded', subtitle: '', source: 'TEST', items: [{ label: 'Complete', value: 2, color: '#000' }, { label: 'Pending', value: 1, color: '#aaa' }] }))
  assert.ok(loadedHtml.includes('66.7%'))
  assert.ok(loadedHtml.includes('33.3%'))
  checks++

  const routeHtml = await renderPage('driver', 'BusinessDriverPage', workspace => {
    workspace.businessTasks.value = [{ ...task, status: 'PENDING_ACCEPTANCE' }]
    workspace.businessLoaded.value = true
    workspace.businessRoutes.value = [{ databaseId: task.routeId, id: `DB-${task.routeId}`, name: '测试路线', version: 1, parentRouteId: null, originCity: '北京', destinationCity: '上海', verificationStatus: 'PENDING_VERIFICATION', estimateSource: 'TEST_UNVERIFIED', citySequence: ['北京', '上海'], segments: [{ id: 1, sequence: 1, fromCity: '北京', toCity: '上海', roadName: '测试路段', roadType: '测试', distanceKm: 100, estimatedMinutes: 90, mapSource: 'TEST', mapRequestJson: '{}', mapResponseReference: 'test', geometryJson: null, truckAccessStatus: 'PENDING', truckEvidenceSource: null, truckEvidenceReference: null, verifiedAt: null }] }]
  })
  assert.ok(routeHtml.includes('确认接单'))
  assert.ok(routeHtml.includes('反馈'))
  assert.ok(!routeHtml.includes('二次确认接单'))
  assert.ok(routeHtml.includes('运输任务路线地图'))
  checks++

  const { citySections, trackSections, trackPointSections, latestSimulationRun } = await server.ssrLoadModule('/src/map-data.ts')
  const { wgs84, gcj02, roadCoordinates } = await server.ssrLoadModule('/src/road-geometry.ts')
  const { amapPosition } = await server.ssrLoadModule('/src/amap.ts')
  const converted = gcj02(116.4,39.9)
  const recovered = wgs84(converted[1],converted[0],'GCJ02')
  assert.ok(Math.abs(recovered[0]-39.9)<0.000001 && Math.abs(recovered[1]-116.4)<0.000001)
  assert.deepEqual(amapPosition([39.9,116.4],'GCJ02'),[116.4,39.9], 'GCJ02 GPS points must not be converted twice')
  const wgsPosition = amapPosition([39.9,116.4],'WGS84')
  assert.ok(Math.abs(wgsPosition[0] - converted[1]) < 0.000001 && Math.abs(wgsPosition[1] - converted[0]) < 0.000001, 'WGS84 GPS points must be converted for AMap')
  assert.deepEqual(roadCoordinates('{"coordinateSystem":"UNKNOWN","coordinates":[[116,39],[117,40]]}'),[])
  assert.deepEqual(roadCoordinates('{"coordinateSystem":"WGS84","coordinates":[[116,39],[117,40]]}'),[[39,116],[40,117]])
  checks++
  const sections = citySections(['北京', 'Unknown city', '上海'])
  assert.equal(sections.length, 2, 'Unknown coordinates must break a line rather than bridge it')
  assert.ok(sections.every(section => section.length === 1))
  const gps = (sequence, latitude = 23) => ({ sequence, latitude, longitude: 113, speedKmh: 60, eventTime: `2026-10-24T10:0${sequence}:00Z` })
  assert.deepEqual(trackSections([gps(1), gps(2), gps(4)]).map(section => section.length), [2, 1])
  assert.deepEqual(trackSections([gps(1), gps(2), gps(1)]).map(section => section.length), [2, 1])
  assert.deepEqual(trackSections([gps(1), gps(2, NaN), gps(3)]).map(section => section.length), [1, 1])
  assert.deepEqual(trackSections([{ ...gps(1), runId: 'first' }, { ...gps(2), runId: 'second' }]).map(section => section.length), [1, 1])
  const twoRuns = [{ ...gps(1), runId: 'first' }, { ...gps(2), runId: 'first' }, { ...gps(1), runId: 'second' }, { ...gps(2), runId: 'second' }]
  assert.deepEqual(latestSimulationRun(twoRuns).map(point => point.runId), ['second', 'second'], 'Map playback should use the latest simulation run only')
  assert.equal(latestSimulationRun([gps(1), gps(2)]).length, 2, 'Legacy points without run IDs remain visible')
  assert.equal(trackPointSections([{ ...gps(1), coordinateSystem: 'WGS84' }, { ...gps(2), coordinateSystem: 'GCJ02' }]).length, 1, 'Coordinate metadata alone must not split a continuous run')
  assert.equal(trackSections([{ ...gps(1), coordinateSystem: 'OTHER' }]).length, 0, 'Unknown coordinate systems must not be drawn')
  checks++
  const truck = await server.ssrLoadModule('/src/truck-routes.ts')
  globalThis.fetch = async () => new Response(JSON.stringify({ attemptId: 12, message: '核验失败', failureReason: '10012 / INSUFFICIENT_PRIVILEGES' }), { status: 422 })
  await assert.rejects(truck.queryTruckRoute({}), /10012.*核验记录 #12/)
  checks++
  const quoted = { quoteId: 5, quote: { originCity: 'A', destinationCity: 'B', roadNames: ['Road A'], distanceKm: 10, durationMinutes: 15, dataSource: 'AMAP_TRUCK_ROUTE_API_V4', requestParameters: { size: '3' }, requestedAt: '2026-10-24T10:00:00Z', geometryStatus: 'INCOMPLETE_OR_DISCONNECTED', geometry: null } }
  assert.equal(truck.quotePrefill(quoted).geometryJson, '', 'Missing geometry must remain missing when prefilling metrics')
  quoted.quote.geometry = { coordinateSystem: 'GCJ02', coordinates: [[116,39],[117,40]] }
  quoted.quote.geometryStatus = 'COMPLETE_PENDING_REVIEW'
  const draft = truck.quotePrefill(quoted)
  assert.equal(draft.mapResponseReference, 'route_quotes#5')
  assert.equal(JSON.parse(draft.geometryJson).coordinateSystem, 'GCJ02')
  assert.equal(draft.truckAccessStatus, undefined, 'Map query must not approve a road')
  assert.ok(JSON.parse(draft.mapRequestJson).requestedAt)
  checks++
  const truckComponent = (await server.ssrLoadModule('/src/components/TruckRouteQuery.vue')).default
  const truckHtml = await renderToString(createSSRApp(truckComponent, { originCity: 'A', destinationCity: 'B', editable: true }))
  assert.ok(truckHtml.includes('普通驾车道路资料'))
  assert.ok(truckHtml.includes('获取普通道路资料'))
  assert.ok(!truckHtml.includes('已核验路线'))
  checks++
  const roadMaterial = (await server.ssrLoadModule('/src/components/RoadQuoteMaterial.vue')).default
  const restored = { quoteId: 1, quote: { ...quoted.quote, originCity: 'A', destinationCity: 'B', geometry: null, geometryStatus: 'INCOMPLETE_OR_DISCONNECTED', rawResponse: '{}',
    steps: [{ number: 1, roadName: 'Source road A', instruction: 'Source instruction', coordinates: [[116,39],[116.1,39.1]] }, { number: 2, roadName: 'Source road B', instruction: '', coordinates: [[116.1001,39.1],[116.2,39.2]] }],
    geometryIssues: [{ type: 'DISCONNECTED', stepNumber: 2, previousStepNumber: 1, distanceMeters: 8.6, from: [116.1,39.1], to: [116.1001,39.1] }] } }
  globalThis.fetch = async (url, options) => {
    assert.equal(url, '/api/routes/truck-quote-history/1')
    assert.ok(!options.method || options.method === 'GET', 'Saved material preview must not resubmit a map query')
    return new Response(JSON.stringify(restored))
  }
  const saved = await truck.savedQuote(1)
  assert.equal(saved.quoteId, 1); assert.equal(truck.quotePrefill(saved).geometryJson, '')
  assert.equal(truck.geometryIssueText(saved.quote.geometryIssues[0]), '步骤 1 → 2：接缝约 8.6 米')
  checks++
  const previewHtml = await renderToString(createSSRApp(roadMaterial, { result: saved, editable: true }))
  assert.ok(previewHtml.includes('道路资料分步交互地图'))
  assert.ok(previewHtml.includes('接缝约 8.6 米'))
  assert.ok(previewHtml.includes('Source road A'))
  assert.ok(previewHtml.includes('Source road B'))
  assert.ok(previewHtml.includes('仅填入指标，几何保持空缺'))
  const readOnlyHtml = await renderToString(createSSRApp(roadMaterial, { result: saved }))
  assert.ok(!readOnlyHtml.includes('仅填入指标，几何保持空缺'))
  globalThis.fetch = async () => new Response('', { status: 404 })
  await assert.rejects(truck.savedQuote(999), /记录不存在/)
  checks++
  globalThis.fetch = async (url, options) => {
    assert.equal(url, '/api/routes/open-road-quote')
    const body = JSON.parse(options.body)
    assert.deepEqual(body.via, ['120.1,30.1']); assert.equal(body.routeIndex, 1)
    assert.equal(body.loadTons, undefined, 'OSRM car query must not claim to consider a truck profile')
    return new Response(JSON.stringify(restored))
  }
  await truck.queryTruckRoute({ origin: '120,30', destination: '120.2,30.2', originCity: 'A', destinationCity: 'B', loadTons: 10 }, 'OPEN_ROAD', { via: ['120.1,30.1'], routeIndex: 1 })
  checks++
  const openMaterial = { ...restored, quote: { ...restored.quote, dataSource: 'OSRM_OSM_DRIVING_V5', geometry: { coordinateSystem: 'WGS84', coordinates: [[120,30],[120.1,30.1]] }, geometryIssues: [], geometryStatus: 'COMPLETE_PENDING_REVIEW' } }
  const openHtml = await renderToString(createSSRApp(roadMaterial, { result: openMaterial, editable: true }))
  assert.ok(openHtml.includes('OpenStreetMap contributors（ODbL）')); assert.ok(openHtml.includes('WGS84（仅展示时转 GCJ02）'))
  assert.ok(openHtml.includes('仍需核对')); assert.ok(openHtml.includes('需补可靠公开依据'))
  assert.equal(JSON.parse(truck.quotePrefill(openMaterial).geometryJson).coordinateSystem, 'WGS84')
  const rounded = truck.quotePrefill({ ...openMaterial, quote: { ...openMaterial.quote, distanceKm: 54.2758, durationMinutes: 36.193333 } })
  assert.equal(rounded.distanceKm, 54.28); assert.equal(rounded.estimatedMinutes, 36.19, 'Prefill must satisfy the form 0.01 step without altering the raw material')
  checks++
  console.log(`PASS: ${checks} checks (authentication, portal isolation, task actions, charts, map containers, missing geometry and GPS discontinuities).`)
} finally {
  globalThis.fetch = originalFetch
  globalThis.window = originalWindow
  for (const [name, value] of [['sessionStorage', previousSession], ['localStorage', previousLocal]]) {
    if (value === undefined) delete globalThis[name]
    else Object.defineProperty(globalThis, name, { configurable: true, value })
  }
  await server.close()
}

