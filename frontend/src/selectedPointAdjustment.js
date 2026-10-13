import { assertManualMembership, buildManualComparison } from './manualRouteTest.js'

const clone = value => JSON.parse(JSON.stringify(value))
export const adjustablePoint = point => !['START', 'END'].includes(point.role) && point.weightSource !== 'ANCHOR'
const key = result => JSON.stringify(result.routes.map(route => route.points.map(point => point.manualToken)))
const better = (a, b) => a.distance < b.distance - 1 || (Math.abs(a.distance - b.distance) < 1 && a.duration < b.duration - 0.01)

// Only selected occurrences move; unselected relative order and every anchor position remain invariant.
export function* selectedAdjustmentCandidates(input, tokens) {
  const selected = new Set(tokens), seen = new Set([key(input)])
  function candidate(r, order) {
    const result = clone(input)
    result.routes[r].points = order.map((point, i) => ({ ...point, order: i + 1, orderNum: i + 1 }))
    const signature = key(result)
    if (seen.has(signature)) return null
    seen.add(signature)
    return result
  }
  for (let r = 0; r < input.routes.length; r++) {
    const points = input.routes[r].points
    let begin = 1
    while (begin < points.length - 1) {
      if (!adjustablePoint(points[begin])) { begin++; continue }
      let end = begin
      while (end < points.length - 1 && adjustablePoint(points[end])) end++
      const chosen = i => selected.has(points[i].manualToken)
      for (let i = begin; i < end; i++) if (chosen(i)) {
        for (let j = i + 1; j < end; j++) if (chosen(j)) {
          const order = [...points]; [order[i], order[j]] = [order[j], order[i]]
          const result = candidate(r, order); if (result) yield result
        }
        let runEnd = i + 1
        while (runEnd < end && chosen(runEnd)) runEnd++
        const lengths = [...new Set([1, 2, 3, runEnd - i])].filter(length => length <= runEnd - i)
        for (const length of lengths) {
          const remainder = [...points]; const chain = remainder.splice(i, length)
          for (let j = begin; j <= end - length; j++) {
            const order = [...remainder]; order.splice(j, 0, ...chain)
            const result = candidate(r, order); if (result) yield result
          }
        }
        for (let j = i + 2; j <= runEnd; j++) {
          const order = [...points]; order.splice(i, j - i, ...points.slice(i, j).reverse())
          const result = candidate(r, order); if (result) yield result
        }
      }
      begin = end + 1
    }
  }
}

function feasible(comparison, options) {
  if (comparison.manualConflicts || comparison.scenarios.some(s => s.manual.missing)) return false
  for (const scenario of comparison.scenarios) {
    const worked = new Map()
    for (const route of scenario.manual.result.routes) {
      const weight = route.points.filter(p => p.role === 'MIDDLE' && !p.scheduleSkipped).reduce((sum, p) => sum + Number(p.estimatedWeightKg || 0), 0)
      if (weight > Number(route.maxCapacityKg) + 1e-6) return false
      const vehicle = String(route.vehicleId ?? route.vehicleIndex ?? 'default')
      const total = (worked.get(vehicle) || 0) + route.durationMinutes
      worked.set(vehicle, total)
      const limit = Number(options.workHours) > 0 ? Number(options.workHours) * 60 : Number(route.workLimitMinutes) > 0 ? Number(route.workLimitMinutes) : 480
      if (total > limit + 1e-6) return false
    }
  }
  return true
}

