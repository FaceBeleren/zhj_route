const TRIP_COLORS = ['#1677d2', '#ea580c', '#16a34a', '#c026d3', '#b59b00', '#0891b2', '#be123c', '#64748b']
const ROAD_SOURCES = new Set(['OD_CACHE', 'OD_PRELOAD', 'BAIDU_ONLINE', 'SAME_POINT'])

export function hasCompleteRoadGeometry(points, segments) {
  return Array.isArray(points) && points.length >= 2 &&
    Array.isArray(segments) && segments.length === points.length - 1 &&
    segments.every(segment => ROAD_SOURCES.has(segment?.pathSource) &&
      Array.isArray(segment.path) && segment.path.length >= 2 &&
      segment.path.every(point => point && point.longitude != null && point.latitude != null && point.longitude !== '' && point.latitude !== '' && Number.isFinite(Number(point.longitude)) && Number.isFinite(Number(point.latitude))))
}

export function buildRouteOverviewLayers(routes, pointFor, segmentFor, returnTrip = null) {
  const layers = (routes || []).map((route, index) => ({
    key: String(route.routeNo ?? index + 1),
    label: `第${route.tripNo ?? route.routeNo ?? index + 1}趟`,
    color: TRIP_COLORS[index % TRIP_COLORS.length],
    points: pointFor(route),
    segments: segmentFor(route, index)
  }))
  if (returnTrip) layers.push({ key: 'return', label: '最终返场', color: '#8b5cf6', points: returnTrip.points, segments: returnTrip.roadSegments })
  return layers
}

export function routeOverviewPoints(layers) {
  return layers.flatMap(layer => (layer.points || []).map((point, index) => ({
    ...point,
    routeColor: layer.color,
    overviewOrder: index + 1
  })))
}
