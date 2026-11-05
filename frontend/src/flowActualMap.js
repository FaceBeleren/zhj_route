import { buildRouteOverviewLayers, hasCompleteRoadGeometry, routeOverviewPoints } from './routeOverview.js'

function hasCoordinates(point) {
  if (!point || point.longitude == null || point.latitude == null || point.longitude === '' || point.latitude === '') return false
  const longitude = Number(point.longitude)
  const latitude = Number(point.latitude)
  return Number.isFinite(longitude) && Number.isFinite(latitude) &&
    Math.abs(longitude) <= 180 && Math.abs(latitude) <= 90 && !(longitude === 0 && latitude === 0)
}

export function buildFlowActualMap(tripRows, collectedOnly = false) {
  let missingCoordinateCount = 0
  let sequenceOffset = 0
  const routes = (tripRows || []).map(({ trip, index }) => {
    const allEvents = [
      trip.start && { ...trip.start, flowRole: 'STATION', flowThrough: false },
      ...(trip.points || []).map(point => ({ ...point, flowRole: 'POINT', flowCollected: point.matchType != null && Number(point.matchType) === 0, flowThrough: point.matchType != null && Number(point.matchType) === 1 })),
      trip.end && { ...trip.end, flowRole: 'STATION', flowThrough: false }
    ].filter(Boolean)
    const events = allEvents.map((point, position) => ({ ...point, flowOriginalOrder: position + 1, flowDisplayOrder: sequenceOffset + position + 1 }))
    sequenceOffset += allEvents.length
    const points = events.filter(point => {
      if (hasCoordinates(point)) return true
      missingCoordinateCount += 1
      return false
    })
    return {
      routeNo: index + 1,
      carCode: trip.carCode,
      points,
      segments: points.slice(1).map((point, position) => ({
        pathSource: 'FLOW_POINT_LINE',
        path: [points[position], point]
      }))
    }
  })
  const layers = buildRouteOverviewLayers(routes, route => route.points, route => route.segments)
  layers.forEach((layer, index) => { layer.label = `第${routes[index].routeNo}趟 · ${routes[index].carCode || '车辆未知'}` })
  const points = routeOverviewPoints(layers)
  return { layers, points, markers: buildFlowVisitMarkers(points, routes, collectedOnly), missingCoordinateCount }
}

// 出场和返场均为所选停车场的推算路段；不凭空补齐缺失的首点或末趟到站事件。
export function buildFlowParkingAccessLayers(dayTripRows, selectedTripRows, parking) {
  if (!hasCoordinates(parking)) return []
  const firstByCar = new Map()
  const lastByCar = new Map()
  for (const row of dayTripRows || []) {
    const carCode = String(row?.trip?.carCode || '').trim()
    if (!carCode) continue
    if (!firstByCar.has(carCode)) firstByCar.set(carCode, row)
    lastByCar.set(carCode, row)
  }
  const selectedIndexes = new Set((selectedTripRows || []).map(row => row.index))
  const layers = []
  for (const [carCode, row] of [...firstByCar].sort((a, b) => a[1].index - b[1].index)) {
    if (!selectedIndexes.has(row.index)) continue
    const firstEvent = row.trip?.start || row.trip?.points?.[0]
    if (!hasCoordinates(firstEvent)) continue
    const from = { ...parking, flowRole: 'PARKING' }
    const to = { ...firstEvent, flowRole: row.trip.start ? 'STATION' : 'POINT' }
    layers.push({
      key: `flow-departure-${carCode}-${row.index}`,
      label: `${carCode} · 停车场到首个流水位置`,
      color: '#8b5cf6',
      points: [from, to],
      segments: [{ pathSource: 'FLOW_DEPARTURE_DIRECT', path: [from, to] }],
      departureFromParking: true
    })
  }
  for (const [carCode, row] of [...lastByCar].sort((a, b) => a[1].index - b[1].index)) {
    if (!selectedIndexes.has(row.index) || !hasCoordinates(row.trip?.end)) continue
    const from = { ...row.trip.end, flowRole: 'STATION' }
    const to = { ...parking, flowRole: 'PARKING' }
    layers.push({
      key: `flow-return-${carCode}-${row.index}`,
      label: `${carCode} · 最后一趟返停车场`,
      color: '#8b5cf6',
      points: [from, to],
      segments: [{ pathSource: 'FLOW_RETURN_DIRECT', path: [from, to] }],
      returnToParking: true
    })
  }
  return layers
}

