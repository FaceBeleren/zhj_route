import { buildSingleTripDetours } from './singleTripDetours.js'

const ROAD_SOURCES = new Set(['OD_CACHE', 'OD_PRELOAD', 'BAIDU_ONLINE', 'SAME_POINT'])

function clock(minutes) {
  const rounded = Math.max(0, Math.round(minutes))
  const day = Math.floor(rounded / 1440)
  return `${String(Math.floor((rounded % 1440) / 60)).padStart(2, '0')}:${String(rounded % 60).padStart(2, '0')}${day ? `（+${day}天）` : ''}`
}

function routeFromPoints(source, points, segments) {
  if (segments?.length !== points.length - 1 || segments.some(segment => !ROAD_SOURCES.has(segment.pathSource) || !segment.path?.length || !Number.isFinite(Number(segment.distance)) || !Number.isFinite(Number(segment.durationMinutes)))) {
    throw new Error('单趟时间窗修正缺少完整道路数据')
  }
  let middleOrder = 0
  const ordered = points.map((point, index) => ({ ...point, order: index + 1, orderNum: index + 1,
    templateToken: point.role === 'MIDDLE' ? `01-${String(++middleOrder).padStart(3, '0')}` : point.templateToken }))
  const collected = ordered.filter(point => point.role === 'MIDDLE')
  const distance = segments.reduce((sum, segment) => sum + Number(segment.distance), 0)
  const travel = segments.reduce((sum, segment) => sum + Number(segment.durationMinutes), 0)
  const operation = ordered.reduce((sum, point) => sum + Number(point.operationDurationMinutes || 0), 0)
  const weight = collected.reduce((sum, point) => sum + Number(point.estimatedWeightKg || 0), 0)
  return { ...source, points: ordered, sequence: ordered.map(point => point.facilityId), pointCount: collected.length,
    estimatedWeightKg: weight, estimatedVolumeLiter: collected.reduce((sum, point) => sum + Number(point.estimatedVolumeLiter || 0), 0),
    segments, roadSegments: segments, distance, planningDistance: distance, displayDistance: distance,
    travelDurationMinutes: travel, planningTravelDurationMinutes: travel, displayTravelDurationMinutes: travel,
    operationDurationMinutes: operation, durationMinutes: travel + operation, totalDurationMinutes: travel + operation,
    planningTotalDurationMinutes: travel + operation, displayTotalDurationMinutes: travel + operation }
}

export function singleTripTimeline(route, detours, startMinutes, isViolation, penaltyForViolation = () => 1, includeDetours = true) {
  const points = route?.points || []
  const base = route?.roadSegments || route?.segments || []
  if (points.length < 2 || base.length !== points.length - 1) throw new Error('单趟行程点位与道路不对应')
  const breaks = new Set(includeDetours ? detours.breaks : [])
  const expanded = includeDetours ? detours.expandedSegments : base
  const legs = []
  const violations = []
  let penalty = 0, cursor = startMinutes, expandedIndex = 0, legPoints = [], legSegments = []
  const visit = point => {
    const arrivalMinutes = cursor
    cursor += Number(point.operationDurationMinutes || 0)
    const departureMinutes = cursor
    const violated = point.role === 'MIDDLE' && (isViolation(point, arrivalMinutes) || isViolation(point, departureMinutes))
    if (violated) {
      violations.push({ facilityId: String(point.facilityId), facilityName: point.facilityName, arrivalMinutes, departureMinutes })
      penalty += Math.max(penaltyForViolation(point, arrivalMinutes), penaltyForViolation(point, departureMinutes), 1)
    }
    legPoints.push({ ...point, arrivalMinutes, departureMinutes, arrivalTime: clock(arrivalMinutes), departureTime: clock(departureMinutes), timeWindowViolation: violated })
  }
  const closeLeg = () => {
    const last = legPoints.at(-1)
    const first = legPoints[0]
    legs.push({ no: legs.length + 1, points: legPoints, segments: legSegments,
      distance: legSegments.reduce((sum, segment) => sum + Number(segment.distance), 0),
      startTime: first.departureTime, endTime: last.departureTime,
      durationMinutes: last.departureMinutes - first.departureMinutes,
      pointCount: legPoints.filter(point => point.role === 'MIDDLE').length,
      violationCount: legPoints.filter(point => point.timeWindowViolation).length })
    legPoints = []; legSegments = []
  }
  visit(points[0])
  for (let index = 0; index < base.length; index++) {
    if (breaks.has(index)) {
      const toTerminal = expanded[expandedIndex++]
      const fromTerminal = expanded[expandedIndex++]
      if (!toTerminal || !fromTerminal) throw new Error('中途卸料道路分段缺失')
      cursor += Number(toTerminal.durationMinutes)
      legSegments.push(toTerminal)
      const terminal = { ...points.at(-1), role: 'UNLOAD', operationDurationMinutes: Number(detours.unloadMinutes || 0) }
      visit(terminal)
      closeLeg()
      legPoints = [{ ...terminal, arrivalMinutes: cursor, departureMinutes: cursor, arrivalTime: clock(cursor), departureTime: clock(cursor), timeWindowViolation: false }]
      cursor += Number(fromTerminal.durationMinutes)
      legSegments.push(fromTerminal)
    } else {
      const segment = expanded[expandedIndex++]
      if (!segment) throw new Error('单趟道路分段缺失')
      cursor += Number(segment.durationMinutes)
      legSegments.push(segment)
    }
    visit(points[index + 1])
  }
  if (expandedIndex !== expanded.length) throw new Error('单趟道路分段数量不一致')
  closeLeg()
  return { legs, violations, violationCount: violations.length, penalty, endMinutes: cursor,
    distance: legs.reduce((sum, leg) => sum + leg.distance, 0) }
}

