import { assertManualMembership, buildManualComparison } from './manualRouteTest.js'
import { adjustablePoint } from './selectedPointAdjustment.js'

const clone = value => JSON.parse(JSON.stringify(value))

export function exactOrderUnavailableReason(route) {
  if (!route?.points?.length) return '请选择有效趟次'
  const count = route.points.slice(1, -1).filter(adjustablePoint).length
  if (count > 10) return '当前趟次有' + count + '个可排序收运点，超过全排列上限10个'
  if (count < 2) return '当前趟次不足2个可排序收运点，无需全排列'
  return ''
}

export function exactSegmentLimit(route, endIndex) {
  if (!Number.isInteger(endIndex) || endIndex < 1 || endIndex >= (route?.points?.length || 0)) return 0
  let count = 0
  for (let index = endIndex - 1; index > 0 && count < 10; index--) {
    if (!adjustablePoint(route.points[index])) break
    count++
  }
  return count
}

const vehicleKey = route => String(route.vehicleId || route.vehicleName || 'DEFAULT')

function timingForRoute(result, routeIndex, buildSchedules, startTime, checkDeparture) {
  const route = result.routes[routeIndex]
  const schedules = buildSchedules(result, startTime)
  const schedule = schedules.get(Number(route.routeNo))
  if (!Number.isFinite(schedule?.startMinutes) || !Number.isFinite(schedule?.endMinutes)) throw new Error('当前趟次缺少有效发车时间')
  const downstreamWindows = result.routes.slice(routeIndex + 1).filter(next => vehicleKey(next) === vehicleKey(route))
    .flatMap(next => next.points.filter(point => point.role === 'MIDDLE' && !point.scheduleSkipped &&
      (point.allowTimeBegin || point.allowTimeEnd || (point.barredTimeBegin && point.barredTimeEnd))).map(point => {
      const arrival = schedules.get(Number(next.routeNo))?.pointsByOrder.get(Number(point.order))?.arrivalMinutes
      if (!Number.isFinite(arrival)) throw new Error('后续趟次缺少有效到达时间')
      return { ...point, arrivalOffsetMinutes: arrival - schedule.endMinutes }
    }))
  return { tripStartMinutes: schedule.startMinutes, downstreamWindows, checkDeparture }
}

export async function optimizeExactRoute({ draft, routeIndex, options, resolveExact, resolveRoad, buildSchedules, isViolation, singleTripMode = false, reorderRange = null, signal, onProgress = () => {} }) {
  const route = draft.routes[routeIndex]
  if (reorderRange) {
    const count = reorderRange.endIndex - reorderRange.startIndex
    if (!Number.isInteger(count) || count < 2 || count > exactSegmentLimit(route, reorderRange.endIndex)) {
      throw new Error('请选择固定终点及其前方连续2至10个可排序收运点')
    }
  }
  const reason = reorderRange ? '' : exactOrderUnavailableReason(route)
  if (reason) throw new Error(reason)
  const check = () => { if (signal?.aborted) throw new DOMException('已取消全排列', 'AbortError') }
  const baseline = clone(draft)
  const key = points => JSON.stringify(points.map(point => point.manualToken))
  const roads = new Map()
  const compare = candidate => buildManualComparison({
    original: baseline, draft: candidate, options, scheduleDays: [], buildSchedules, isViolation,
    resolveRoad: async points => {
      check()
      const signature = key(points)
      if (!roads.has(signature)) roads.set(signature, resolveRoad(points, options, signal))
      return roads.get(signature)
    },
    onProgress: message => onProgress({ message })
  })
  check()
  const baselineComparison = await compare(baseline)
  const timing = timingForRoute(baselineComparison.scenarios[0].generated.result,
    routeIndex, buildSchedules, options.plannedStartTime, singleTripMode)
  check()
  onProgress({ message: '正在枚举当前趟次的全部允许顺序，同时比较两种时间窗口径…' })
  const points = baseline.routes[routeIndex].points
  if (reorderRange) timing.reorderRange = reorderRange
  const response = await resolveExact(points, options, signal, timing)
  check()
  roads.set(key(points), { segments: response.originalSegments })
  const compareCandidate = async item => {
    const candidate = clone(baseline)
    candidate.routes[routeIndex].points = item.points
    assertManualMembership(baseline, candidate)
    roads.set(key(item.points), { segments: item.adjustedSegments })
    const comparison = await compare(candidate)
    check()
    const changedTokens = item.points.filter((point, index) => point.manualToken !== points[index].manualToken)
      .map(point => point.manualToken)
    return { candidate, comparison, changedTokens }
  }
  const distance = await compareCandidate(response)
  const timeWindow = response.timeWindowCandidate
    ? key(response.timeWindowCandidate.points) === key(response.points) ? distance : await compareCandidate(response.timeWindowCandidate)
    : null
  return { distance, timeWindow, message: '全排列完成，共比较 ' + response.evaluated + ' 种允许顺序。' }
}