function buildFlowVisitMarkers(points, routes, collectedOnly) {
  const markers = new Map()
  points.forEach((point, index) => {
    const key = point.facilityId == null
      ? `COORD:${point.longitude}:${point.latitude}:${point.facilityName || ''}`
      : `ID:${point.facilityId}`
    let marker = markers.get(key)
    if (!marker) {
      marker = { ...point, flowMapMarker: true, flowCollectionCount: 0, flowThroughCount: 0, flowStationCount: 0, flowVisitOrders: [], flowVisitDetails: [] }
      markers.set(key, marker)
    }
    if (point.flowRole === 'POINT' && point.flowCollected) marker.flowCollectionCount += 1
    if (point.flowRole === 'POINT' && point.flowThrough) marker.flowThroughCount += 1
    if (point.flowRole === 'STATION') marker.flowStationCount += 1
    const globalOrder = point.flowDisplayOrder ?? index + 1
    marker.flowVisitOrders.push(globalOrder)
    const tripIndex = layersIndexForPoint(routes, index)
    const route = routes[tripIndex]
    const kind = point.flowRole === 'STATION' ? '场站' : point.flowCollected ? '收运' : point.flowThrough ? '途经' : '未知'
    marker.flowVisitDetails.push(`${route?.carCode || '车辆未知'}第${route?.routeNo || '?'}趟第${point.flowOriginalOrder ?? point.overviewOrder}位（${kind}）`)
  })
  return [...markers.values()].filter(marker => !collectedOnly || marker.flowCollectionCount > 0 || marker.flowStationCount > 0).map(marker => ({
    ...marker,
    flowVisitLabel: marker.flowVisitOrders.length <= 6
      ? marker.flowVisitOrders.join('、')
      : `${marker.flowVisitOrders.slice(0, 5).join('、')}…共${marker.flowVisitOrders.length}次`,
    flowVisitTitle: `展示顺序 ${marker.flowVisitOrders.join('、')}；收运${marker.flowCollectionCount}次、途经${marker.flowThroughCount}次；${marker.flowVisitDetails.join('；')}`
  }))
}

function layersIndexForPoint(routes, pointIndex) {
  let offset = 0
  for (let index = 0; index < routes.length; index += 1) {
    offset += routes[index].points.length
    if (pointIndex < offset) return index
  }
  return routes.length - 1
}

export function flowRoadKey(points) {
  return JSON.stringify((points || []).map(point => [point.facilityId, Number(point.longitude), Number(point.latitude)]))
}

export function normalizeFlowRoadSegments(points, segments) {
  const normalized = (segments || []).map((segment, index) => {
    const from = points[index]
    const to = points[index + 1]
    if (segment.pathSource !== 'DIRECT' || !from || !to || Number(from.longitude) !== Number(to.longitude) || Number(from.latitude) !== Number(to.latitude)) return segment
    return { ...segment, pathSource: 'SAME_POINT', path: [from, to] }
  })
  if (!hasCompleteRoadGeometry(points, normalized)) throw new Error('道路折线数据不完整；未用直线替代')
  return normalized
}

export function flowPolylineMeters(layers) {
  const radius = 6371000
  let total = 0
  for (const layer of layers || []) for (const segment of layer.segments || []) {
    const path = segment.path || []
    for (let index = 1; index < path.length; index += 1) {
      const before = path[index - 1]
      const after = path[index]
      const lat1 = Number(before.latitude) * Math.PI / 180
      const lat2 = Number(after.latitude) * Math.PI / 180
      const dLat = lat2 - lat1
      const dLng = (Number(after.longitude) - Number(before.longitude)) * Math.PI / 180
      const a = Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) ** 2
      const clamped = Math.min(1, Math.max(0, a))
      total += radius * 2 * Math.atan2(Math.sqrt(clamped), Math.sqrt(1 - clamped))
    }
  }
  return total
}
