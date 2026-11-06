import test from 'node:test'
import assert from 'node:assert/strict'
import { createManualDraft } from './manualRouteTest.js'
import { optimizeTwoOpt } from './twoOptAdjustment.js'

const point = (id, longitude, role = 'MIDDLE') => ({
  facilityId: id, longitude, latitude: 0, role,
  estimatedWeightKg: role === 'MIDDLE' ? 2 : 0, operationDurationMinutes: 0
})
const draft = createManualDraft({ routes: [{
  vehicleId: 'v', routeNo: 1, tripNo: 1, maxCapacityKg: 1,
  points: [point('S', 0, 'START'), point('A', 3), point('B', 1), point('C', 2), point('T', 4, 'END')]
}] })
const resolveRoad = async points => ({ segments: points.slice(1).map((to, index) => {
  const distance = Math.abs(to.longitude - points[index].longitude) * 100
  return { distance, durationMinutes: distance / 10, pathSource: 'OD_CACHE', path: [points[index], to] }
}) })
const buildSchedules = result => new Map(result.routes.map(route => [route.routeNo, {
  pointsByOrder: new Map(route.points.map(point => [point.order, { arrivalMinutes: 0, arrivalTime: '00:00', departureTime: '00:00' }]))
}]))

test('单趟 2-opt 只按基础道路改善，不以旧载重或工时淘汰候选', async () => {
  const result = await optimizeTwoOpt({
    draft, routeIndex: 0, options: { workHours: 1, plannedStartTime: '00:00' },
    resolveRoad, buildSchedules, isViolation: () => false, singleTripMode: true, maxEvaluations: 1
  })
  assert.equal(result.accepted, 1)
  assert.deepEqual(result.candidate.routes[0].points.map(stop => stop.facilityId), ['S', 'B', 'A', 'C', 'T'])
  assert.ok(result.comparison.scenarios[0].manual.distance < result.comparison.scenarios[0].generated.distance)
})
