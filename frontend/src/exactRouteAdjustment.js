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

export async function optimizeExactRoute({ draft, routeIndex, options, resolveExact, resolveRoad, buildSchedules, isViolation, signal, onProgress = () => {} }) {
  const reason = exactOrderUnavailableReason(draft.routes[routeIndex])
  if (reason) throw new Error(reason)
  const check = () => { if (signal?.aborted) throw new DOMException('已取消全排列', 'AbortError') }
  const baseline = clone(draft)
  check()
  onProgress({ message: '正在准备有向道路矩阵并枚举当前趟次的全部允许顺序…' })
  const response = await resolveExact(baseline.routes[routeIndex].points, options, signal)
  check()
  const candidate = clone(baseline)
  candidate.routes[routeIndex].points = response.points
  assertManualMembership(baseline, candidate)
  const key = points => JSON.stringify(points.map(point => point.manualToken))
  const roads = new Map([
    [key(baseline.routes[routeIndex].points), response.originalSegments],
    [key(candidate.routes[routeIndex].points), response.adjustedSegments]
  ])
  const comparison = await buildManualComparison({
    original: baseline, draft: candidate, options, scheduleDays: [], buildSchedules, isViolation,
    resolveRoad: async points => {
      check()
      const segments = roads.get(key(points))
      return segments ? { segments } : resolveRoad(points, options, signal)
    },
    onProgress: message => onProgress({ message })
  })
  check()
  const changedTokens = candidate.routes[routeIndex].points.filter((point, index) =>
    point.manualToken !== baseline.routes[routeIndex].points[index].manualToken).map(point => point.manualToken)
  return { candidate, comparison, changedTokens,
    message: '全排列完成：共比较' + response.evaluated + '种允许顺序，已选出固定起终点及锚点范围内道路里程最短的顺序。完整路线时间窗冲突' + comparison.manualConflicts + '次。' }
}
