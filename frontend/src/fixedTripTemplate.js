
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
