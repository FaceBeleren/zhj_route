import { assertManualMembership, buildManualComparison } from './manualRouteTest.js'
import { adjustablePoint, operationallyFeasible } from './selectedPointAdjustment.js'

const clone = value => JSON.parse(JSON.stringify(value))
const better = (a, b) => a.distance < b.distance - 1 || (Math.abs(a.distance - b.distance) < 1 && a.duration < b.duration - 0.01)

// Directed road costs: reversing a block also reverses every internal arc.
export function* twoOptCandidates(input, routeIndex) {
  const points = input.routes[routeIndex]?.points
  if (!points) throw new Error('请选择有效趟次')
  for (let i = 1; i < points.length - 2; i++) {
    if (!adjustablePoint(points[i])) continue
    for (let j = i + 1; j < points.length - 1; j++) {
      if (!adjustablePoint(points[j])) break
      const candidate = clone(input)
      candidate.routes[routeIndex].points.splice(i, j - i + 1, ...clone(points.slice(i, j + 1)).reverse())
      candidate.routes[routeIndex].points.forEach((point, index) => { point.order = point.orderNum = index + 1 })
      yield candidate
    }
  }
}

export async function optimizeTwoOpt({ draft, routeIndex, options, resolveRoad, buildSchedules, isViolation, signal, onProgress = () => {}, timeLimitMs = 30000, maxEvaluations = 2000 }) {
  const baseline = clone(draft), started = Date.now()
  if (!baseline.routes[routeIndex]) throw new Error('请选择有效趟次')
  for (const route of baseline.routes) {
    if (!Number.isFinite(Number(route.maxCapacityKg)) || Number(route.maxCapacityKg) <= 0) throw new Error('车辆最大载重缺失或无效，不能确认调整可行性')
    if (route.points.some(p => p.role === 'MIDDLE' && (p.estimatedWeightKg == null || !Number.isFinite(Number(p.estimatedWeightKg)) || Number(p.estimatedWeightKg) < 0))) throw new Error('点位重量缺失或无效')
  }
  if (options.workHours != null && (!Number.isFinite(Number(options.workHours)) || Number(options.workHours) <= 0)) throw new Error('工作时长必须为正数')
  const check = () => { if (signal?.aborted) throw new DOMException('已取消，未采用部分结果', 'AbortError') }
  const edges = new Map()
  const edgeKey = (a, b) => JSON.stringify([a.manualToken, a.longitude, a.latitude, b.manualToken, b.longitude, b.latitude])
  // Resolve only previously unseen directed edges; all candidate comparisons reuse them.
  const road = async points => {
    check()
    const segments = []
    if (points.length > 2 && points.slice(1).every((p, i) => !edges.has(edgeKey(points[i], p)))) {
      const response = await resolveRoad(points, options, signal)
      check()
      if (response.segments?.length !== points.length - 1) throw new Error('道路数据不完整')
      response.segments.forEach((segment, i) => edges.set(edgeKey(points[i], points[i + 1]), segment))
    }
    for (let i = 1; i < points.length; i++) {
      check()
      const a = points[i - 1], b = points[i], key = edgeKey(a, b)
      if (!edges.has(key)) {
        const response = await resolveRoad([a, b], options, signal)
        check()
        if (response.segments?.length !== 1) throw new Error('道路数据不完整')
        edges.set(key, response.segments[0])
      }
      segments.push(edges.get(key))
    }
    return { segments }
  }
  let evaluations = 0, rounds = 0, accepted = 0, limited = false
  const report = message => onProgress({ message, evaluations, rounds, accepted })
  const evaluate = candidate => buildManualComparison({ original: baseline, draft: candidate, options, scheduleDays: [], resolveRoad: road, buildSchedules, isViolation })
  check(); report('正在复算完整路线基准并准备有向道路缓存…')
  let comparison = await evaluate(baseline), current = baseline
  let metrics = comparison.scenarios[0].manual
  const visited = new Set([JSON.stringify(current.routes[routeIndex].points.map(p => p.manualToken))])
  // The limit bounds search; an in-flight road request finishes before we return a checked result.
  const searchStarted = Date.now()
  search: while (true) {
    rounds++
    let found = false
    for (const candidate of twoOptCandidates(current, routeIndex)) {
      check()
      if (Date.now() - searchStarted >= timeLimitMs || evaluations >= maxEvaluations) { limited = true; break search }
      const signature = JSON.stringify(candidate.routes[routeIndex].points.map(p => p.manualToken))
      if (visited.has(signature)) continue
      visited.add(signature)
      assertManualMembership(baseline, candidate)
      const checked = await evaluate(candidate)
      check(); evaluations++
      const next = checked.scenarios[0].manual
      if (better(next, metrics) && checked.manualConflicts === 0 && operationallyFeasible(checked, options)) {
        current = candidate; comparison = checked; metrics = next; accepted++; found = true
      }
      report(`已检查${evaluations}个区段反转，采用${accepted}次改善；第${rounds}轮`)
      await new Promise(resolve => setTimeout(resolve, 0))
      if (found) break
    }
    if (!found) break
  }
  check()
  const changedTokens = new Set()
  current.routes[routeIndex].points.forEach((point, index) => { if (point.manualToken !== baseline.routes[routeIndex].points[index].manualToken) changedTokens.add(point.manualToken) })
  const feasible = operationallyFeasible(comparison, options) && comparison.manualConflicts === 0
  if (!feasible && comparison.verdict.manual === 'green') comparison.verdict.manual = 'yellow'
  const message = `2-opt完成：检查${evaluations}次，采用${accepted}次改善；${limited ? '达到搜索上限，返回已检查结果' : '允许范围内未找到进一步改善'}。仅调整当前趟次，不跨固定锚点；完整路线时间窗冲突${comparison.manualConflicts}次，未检查每日跳点。${!feasible ? '当前结果未通过完整路线约束检查。' : ''}`
  report(message)
  return { candidate: current, comparison, changedTokens: [...changedTokens], message, evaluations, rounds, accepted, limited, elapsedMs: Date.now() - started }
}
