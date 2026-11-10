import { flowLcsSequence } from './flowLcs.js'

export function buildFlowOrderComparison(template, day, carCode) {
  if (!template || !day || !carCode) return null
  const planPoints = template.points || []
  const plan = planPoints.map(point => String(point.facilityId))
  const planSet = new Set(plan)
  const planNames = new Map(planPoints.map(point => [String(point.facilityId), point.facilityName || String(point.facilityId)]))
  const seen = new Set()
  const actualPoints = (day.trips || []).filter(trip => String(trip.carCode || '') === String(carCode))
    .flatMap(trip => trip.points || []).filter(point => Number(point.matchType) === 0 && point.facilityId != null)
    .filter(point => { const id = String(point.facilityId); if (seen.has(id)) return false; seen.add(id); return true })
  const actual = actualPoints.map(point => String(point.facilityId))
  const shared = actual.filter(id => planSet.has(id))
  const sharedSet = new Set(shared)
  const planShared = plan.filter(id => sharedSet.has(id))
  const position = new Map(planShared.map((id, index) => [id, index]))
  let correctPairs = 0
  for (let i = 0; i < shared.length; i++) for (let j = i + 1; j < shared.length; j++) {
    if (position.get(shared[i]) < position.get(shared[j])) correctPairs++
  }
  const pairCount = shared.length * (shared.length - 1) / 2
  const sharedActualPosition = new Map(shared.map((id, index) => [id, index]))
  const actualPosition = new Map(actual.map((id, index) => [id, index + 1]))
  const planPosition = new Map(plan.map((id, index) => [id, index + 1]))
  const lcsIds = flowLcsSequence(planShared, shared)
  const lcsSequence = lcsIds.map(id => ({ id, name: planNames.get(id) || id,
    planOrder: planPosition.get(id), actualOrder: actualPosition.get(id) }))
  const planSequence = planPoints.map((point, index) => {
    const id = String(point.facilityId)
    return { id, name: point.facilityName || id, order: index + 1, actualOrder: actualPosition.get(id),
      frequencyLabel: point.frequencyLabel || '未提供', frequencyTitle: point.frequencyTitle || '',
      status: !seen.has(id) ? 'not-collected' : position.get(id) === sharedActualPosition.get(id) ? 'matched' : 'moved' }
  })
  const actualSequence = actualPoints.map((point, index) => {
    const id = String(point.facilityId)
    return { id, name: planNames.get(id) || point.facilityName || id, actualName: point.facilityName || id,
      order: index + 1, planOrder: planPosition.get(id),
      status: !planSet.has(id) ? 'extra' : position.get(id) === sharedActualPosition.get(id) ? 'matched' : 'moved' }
  })
  return { sharedCount: shared.length, correctPairs, pairCount, orderRate: pairCount ? correctPairs / pairCount : null,
    lcsLength: lcsIds.length, notCollected: plan.filter(id => !seen.has(id)), extra: actual.filter(id => !planSet.has(id)),
    planSequence, actualSequence, lcsSequence }
}

export function gpsLayerType(layer) {
  if (layer.kind !== 'trip') return layer.kind
  const suffix = String(layer.key || '').match(/-(to-first|work|to-station)$/)
  return suffix ? suffix[1] : 'trip'
}

export function measureSelectedGpsLayers(layers, selectedTypes, distance) {
  const selected = (layers || []).filter(layer => selectedTypes.has(gpsLayerType(layer)) && (layer.points || []).length >= 2)
  if (!selected.length) return null
  const segments = selected.map(layer => ({
    label: layer.label,
    type: gpsLayerType(layer),
    tripNo: layer.kind === 'trip' ? layer.tripIndex + 1 : null,
    meters: (layer.points || []).reduce((sum, point, index, points) =>
      index ? sum + distance(points[index - 1], point) : sum, 0)
  }))
  return { meters: segments.reduce((sum, item) => sum + item.meters, 0), segments }
}

const tripMileageTypes = ['to-first', 'work', 'to-station', 'trip']
const mileageLabels = {
  before: '首趟流水前', 'to-first': '出发到首点', work: '首点到末点', 'to-station': '末点到场站',
  trip: '未细分整趟', between: '趟间未归属', after: '末趟流水后', unassigned: '时间未归属'
}

function mileageColumn(type, tripNo) {
  const label = mileageLabels[type]
  return tripMileageTypes.includes(type) ? '第' + tripNo + '趟 · ' + label : label
}

export function flowBatchMileageColumns(rows, selectedTypes) {
  const columns = []
  if (selectedTypes.has('before')) columns.push(mileageColumn('before'))
  const tripCount = rows.reduce((max, row) => Math.max(max, row.tripCount), 0)
  for (let tripNo = 1; tripNo <= tripCount; tripNo += 1) {
    for (const type of tripMileageTypes) {
      if (selectedTypes.has(type)) columns.push(mileageColumn(type, tripNo))
    }
  }
  for (const type of ['between', 'after', 'unassigned']) {
    if (selectedTypes.has(type)) columns.push(mileageColumn(type))
  }
  return columns
}

export function flowBatchMileageByColumn(segments) {
  const values = {}
  for (const { type, tripNo, meters } of segments) {
    const column = mileageColumn(type, tripNo)
    values[column] = (values[column] ?? 0) + meters
  }
  return values
}

export function summarizeFlowBatch(rows, phase) {
  const chosen = rows.filter(row => row.phase === phase)
  const scored = chosen.filter(row => row.sharedCount >= 3 && Number.isFinite(row.orderRate))
  const withGps = chosen.filter(row => Number.isFinite(row.gpsMeters))
  const numerator = scored.reduce((sum, row) => sum + row.correctPairs, 0)
  const denominator = scored.reduce((sum, row) => sum + row.pairCount, 0)
  return {
    days: chosen.length, scoreDays: scored.length, gpsDays: withGps.length,
    orderRate: denominator ? numerator / denominator : null,
    correctPairs: numerator, pairCount: denominator,
    averageGpsKm: withGps.length ? withGps.reduce((sum, row) => sum + row.gpsMeters, 0) / withGps.length / 1000 : null,
    averageSharedCount: scored.length ? scored.reduce((sum, row) => sum + row.sharedCount, 0) / scored.length : null
  }
}