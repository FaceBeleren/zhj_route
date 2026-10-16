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
  return { thresholdKg, breaks, baseDistance, extraDistance, totalDistance: baseDistance + extraDistance,
    baseDurationMinutes, extraDurationMinutes, totalDurationMinutes: baseDurationMinutes + extraDurationMinutes,
    expandedSegments, detourSegments }
}
