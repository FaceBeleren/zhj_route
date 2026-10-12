import { buildFixedDailyRouteResult } from './fixedTripTemplate.js'

const clone = value => JSON.parse(JSON.stringify(value))
export const COMPARISON_COLORS = { green: '#16803c', red: '#dc2626', yellow: '#b77908', neutral: '#64748b' }

export function createManualDraft(result) {
  const draft = clone(result)
  draft.routes.forEach((route, r) => route.points.forEach((point, p) => { point.manualToken = `${r}:${p}` }))
  return draft
}

export function moveManualPoint(draft, routeIndex, pointIndex, direction) {
  const route = draft.routes[routeIndex]
  const to = pointIndex + direction
  if (!route || ![-1, 1].includes(direction) || pointIndex < 1 || pointIndex >= route.points.length - 1 || to < 1 || to >= route.points.length - 1) return false
  const movable = point => !['START', 'END'].includes(point.role) && point.weightSource !== 'ANCHOR'
  if (!movable(route.points[pointIndex]) || !movable(route.points[to])) return false
  ;[route.points[pointIndex], route.points[to]] = [route.points[to], route.points[pointIndex]]
  route.points.forEach((point, index) => { point.order = point.orderNum = index + 1 })
  return true
}

export function reorderManualPoint(draft, routeIndex, fromIndex, toIndex) {
  const route = draft.routes[routeIndex]
  if (!route || !Number.isInteger(fromIndex) || !Number.isInteger(toIndex) || fromIndex === toIndex) return false
  const points = route.points
  if (Math.min(fromIndex, toIndex) < 1 || Math.max(fromIndex, toIndex) >= points.length - 1) return false
  // Keep every fixed anchor in place, including anchors inside a trip.
  if (points.slice(Math.min(fromIndex, toIndex), Math.max(fromIndex, toIndex) + 1).some(point => ['START', 'END'].includes(point.role) || point.weightSource === 'ANCHOR')) return false
  const [point] = points.splice(fromIndex, 1)
  points.splice(toIndex, 0, point)
  points.forEach((item, index) => { item.order = item.orderNum = index + 1 })
  return true
}

export function assertManualMembership(original, draft) {
  if (original.routes.length !== draft.routes.length) throw new Error('不能增加或删除趟次')
  const identity = point => {
    const copy = { ...point }
    delete copy.order; delete copy.orderNum
    return JSON.stringify(copy)
  }
  original.routes.forEach((route, index) => {
    const changed = draft.routes[index]
    if (route.vehicleId !== changed.vehicleId || route.tripNo !== changed.tripNo || route.points.length !== changed.points.length) throw new Error('只能趟内挪动点位，不能删除点位或改变趟次归属')
    if (identity(route.points[0]) !== identity(changed.points[0]) || identity(route.points.at(-1)) !== identity(changed.points.at(-1))) throw new Error('起终点不能移动')
    const before = route.points.map(identity).sort()
    const after = changed.points.map(identity).sort()
    if (JSON.stringify(before) !== JSON.stringify(after)) throw new Error('点位数据发生变化，只允许调整顺序')
  })
}

export function buildManualSavePayload(comparison, context, options, scheduleDays = []) {
  const scenario = comparison.scenarios.find(item => item.day === 0) || comparison.scenarios[0]
  if (!scenario?.manual?.result?.routes?.length) throw new Error('请先完成手动路线计算')
  const result = clone(scenario.manual.result)
  const { routes, ...summary } = result
  return {
    mode: 'group', sourceType: 'EDIT', operationType: 'EDIT',
    groupName: `${context.originRouteName || context.title || '路线'} 手动调整方案`,
    unitId: context.unitId, unitName: context.unitName,
    originRouteId: context.originRouteId, originRouteName: context.originRouteName,
    planningStrategy: result.planningStrategy || context.planningStrategy,
    defaultDisplayMode: 'ROAD',
    routes,
    remark: `人工调整保存，不代表优化或可执行认证；所校验场景合计时间窗冲突${comparison.manualConflicts}次。`,
    summary: { ...summary, manualAdjustment: true, timeWindowConflictCount: comparison.manualConflicts,
      comparisonMessage: comparison.verdict.message,
      scenarioChecks: comparison.scenarios.map(item => ({ day: item.day, label: item.label, violations: item.manual.violations, missing: item.manual.missing || 0, distance: item.manual.distance, duration: item.manual.duration })) },
    request: { ...clone(context.request || {}), optimizeOptions: clone(options || {}), scheduleDays: clone(scheduleDays), manualAdjustment: true }
  }
}

export function compareManualMetrics(generated, manual, manualConflicts) {
  const difference = manual.distance - generated.distance
  // Less than one meter / 0.01 minute is treated as equal, not a meaningful improvement.
  const order = Math.abs(difference) >= 1 ? Math.sign(difference) : Math.abs(manual.duration - generated.duration) >= 0.01 ? Math.sign(manual.duration - generated.duration) : 0
  if (order < 0 && manualConflicts > 0) return { generated: 'green', manual: 'yellow', message: '手动路线更优，但时间窗不允许，不能作为可行替代方案' }
  if (order < 0) return { generated: 'red', manual: 'green', message: '手动路线更优且时间窗通过：发现了比当前系统方案更好的候选' }
  if (order > 0) return { generated: 'green', manual: 'red', message: '当前系统路线更优' }
  return { generated: 'neutral', manual: manualConflicts ? 'yellow' : 'neutral', message: manualConflicts ? '里程、耗时相当，但手动路线仍有时间窗冲突' : '两者里程和耗时相当' }
}

