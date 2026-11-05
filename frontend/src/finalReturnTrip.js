const ROAD_SOURCES = new Set(['OD_CACHE', 'OD_PRELOAD', 'BAIDU_ONLINE', 'SAME_POINT'])

// A non-collection leg after the last active unloading trip. Keep it outside
// result.routes so collection trip counts and time-window checks stay intact.
export async function buildFinalReturnTrip(routes, resolveRoad) {
  const collectionRoutes = (routes || []).filter(route =>
    (route.points || []).some(point => point.role === 'MIDDLE' && !point.scheduleSkipped))
  if (!collectionRoutes.length) return null
  const initial = routes[0]?.points?.[0]
  const terminal = collectionRoutes.at(-1)?.points?.at(-1)
  if (!initial || !terminal || terminal.role !== 'END') throw new Error('返场路线缺少初始点或最后处理厂')
  const road = await resolveRoad([terminal, initial])
  const segments = road?.segments || []
  if (segments.length !== 1 || segments.some(segment =>
    !ROAD_SOURCES.has(segment.pathSource) || (segment.path || []).length < 2 ||
    !Number.isFinite(Number(segment.distance)) || Number(segment.distance) < 0 ||
    !Number.isFinite(Number(segment.durationMinutes)) || Number(segment.durationMinutes) < 0)) {
    throw new Error('处理厂返回初始点的道路数据不完整；不以直线回退')
  }
  const returnSegment = { ...segments[0], returnToStart: true }
  const distance = Number(returnSegment.distance)
  const durationMinutes = Number(returnSegment.durationMinutes)
  return {
    returnToStart: true,
    routeNo: 'RETURN',
    pointCount: 0,
    points: [{ ...terminal, role: 'START', order: 1 }, { ...initial, role: 'END', order: 2 }],
    roadSegments: [returnSegment],
    distance,
    durationMinutes
  }
}