export async function buildSingleTripScenario(source, dueIds, capacityKg, loadRate, unloadMinutes, resolveRoad, startMinutes, isViolation, penaltyForViolation) {
  const points = dueIds == null ? source.points : source.points.filter(point => point.role !== 'MIDDLE' || dueIds.has(String(point.facilityId)))
  const road = await resolveRoad(points)
  const route = routeFromPoints(source, points, road.segments)
  const detours = await buildSingleTripDetours(route, capacityKg, loadRate, unloadMinutes, resolveRoad)
  detours.unloadMinutes = unloadMinutes
  const timeline = singleTripTimeline(route, detours, startMinutes, isViolation, penaltyForViolation)
  return { route, detours, timeline }
}

function score(scenarios) {
  const all = [scenarios.complete, ...scenarios.days.filter(day => !day.disabled)]
  return { violations: all.reduce((sum, item) => sum + item.timeline.violationCount, 0),
    penalty: all.reduce((sum, item) => sum + item.timeline.penalty, 0),
    duration: all.reduce((sum, item) => sum + item.timeline.endMinutes, 0),
    distance: all.reduce((sum, item) => sum + item.timeline.distance, 0) }
}

function better(left, right) {
  return left.violations < right.violations || (left.violations === right.violations && (left.penalty < right.penalty - 1e-6 ||
    (Math.abs(left.penalty - right.penalty) < 1e-6 && (left.duration < right.duration - 1e-6 ||
      (Math.abs(left.duration - right.duration) < 1e-6 && left.distance < right.distance - 1e-6)))))
}

export async function repairSingleTripPlan(plan, config) {
  const { capacityKg, loadRate, unloadMinutes, resolveRoad, startMinutes, isViolation, penaltyForViolation, signal, onProgress = () => {} } = config
  const dueByDay = plan.days.map(day => day.disabled ? null : new Set(day.route.points.filter(point => point.role === 'MIDDLE').map(point => String(point.facilityId))))
  const scenarioCache = new Map()
  const scenarioFor = async (route, due) => {
    const ids = route.points.filter(point => due == null || point.role !== 'MIDDLE' || due.has(String(point.facilityId))).map(point => String(point.facilityId))
    const key = JSON.stringify(ids)
    if (!scenarioCache.has(key)) scenarioCache.set(key, buildSingleTripScenario(route, due, capacityKg, loadRate, unloadMinutes, resolveRoad, startMinutes, isViolation, penaltyForViolation))
    return scenarioCache.get(key)
  }
  const makeScenarios = async route => {
    const complete = await scenarioFor(route, null)
    const days = []
    for (let index = 0; index < dueByDay.length; index++) {
      if (signal?.aborted) throw new DOMException('已取消时间窗修正', 'AbortError')
      const day = plan.days[index]
      days.push(day.disabled ? { day: day.day, disabled: true } : { day: day.day, ...await scenarioFor(complete.route, dueByDay[index]) })
    }
    return { complete, days }
  }
  let current = await makeScenarios(plan.complete.route)
  const before = score(current)
  let currentScore = before, evaluated = 0, round = 0
  const visited = new Set()
  while (currentScore.violations > 0) {
    if (signal?.aborted) throw new DOMException('已取消时间窗修正', 'AbortError')
    round++
    const points = current.complete.route.points
    const conflictIds = new Set([current.complete, ...current.days.filter(day => !day.disabled)].flatMap(item => item.timeline.violations.map(row => row.facilityId)))
    let best = current, bestScore = currentScore
    for (let from = 1; from < points.length - 1; from++) {
      if (!conflictIds.has(String(points[from].facilityId)) || points[from].weightSource === 'ANCHOR') continue
      for (let to = 1; to < points.length - 1; to++) {
        if (from === to || points.slice(Math.min(from, to), Math.max(from, to) + 1).some(point => point.weightSource === 'ANCHOR')) continue
        if (signal?.aborted) throw new DOMException('已取消时间窗修正', 'AbortError')
        const reordered = [...points]
        reordered.splice(to, 0, reordered.splice(from, 1)[0])
        const signature = reordered.map(point => String(point.facilityId)).join('|')
        if (visited.has(signature)) continue
        visited.add(signature)
        evaluated++
        onProgress({ round, evaluated, point: points[from].facilityName || points[from].facilityId, best: bestScore })
        const candidate = await makeScenarios({ ...current.complete.route, points: reordered })
        const candidateScore = score(candidate)
        if (better(candidateScore, bestScore)) { best = candidate; bestScore = candidateScore }
      }
    }
    if (best === current) break
    current = best; currentScore = bestScore
  }
  return { ...current, before, after: currentScore, evaluated, rounds: round, improved: better(currentScore, before) }
}
