import test from 'node:test'
import assert from 'node:assert/strict'
import { createManualDraft } from './manualRouteTest.js'
import { exactOrderUnavailableReason, optimizeExactRoute } from './exactRouteAdjustment.js'

const point = (id, role = 'MIDDLE') => ({ facilityId: id, role, longitude: id, latitude: 0, operationDurationMinutes: 1 })
const draft = count => createManualDraft({ routes: [{ routeNo: 1, vehicleId: 'v', tripNo: 1,
  points: [point(0, 'START'), ...Array.from({ length: count }, (_, i) => point(i + 1)), point(count + 1, 'END')] }] })
const road = points => points.slice(1).map((to, i) => ({ path: [points[i], to], pathSource: 'OD_CACHE',
  distance: 100, durationMinutes: 1 }))
const buildSchedules = result => new Map(result.routes.map(route => [route.routeNo, {
  startMinutes: route.tripNo === 1 ? 360 : 370,
  endMinutes: route.tripNo === 1 ? 370 : 380,
  pointsByOrder: new Map(route.points.map(point => [point.order, { arrivalMinutes: route.tripNo === 1 ? 360 : 372, arrivalTime: '06:00' }]))
}]))

test('exact limit counts movable points and allows ten with fixed endpoints', () => {
  assert.equal(exactOrderUnavailableReason(draft(10).routes[0]), '')
  assert.match(exactOrderUnavailableReason(draft(11).routes[0]), /11.*10/)
  const withAnchor = draft(11).routes[0]
  withAnchor.points[5].weightSource = 'ANCHOR'
  assert.equal(exactOrderUnavailableReason(withAnchor), '')
})

test('exact candidate uses returned roads, preserves other trips, and annotates window conflicts', async () => {
  const input = draft(3)
  input.routes.push({ ...draft(2).routes[0], routeNo: 2, tripNo: 2 })
  input.routes[1].points.forEach((p, index) => { p.manualToken = '1:' + index })
  input.routes[1].points[1].allowTimeEnd = '06:15'
  const baseline = JSON.stringify(input)
  let requested = 0
  const result = await optimizeExactRoute({
    draft: input, routeIndex: 0, options: { plannedStartTime: '00:00' },
    resolveExact: async (points, options, signal, timing) => {
      assert.equal(timing.tripStartMinutes, 360)
      assert.equal(timing.downstreamWindows[0].arrivalOffsetMinutes, 2)
      assert.equal(timing.downstreamWindows[0].allowTimeEnd, '06:15')
      requested++
      const order = [points[0], points[3], points[1], points[2], points[4]]
        .map((p, index) => ({ ...p, order: index + 1, orderNum: index + 1 }))
      return { points: order, originalSegments: road(points), adjustedSegments: road(order), evaluated: 6,
        timeWindowCandidate: { points, adjustedSegments: road(points) } }
    },
    resolveRoad: async points => ({ segments: road(points) }),
    buildSchedules, isViolation: () => true
  })
  assert.equal(requested, 1)
  assert.equal(JSON.stringify(input), baseline)
  assert.deepEqual(result.distance.candidate.routes[0].points.map(p => p.facilityId), [0, 3, 1, 2, 4])
  assert.deepEqual(result.timeWindow.candidate.routes[0].points.map(p => p.facilityId), [0, 1, 2, 3, 4])
  assert.equal(result.distance.changedTokens.length, 3)
  assert.deepEqual(result.distance.candidate.routes[1], input.routes[1])
  assert.equal(result.distance.comparison.manualConflicts, 5)
  assert.match(result.message, /6 种/)
})

test('over-limit input fails before requesting OD and cancellation discards response', async () => {
  let requested = false
  await assert.rejects(optimizeExactRoute({ draft: draft(11), routeIndex: 0,
    resolveExact: () => { requested = true } }), /超过/)
  assert.equal(requested, false)
  const controller = new AbortController()
  await assert.rejects(optimizeExactRoute({
    draft: draft(2), routeIndex: 0, signal: controller.signal,
    options: { plannedStartTime: '06:00' }, resolveRoad: async points => ({ segments: road(points) }),
    buildSchedules, isViolation: () => false,
    resolveExact: async () => { controller.abort(); return {} }
  }), { name: 'AbortError' })
})