export async function optimizeSelectedPoints({ draft, selectedTokens, options, scheduleDays = [], resolveRoad, buildSchedules, isViolation, signal, onProgress = () => {} }) {
  const baseline = clone(draft), chosen = new Set(selectedTokens)
  const allTokens = baseline.routes.flatMap(route => route.points.map(p => p.manualToken))
  if (allTokens.some(token => typeof token !== 'string' || !token) || new Set(allTokens).size !== allTokens.length) throw new Error('点位出现标识缺失或重复，请重新打开调整窗口')
  if (options.workHours != null && (!Number.isFinite(Number(options.workHours)) || Number(options.workHours) <= 0)) throw new Error('工作时长必须为正数')
  const eligible = baseline.routes.flatMap(route => route.points.slice(1, -1).filter(adjustablePoint).map(p => p.manualToken))
  if (!chosen.size || [...chosen].some(token => !eligible.includes(token))) throw new Error('请勾选可移动收运点，不能选择锚点')
  for (const route of baseline.routes) {
    if (!Number.isFinite(Number(route.maxCapacityKg)) || Number(route.maxCapacityKg) <= 0) throw new Error('车辆最大载重缺失，不能确认调整可行性')
    if (route.points.some(p => p.role === 'MIDDLE' && (!Number.isFinite(Number(p.estimatedWeightKg)) || p.estimatedWeightKg == null || Number(p.estimatedWeightKg) < 0))) throw new Error('点位重量缺失或无效')
  }
  const check = () => { if (signal?.aborted) throw new DOMException('已取消，开始前的编辑内容保留', 'AbortError') }
  const cache = new Map()
  let evaluations = 0, round = 0, bestInfo = null
  const started = Date.now()
  const report = message => onProgress({ message, evaluations, round, best: bestInfo, elapsedMs: Date.now() - started })
  const road = async points => {
    check()
    const signature = JSON.stringify(points.map(p => [p.facilityId, p.longitude, p.latitude, p.role, p.weightSource]))
    if (!cache.has(signature)) cache.set(signature, Promise.resolve(resolveRoad(points, options, signal)))
    const result = await cache.get(signature); check(); return result
  }
  const evaluate = candidate => buildManualComparison({ original: baseline, draft: candidate, options, scheduleDays, resolveRoad: road, buildSchedules, isViolation, onProgress: report })
  check(); report('正在道路复算本次编辑基准…')
  const baseComparison = await evaluate(baseline), baselineMetrics = baseComparison.scenarios[0].manual
  const baselineFeasible = feasible(baseComparison, options)
  let current = baseline, currentMetrics = baselineMetrics, best = null, shorterConflict = null
  const visited = new Set([key(baseline)])
  while (true) {
    round++
    let roundBest = null
    for (const candidate of selectedAdjustmentCandidates(current, chosen)) {
      check()
      const signature = key(candidate); if (visited.has(signature)) continue
      visited.add(signature); assertManualMembership(baseline, candidate)
      const comparison = await evaluate(candidate); check(); evaluations++
      const metrics = comparison.scenarios[0].manual
      if (better(metrics, baselineMetrics) && comparison.manualConflicts > 0 && (!shorterConflict || better(metrics, shorterConflict.metrics))) shorterConflict = { candidate, comparison, metrics }
      if (feasible(comparison, options) && better(metrics, baselineMetrics) && better(metrics, currentMetrics) && (!roundBest || better(metrics, roundBest.metrics))) {
        roundBest = { candidate, comparison, metrics }
        bestInfo = { distance: metrics.distance, duration: metrics.duration, allScenariosPassed: true }
      }
      report(`第${round}轮：已比较${evaluations}个候选；仅移动所选点，其他点保持相对顺序`)
      // Allow cancel and paint even when all roads are already cached.
      await new Promise(resolve => setTimeout(resolve, 0))
    }
    if (!roundBest) break
    best = roundBest; current = best.candidate; currentMetrics = best.metrics
  }
  check()
  const result = best || shorterConflict || { candidate: baseline, comparison: baseComparison }
  if (!baselineFeasible && result.comparison.verdict.generated === 'green') result.comparison.verdict.generated = 'yellow'
  return { ...result, improved: !!best, conflictOnly: !best && !!shorterConflict, evaluations, rounds: round,
    message: (best ? '找到里程更短或里程相当而耗时更少、且全部约束通过的调整；所选范围局部搜索，不代表全局最优' : shorterConflict ? '找到更优但超窗的候选，仅供对照，不自动推荐采用' : '未找到更好的可行调整；开始前顺序保留') + (baselineFeasible ? '' : '。注意：调整前基准本身也未通过全部约束，不代表可执行。') }
}
