
export async function buildFixedDailyRouteResult(template, dueIds, resolveRoad) {
  const masked = maskFixedTripTemplate(template, dueIds)
  const routes = []
  for (const route of masked.routes) {
    const points = route.points.filter(point => !point.scheduleSkipped).map((point, index) => ({ ...point, order: index + 1, orderNum: index + 1 }))
    const segments = points.length === route.points.length ? route.roadSegments || route.segments : (await resolveRoad(points)).segments
    if (segments?.length !== points.length - 1 || segments.some(segment => !['OD_CACHE', 'OD_PRELOAD', 'BAIDU_ONLINE', 'SAME_POINT'].includes(segment.pathSource) || !segment.path?.length || !Number.isFinite(Number(segment.distance)) || !Number.isFinite(Number(segment.durationMinutes)))) throw new Error('跳点后未取得完整道路数据，不使用直线回退')
    const distance = segments.reduce((sum, segment) => sum + Number(segment.distance), 0)
    const travel = segments.reduce((sum, segment) => sum + Number(segment.durationMinutes), 0)
    const duration = travel + route.operationDurationMinutes
    routes.push({ ...route, points, sequence: points.map(point => point.facilityId), segments, roadSegments: segments,
      fullTemplatePoints: route.points, fullTemplateSegments: route.roadSegments || route.segments,
      retainedRoadDistance: route.displayDistance ?? route.distance, retainedRoadTotalDurationMinutes: route.displayTotalDurationMinutes,
      distance, planningDistance: distance, displayDistance: distance,
      travelDurationMinutes: travel, planningTravelDurationMinutes: travel, displayTravelDurationMinutes: travel,
      durationMinutes: duration, totalDurationMinutes: duration, planningTotalDurationMinutes: duration, displayTotalDurationMinutes: duration,
      timeExceeded: Number(route.workLimitMinutes || 0) > 0 && duration > route.workLimitMinutes,
      overdueMinutes: Number(route.workLimitMinutes || 0) > 0 ? Math.max(0, duration - route.workLimitMinutes) : 0 })
  }
  return { ...masked, routes, scheduleMaskOnly: false, dailyRoadRebuilt: true, distanceMode: 'ROAD',
    message: '按跳点后道路计算距离、行驶与作业时间；不计不收点绕路及作业时间，保留原趟次与卸料时间。' }
}

// Preserve full road geometry, but subtract skipped collection handling time from the daily schedule.
export function maskFixedTripTemplate(template, dueIds) {
  const covered = new Set((template.routes || []).flatMap(route => route.points.filter(point => point.role === 'MIDDLE').map(point => String(point.facilityId))))
  const routes = (template.routes || []).map(route => {
    let order = 0
    const points = route.points.map(point => {
      if (point.role !== 'MIDDLE') return { ...point, scheduleSkipped: false }
      order++
      const skipped = !dueIds.has(String(point.facilityId))
      return { ...point, templateToken: `${String(route.tripNo).padStart(2, '0')}-${String(order).padStart(3, '0')}`, scheduleSkipped: skipped,
        operationDurationMinutes: skipped ? 0 : point.operationDurationMinutes,
        estimatedWeightKg: skipped ? 0 : point.estimatedWeightKg, estimatedVolumeLiter: skipped ? 0 : point.estimatedVolumeLiter }
    })
    const operation = points.reduce((sum, point) => sum + Number(point.operationDurationMinutes || 0), 0)
    const travel = Number(route.planningTravelDurationMinutes ?? route.travelDurationMinutes ?? 0)
    const duration = travel + operation
    const displayDuration = Number(route.displayTravelDurationMinutes ?? travel) + operation
    const due = points.filter(point => point.role === 'MIDDLE' && !point.scheduleSkipped)
    const weight = due.reduce((sum, point) => sum + Number(point.estimatedWeightKg || 0), 0)
    return { ...route, points, pointCount: due.length, estimatedWeightKg: weight, estimatedVolumeLiter: due.reduce((sum, point) => sum + Number(point.estimatedVolumeLiter || 0), 0),
      loadRate: route.ratedCapacityKg ? weight / route.ratedCapacityKg : null, operationDurationMinutes: operation, durationMinutes: duration, totalDurationMinutes: duration,
      planningTotalDurationMinutes: duration, displayTotalDurationMinutes: displayDuration,
      timeExceeded: Number(route.workLimitMinutes || 0) > 0 && duration > route.workLimitMinutes,
      overdueMinutes: Math.max(0, duration - Number(route.workLimitMinutes || duration)) }
  })
  const unassigned = (template.unassignedPoints || []).filter(point => dueIds.has(String(point.facilityId)))
  return { ...template, routes, fixedTripTemplate: true, scheduleMaskOnly: true,
    assignedPointCount: routes.reduce((sum, route) => sum + route.pointCount, 0),
    assignedWeightKg: routes.reduce((sum, route) => sum + route.estimatedWeightKg, 0),
    unassignedPoints: unassigned, unassignedPointCount: unassigned.length,
    duePointCount: dueIds.size, skippedPointCount: covered.size - [...dueIds].filter(id => covered.has(id)).length,
    dueUnassignedCount: [...dueIds].filter(id => !covered.has(id)).length,
    message: '仍沿完整道路经过所有点；当天不收点作业时间为0，行驶及卸料时间保留，已重新推算每天到达时间。' }
}
