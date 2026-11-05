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

function segmentMeters(a, b) {
  const radians = Math.PI / 180
  const lat1 = Number(a.latitude) * radians
  const lat2 = Number(b.latitude) * radians
  const dLat = lat2 - lat1
  const dLng = (Number(b.longitude) - Number(a.longitude)) * radians
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) ** 2
  return 6371000 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(Math.max(0, 1 - h)))
}

export function playbackPathsDistance(paths) {
  let total = 0
  for (const path of paths || []) for (let index = 1; index < path.length; index += 1) total += segmentMeters(path[index - 1], path[index])
  return total
}

// Every path is an independent visible segment: never interpolate through an unrecorded gap.
export function playbackPointAtDistance(paths, distance) {
  const valid = (paths || []).filter(path => path?.length)
  if (!valid.length) return null
  let remaining = Math.max(0, Number(distance) || 0)
  for (let pathIndex = 0; pathIndex < valid.length; pathIndex += 1) {
    const path = valid[pathIndex]
    for (let index = 1; index < path.length; index += 1) {
      const from = path[index - 1]
      const to = path[index]
      const length = segmentMeters(from, to)
      if (remaining <= length) {
        const ratio = length === 0 ? 0 : remaining / length
        return { longitude: Number(from.longitude) + (Number(to.longitude) - Number(from.longitude)) * ratio,
          latitude: Number(from.latitude) + (Number(to.latitude) - Number(from.latitude)) * ratio }
      }
      remaining -= length
    }
    if (remaining <= 0 || pathIndex === valid.length - 1) return path[path.length - 1]
  }
  return valid[valid.length - 1][valid[valid.length - 1].length - 1]
}
