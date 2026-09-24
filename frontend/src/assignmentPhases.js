function dueDays(point, phase, cycleDays) {
  const days = []
  for (let start = 0; start < cycleDays; start += point.period) {
    for (const offset of point.dueOffsets) days.push(start + (offset + phase) % point.period)
  }
  return days
}

function scoreAddition(point, phase, weights, counts, cycleDays) {
  let weightScore = 0
  let countScore = 0
  for (const day of dueDays(point, phase, cycleDays)) {
    weightScore += 2 * weights[day] * point.weightKg + point.weightKg ** 2
    countScore += 2 * counts[day] + 1
  }
  return { weightScore, countScore }
}

function betterScore(candidate, current) {
  const tolerance = Math.max(1e-6, Math.abs(current.weightScore) * 1e-12)
  return candidate.weightScore < current.weightScore - tolerance
    || (Math.abs(candidate.weightScore - current.weightScore) <= tolerance && candidate.countScore < current.countScore)
}

function applyPoint(point, phase, direction, weights, counts, cycleDays) {
  for (const day of dueDays(point, phase, cycleDays)) {
    weights[day] += direction * point.weightKg
    counts[day] += direction
  }
}

export function balanceAssignmentPhases(inputPoints, cycleDays, mode = 'WEIGHT') {
  if (!Number.isInteger(cycleDays) || cycleDays < 1) throw new Error('共同周期必须为正整数')
  const points = inputPoints.map(point => ({
    ...point,
    weightKg: Number.isFinite(Number(point.weightKg)) && Number(point.weightKg) > 0 ? Number(point.weightKg) : 0,
    phase: 0
  }))
  const weights = Array(cycleDays).fill(0)
  const counts = Array(cycleDays).fill(0)
  const flexible = []
  const flatCounters = new Map()
  for (const point of points) {
    if (mode === 'FLAT' && point.period > 1 && point.dueOffsets.size < point.period) {
      const key = `${point.period}/${point.dueOffsets.size}`
      const count = flatCounters.get(key) || 0
      point.phase = count % point.period
      flatCounters.set(key, count + 1)
      applyPoint(point, point.phase, 1, weights, counts, cycleDays)
    } else if (mode === 'SAME' || mode === false || point.period === 1 || point.dueOffsets.size === point.period) {
      applyPoint(point, 0, 1, weights, counts, cycleDays)
    } else flexible.push(point)
  }
  flexible.sort((a, b) => b.weightKg - a.weightKg || b.period - a.period || a.index - b.index)
  for (const point of flexible) {
    let bestPhase = 0
    let bestScore = scoreAddition(point, 0, weights, counts, cycleDays)
    for (let phase = 1; phase < point.period; phase++) {
      const score = scoreAddition(point, phase, weights, counts, cycleDays)
      if (betterScore(score, bestScore)) { bestPhase = phase; bestScore = score }
    }
    point.phase = bestPhase
    applyPoint(point, bestPhase, 1, weights, counts, cycleDays)
  }
  for (let pass = 0; pass < 5; pass++) {
    let changed = false
    for (const point of flexible) {
      applyPoint(point, point.phase, -1, weights, counts, cycleDays)
      let bestPhase = point.phase
      let bestScore = scoreAddition(point, bestPhase, weights, counts, cycleDays)
      for (let phase = 0; phase < point.period; phase++) {
        const score = scoreAddition(point, phase, weights, counts, cycleDays)
        if (betterScore(score, bestScore)) { bestPhase = phase; bestScore = score }
      }
      if (bestPhase !== point.phase) changed = true
      point.phase = bestPhase
      applyPoint(point, bestPhase, 1, weights, counts, cycleDays)
    }
    if (!changed) break
  }
  const dayPlans = Array.from({ length: cycleDays }, (_, index) => ({ day: index + 1, indices: [], weightKg: weights[index], pointCount: counts[index] }))
  for (const point of points) {
    for (const day of dueDays(point, point.phase, cycleDays)) dayPlans[day].indices.push(point.index)
  }
  for (const day of dayPlans) {
    day.indices.sort((a, b) => a - b)
    day.key = day.indices.join(',')
  }
  const averageWeightKg = weights.reduce((sum, weight) => sum + weight, 0) / cycleDays
  const averagePointCount = counts.reduce((sum, count) => sum + count, 0) / cycleDays
  return {
    points,
    dayPlans,
    averageWeightKg,
    minWeightKg: Math.min(...weights),
    maxWeightKg: Math.max(...weights),
    averagePointCount,
    minPointCount: Math.min(...counts),
    maxPointCount: Math.max(...counts)
  }
}
