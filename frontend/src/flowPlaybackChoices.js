function segmentPaths(layers) {
  return (layers || []).flatMap(layer => (layer.segments || []).map(segment => segment.path || [])
    .filter(path => path.length >= 2 && path.every(point => Number.isFinite(Number(point.longitude)) && Number.isFinite(Number(point.latitude)))))
}

export function buildFlowPlaybackChoices(displayedLayers, gpsLayers, carCode, roadMode) {
  const routeLayers = carCode ? (displayedLayers || []).filter(layer => !layer.gpsActual) : []
  const orderedRoutes = [
    ...routeLayers.filter(layer => layer.departureFromParking),
    ...routeLayers.filter(layer => !layer.departureFromParking && !layer.returnToParking),
    ...routeLayers.filter(layer => layer.returnToParking)
  ]
  return [
    { key: 'optimized', label: roadMode === 'ROAD' ? '流水道路路线' : '流水点位路线', paths: segmentPaths(orderedRoutes) },
    { key: 'gps', label: 'GPS 实际轨迹', paths: segmentPaths(gpsLayers) }
  ]
}
