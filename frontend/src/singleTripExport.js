import { composeSingleTripDisplay } from './singleTripDetours.js'

function amount(value) { return Number(value || 0) }

export function buildSingleTripExport(route, detours, includeDetours, includeReturn, unloadMinutes) {
  if (!route?.points?.length || !detours) throw new Error('单趟路线或卸料计算缺失')
  const baseSegments = route.roadSegments || route.segments || []
  if (baseSegments.length !== route.points.length - 1) throw new Error('基础单趟道路不完整')
  const display = composeSingleTripDisplay(route, detours, includeDetours, includeReturn)
  const terminal = route.points.at(-1)
  const breaks = new Set(includeDetours ? detours.breaks : [])
  const points = [{ ...route.points[0] }]
  const segments = []
  const breakDetails = []
  let expandedIndex = 0
  let loadKg = 0
  for (let index = 0; index < baseSegments.length; index += 1) {
    if (route.points[index]?.role === 'MIDDLE') loadKg += amount(route.points[index].estimatedWeightKg)
    if (breaks.has(index)) {
      const toTerminal = detours.expandedSegments[expandedIndex++]
      const fromTerminal = detours.expandedSegments[expandedIndex++]
      if (!toTerminal || !fromTerminal) throw new Error('中途卸料道路不完整')
      points.push({ ...terminal, role: 'UNLOAD', facilityName: `${terminal.facilityName || terminal.facilityId}（中途卸料）`, operationDurationMinutes: amount(unloadMinutes), estimatedWeightKg: 0, estimatedVolumeLiter: 0 })
      points.push({ ...route.points[index + 1] })
      segments.push(toTerminal, fromTerminal)
      breakDetails.push({ afterPoint: route.points[index].facilityName || route.points[index].facilityId,
        nextPoint: route.points[index + 1].facilityName || route.points[index + 1].facilityId,
        terminal: terminal.facilityName || terminal.facilityId, loadKg,
        originalDistance: amount(baseSegments[index].distance), originalDurationMinutes: amount(baseSegments[index].durationMinutes),
        toTerminalDistance: amount(toTerminal.distance), toTerminalDurationMinutes: amount(toTerminal.durationMinutes),
        fromTerminalDistance: amount(fromTerminal.distance), fromTerminalDurationMinutes: amount(fromTerminal.durationMinutes),
        extraDistance: amount(toTerminal.distance) + amount(fromTerminal.distance) - amount(baseSegments[index].distance),
        extraDurationMinutes: amount(toTerminal.durationMinutes) + amount(fromTerminal.durationMinutes) - amount(baseSegments[index].durationMinutes) + amount(unloadMinutes) })
      loadKg = 0
    } else {
      points.push({ ...route.points[index + 1] })
      segments.push(includeDetours ? detours.expandedSegments[expandedIndex++] : baseSegments[index])
    }
  }
  const returnTrip = includeReturn ? detours.returnTrip : null
  if (returnTrip) {
    if (returnTrip.roadSegments?.length !== 1) throw new Error('最终返场道路不完整')
    points.push({ ...returnTrip.points[1], role: 'RETURN', facilityName: `${returnTrip.points[1].facilityName || returnTrip.points[1].facilityId}（最终返场）`, operationDurationMinutes: 0, estimatedWeightKg: 0, estimatedVolumeLiter: 0 })
    segments.push(returnTrip.roadSegments[0])
  }
  if (segments.length !== points.length - 1 || segments.some(segment => !segment)) throw new Error('单趟导出路段与点位不对应')
  const orderedPoints = points.map((point, index) => ({ ...point, order: index + 1, orderNum: index + 1 }))
  const orderedSegments = segments.map((segment, index) => ({ ...segment, order: index + 1,
    fromFacilityId: orderedPoints[index].facilityId, fromFacilityName: orderedPoints[index].facilityName,
    toFacilityId: orderedPoints[index + 1].facilityId, toFacilityName: orderedPoints[index + 1].facilityName }))
  const travelDurationMinutes = orderedSegments.reduce((sum, segment) => sum + amount(segment.durationMinutes), 0)
  const exportedRoute = { ...route, points: orderedPoints, segments: orderedSegments, roadSegments: orderedSegments,
    loadRate: null,
    distance: display.totalDistance, displayDistance: display.totalDistance,
    travelDurationMinutes, displayTravelDurationMinutes: travelDurationMinutes,
    operationDurationMinutes: display.totalDurationMinutes - travelDurationMinutes,
    durationMinutes: display.totalDurationMinutes, totalDurationMinutes: display.totalDurationMinutes,
    displayTotalDurationMinutes: display.totalDurationMinutes }
  return { route: exportedRoute, breakdown: {
    baseDistance: display.baseDistance, detourDistance: display.detourDistance, returnDistance: display.returnDistance,
    totalDistance: display.totalDistance, baseDurationMinutes: display.baseDurationMinutes,
    detourDurationMinutes: display.detourDurationMinutes, returnDurationMinutes: display.returnDurationMinutes,
    totalDurationMinutes: display.totalDurationMinutes, thresholdKg: detours.thresholdKg,
    breakCount: breakDetails.length, breakDetails,
    returnFrom: returnTrip?.points?.[0]?.facilityName || returnTrip?.points?.[0]?.facilityId || '',
    returnTo: returnTrip?.points?.[1]?.facilityName || returnTrip?.points?.[1]?.facilityId || ''
  } }
}