export async function buildManualComparison({ original, draft, scheduleDays = [], options, resolveRoad, buildSchedules, isViolation, onProgress = () => {} }) {
  assertManualMembership(original, draft)
  const cache = new Map()
  const roadFor = async points => {
    if (points.some(point => point.longitude == null || point.latitude == null || !Number.isFinite(Number(point.longitude)) || !Number.isFinite(Number(point.latitude)))) throw new Error('点位坐标缺失，不能进行真实道路比较')
    const key = JSON.stringify(points.map(point => [point.facilityId, point.longitude, point.latitude, point.role, point.weightSource]))
    if (!cache.has(key)) cache.set(key, (async () => {
      const same = points.every(point => Number(point.longitude) === Number(points[0].longitude) && Number(point.latitude) === Number(points[0].latitude))
      const road = same ? { segments: points.slice(1).map((point, index) => ({ distance: 0, durationMinutes: 0, pathSource: 'SAME_POINT', path: [points[index], point] })) } : await resolveRoad(points, options)
      if (road.segments?.length !== points.length - 1 || road.segments.some(segment => !['OD_CACHE', 'OD_PRELOAD', 'BAIDU_ONLINE', 'SAME_POINT'].includes(segment.pathSource) || segment.path?.length < 2 || !segment.path?.length || segment.path.some(point => point.longitude == null || point.latitude == null || !Number.isFinite(Number(point.longitude)) || !Number.isFinite(Number(point.latitude))) || segment.distance == null || segment.durationMinutes == null || !Number.isFinite(Number(segment.distance)) || !Number.isFinite(Number(segment.durationMinutes)) || Number(segment.distance) < 0 || Number(segment.durationMinutes) < 0)) throw new Error('道路数据不完整或已回退直线，不能用于比较')
      return road
    })())
    return cache.get(key)
  }
  const rebuild = async (input, label) => {
    const result = clone(input)
    for (const route of result.routes) {
      onProgress(`正在计算${label}第${route.tripNo || route.routeNo}趟道路…`)
      if (route.points.length < 2 || route.points.some(point => point.operationDurationMinutes == null || !Number.isFinite(Number(point.operationDurationMinutes)) || Number(point.operationDurationMinutes) < 0)) throw new Error('点位作业时间缺失或无效，不能进行耗时和时间窗比较')
      route.points.forEach((point, index) => { point.order = point.orderNum = index + 1 })
      const { segments } = await roadFor(route.points)
      const distance = segments.reduce((sum, segment) => sum + Number(segment.distance), 0)
      const travel = segments.reduce((sum, segment) => sum + Number(segment.durationMinutes), 0)
      const operation = route.points.reduce((sum, point) => sum + Number(point.operationDurationMinutes || 0), 0)
      route.segments = route.roadSegments = segments
      route.distance = route.planningDistance = route.displayDistance = distance
      route.travelDurationMinutes = route.planningTravelDurationMinutes = route.displayTravelDurationMinutes = travel
      route.operationDurationMinutes = operation
      route.durationMinutes = route.totalDurationMinutes = route.planningTotalDurationMinutes = route.displayTotalDurationMinutes = travel + operation
    }
    result.distanceMode = 'ROAD'
    return result
  }
  const summarize = (result, dueIds) => {
    const schedules = buildSchedules(result, options.plannedStartTime)
    let violations = 0
    const annotated = clone(result)
    annotated.routes.forEach(route => route.points.forEach(point => {
      const timing = schedules.get(Number(route.routeNo))?.pointsByOrder.get(Number(point.order))
      point.comparisonArrivalTime = timing?.arrivalTime || '—'
      point.comparisonDepartureTime = timing?.departureTime || '—'
      point.timeWindowViolation = point.role === 'MIDDLE' && !point.scheduleSkipped && isViolation(point, timing?.arrivalMinutes)
      if (point.timeWindowViolation) violations++
    }))
    const covered = new Set(annotated.routes.flatMap(route => route.points.filter(point => point.role === 'MIDDLE').map(point => String(point.facilityId))))
    const missing = dueIds ? [...dueIds].filter(id => !covered.has(id)).length : Number(result.unassignedPointCount || result.unassignedPoints?.length || 0)
    return { result: annotated, distance: result.routes.reduce((sum, route) => sum + Number(route.distance), 0), duration: result.routes.reduce((sum, route) => sum + Number(route.durationMinutes), 0), violations, missing }
  }
  const generated = await rebuild(original, '系统原序')
  const manual = await rebuild(draft, '手动顺序')
  const scenarios = [{ day: 0, label: scheduleDays.length ? '完整模板（所有点都收）' : '当前方案', generated: summarize(generated), manual: summarize(manual) }]
  for (const day of scheduleDays) {
    onProgress(`正在检查第${day.day}天跳点后的道路与时间窗…`)
    const due = new Set(day.facilityIds.map(String))
    scenarios.push({ day: day.day, label: `第${day.day}天`, generated: summarize(await buildFixedDailyRouteResult(generated, due, roadFor), due), manual: summarize(await buildFixedDailyRouteResult(manual, due, roadFor), due) })
  }
  const generatedConflicts = scenarios.reduce((sum, scenario) => sum + scenario.generated.violations, 0)
  const manualConflicts = scenarios.reduce((sum, scenario) => sum + scenario.manual.violations, 0)
  const verdict = compareManualMetrics(scenarios[0].generated, scenarios[0].manual, manualConflicts)
  if (scenarios.some(scenario => scenario.manual.missing > 0)) {
    if (verdict.manual === 'green') verdict.manual = 'yellow'
    verdict.message += '；仍有应收点未分配，本次只比较已有趟次，不代表完整收运任务可执行'
  }
  return { scenarios, verdict, generatedConflicts, manualConflicts }
}
