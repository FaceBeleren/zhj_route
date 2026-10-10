// Density in kg/m3; database conversion in litres/tonne. Never change database settings.
const originalWeights = new WeakMap()
export function applyGarbageDensity(points, value) {
  const density = Number(value)
  const automatic = value == null || value === ''
  if (!automatic && (!Number.isFinite(density) || density <= 0)) return false
  for (const point of points || []) {
    if (!originalWeights.has(point)) originalWeights.set(point, { estimatedWeightKg: point.estimatedWeightKg, litersPerTon: point.litersPerTon, weightSource: point.weightSource })
    if (automatic) { Object.assign(point, originalWeights.get(point)); continue }
    if (point.weightSource === 'ANCHOR' || point.estimatedVolumeLiter == null || !Number.isFinite(Number(point.estimatedVolumeLiter)) || Number(point.estimatedVolumeLiter) < 0) continue
    point.estimatedWeightKg = Math.round(Number(point.estimatedVolumeLiter) / 1000 * density * 100) / 100
    point.litersPerTon = 1000000 / density
    point.weightSource = 'DENSITY_OVERRIDE'
  }
  return true
}
