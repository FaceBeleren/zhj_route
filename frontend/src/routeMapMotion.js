export const WHEEL_ZOOM_INTERVAL_MS = 260

export function wheelZoomTarget(currentZoom, deltaY, elapsedSinceLastZoom) {
  if (!Number.isFinite(deltaY) || deltaY === 0 || elapsedSinceLastZoom < WHEEL_ZOOM_INTERVAL_MS) return null
  return currentZoom + (deltaY < 0 ? 1 : -1)
}

export function playbackMetersPerSecond(totalDistance, speed) {
  return Math.max(totalDistance / 90, 5) * speed
}

export function distanceAtSlider(value, maximum, totalDistance) {
  if (!Number.isFinite(value) || !Number.isFinite(maximum) || maximum <= 0 || !Number.isFinite(totalDistance) || totalDistance <= 0) return 0
  return Math.min(totalDistance, Math.max(0, value / maximum * totalDistance))
}
