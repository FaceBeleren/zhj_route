export const WHEEL_ZOOM_INTERVAL_MS = 260

export function wheelZoomTarget(currentZoom, deltaY, elapsedSinceLastZoom) {
  if (!Number.isFinite(deltaY) || deltaY === 0 || elapsedSinceLastZoom < WHEEL_ZOOM_INTERVAL_MS) return null
  return currentZoom + (deltaY < 0 ? 1 : -1)
}

export function playbackMetersPerSecond(totalDistance, speed) {
  return Math.max(totalDistance / 90, 5) * speed
}
