import { buildFinalReturnTrip } from './finalReturnTrip.js'

const ROAD_SOURCES = new Set(['OD_CACHE', 'OD_PRELOAD', 'BAIDU_ONLINE', 'SAME_POINT'])

export function planSingleTripBreaks(route, ratedCapacityKg, targetLoadRate) {
  const thresholdKg = Number(ratedCapacityKg) * Number(targetLoadRate)
  if (!Number.isFinite(thresholdKg) || thresholdKg <= 0) throw new Error('额定载重与目标装载率必须大于0')
  const points = route?.points || []
  const breaks = []
  let loadKg = 0
  for (let index = 1; index < points.length - 1; index += 1) {
    const point = points[index]
    if (point.role !== 'MIDDLE') continue
    const weightKg = Number(point.estimatedWeightKg || 0)
    if (!Number.isFinite(weightKg) || weightKg < 0) throw new Error(`${point.facilityName}的垃圾量无效`)
    if (weightKg > thresholdKg + 1e-6) throw new Error(`${point.facilityName}单点垃圾量超过目标装载量，无法整点收运`)
    if (loadKg > 0 && loadKg + weightKg > thresholdKg + 1e-6) {
      breaks.push(index - 1)
      loadKg = 0
    }
    loadKg += weightKg
  }
  return { thresholdKg, breaks }
}

export async function buildSingleTripDetours(route, ratedCapacityKg, targetLoadRate, unloadMinutes, resolveRoad) {
  const { thresholdKg, breaks } = planSingleTripBreaks(route, ratedCapacityKg, targetLoadRate)
  const points = route.points || []
  const baseSegments = route.roadSegments || route.segments || []
  if (baseSegments.length !== points.length - 1) throw new Error('基础单趟道路数据不完整')
  const terminal = points.at(-1)
  if (!terminal || terminal.role !== 'END') throw new Error('单趟路线缺少终点处理厂')
  const expandedSegments = []
  const detourSegments = []
  const breakSet = new Set(breaks)
  let extraDistance = 0
  let extraTravelMinutes = 0
  for (let index = 0; index < baseSegments.length; index += 1) {
    const original = baseSegments[index]
    if (!breakSet.has(index)) {
      expandedSegments.push(original)
      continue
    }
    const road = await resolveRoad([points[index], terminal, points[index + 1]])
    const replacement = road?.segments || []
    if (replacement.length !== 2 || replacement.some(segment => !ROAD_SOURCES.has(segment.pathSource) || !segment.path?.length || !Number.isFinite(Number(segment.distance)) || !Number.isFinite(Number(segment.durationMinutes)))) {
      throw new Error(`第${index + 1}段中途卸料道路数据不完整；不以直线回退`)
    }
    const additions = replacement.map(segment => ({ ...segment, detourToTerminal: true }))
    expandedSegments.push(...additions)
    detourSegments.push(...additions)
    extraDistance += additions.reduce((sum, segment) => sum + Number(segment.distance), 0) - Number(original.distance)
    extraTravelMinutes += additions.reduce((sum, segment) => sum + Number(segment.durationMinutes), 0) - Number(original.durationMinutes)
  }
  const baseDistance = baseSegments.reduce((sum, segment) => sum + Number(segment.distance), 0)
  const baseDurationMinutes = Number(route.displayTotalDurationMinutes || 0)
  const extraDurationMinutes = extraTravelMinutes + breaks.length * Number(unloadMinutes || 0)
  const returnTrip = await buildFinalReturnTrip([route], resolveRoad)
  return { thresholdKg, breaks, unloadMinutes: Number(unloadMinutes || 0), baseDistance, extraDistance, totalDistance: baseDistance + extraDistance,
    baseDurationMinutes, extraDurationMinutes, totalDurationMinutes: baseDurationMinutes + extraDurationMinutes,
    expandedSegments, detourSegments, returnTrip }
}

export function summarizeSingleTripMileage(legs) {
  const rows = []
  for (const leg of legs) {
    const distance = segments => segments.reduce((sum, segment) => sum + Number(segment.distance), 0)
    const add = (label, segments) => rows.push({ label, meters: distance(segments) })
    if (leg.returnOnly) {
      add('最终返场', leg.segments)
      continue
    }
    const label = '第' + leg.no + '段'
    const collectionIndices = leg.points.flatMap((point, index) => point.role === 'MIDDLE' && !point.scheduleSkipped ? [index] : [])
    if (!collectionIndices.length) {
      add(label + ' · 场站间行驶（无收运点）', leg.segments)
      continue
    }
    const first = collectionIndices[0]
    const last = collectionIndices.at(-1)
    add(label + ' · 出发到首点', leg.segments.slice(0, first))
    add(label + ' · 首点到末点', leg.segments.slice(first, last))
    add(label + ' · 末点到场站', leg.segments.slice(last))
  }
  return { rows, totalMeters: rows.reduce((sum, row) => sum + row.meters, 0) }
}

export function composeSingleTripDisplay(route, detours, includeDetours, includeReturn) {
  const returnTrip = includeReturn ? detours.returnTrip : null
  const detourDistance = includeDetours ? detours.extraDistance : 0
  const detourDurationMinutes = includeDetours ? detours.extraDurationMinutes : 0
  const returnDistance = returnTrip?.distance || 0
  const returnDurationMinutes = returnTrip?.durationMinutes || 0
  return {
    segments: [...(includeDetours ? detours.expandedSegments : route.roadSegments), ...(returnTrip?.roadSegments || [])],
    highlightSegments: [...(includeDetours ? detours.detourSegments : []), ...(returnTrip?.roadSegments || [])],
    baseDistance: detours.baseDistance,
    detourDistance,
    returnDistance,
    totalDistance: detours.baseDistance + detourDistance + returnDistance,
    baseDurationMinutes: detours.baseDurationMinutes,
    detourDurationMinutes,
    returnDurationMinutes,
    totalDurationMinutes: detours.baseDurationMinutes + detourDurationMinutes + returnDurationMinutes
  }
}
