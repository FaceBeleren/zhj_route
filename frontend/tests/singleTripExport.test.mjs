import test from 'node:test'
import assert from 'node:assert/strict'
import { buildSingleTripDetours } from '../src/singleTripDetours.js'
import { buildSingleTripExport } from '../src/singleTripExport.js'

const point = (id, role, weight = 0) => ({ facilityId: id, facilityName: id, role, estimatedWeightKg: weight, longitude: id.charCodeAt(0), latitude: 1 })
const segment = (from, to, distance, durationMinutes) => ({ distance, durationMinutes, pathSource: 'OD_CACHE', path: [from, to] })

test('单趟导出按地图开关展开卸料与返场，里程耗时和路段逐一对应', async () => {
  const points = [point('S', 'START'), point('A', 'MIDDLE', 60), point('B', 'MIDDLE', 50), point('E', 'END')]
  const route = { routeNo: 1, tripNo: 1, pointCount: 2, points,
    roadSegments: [segment(points[0], points[1], 100, 1), segment(points[1], points[2], 200, 2), segment(points[2], points[3], 300, 3)],
    displayTotalDurationMinutes: 20 }
  const resolver = async path => path.length === 2
    ? { segments: [segment(path[0], path[1], 250, 2.5)] }
    : { segments: [segment(path[0], path[1], 500, 5), segment(path[1], path[2], 400, 4)] }
  const detours = await buildSingleTripDetours(route, 100, 0.9, 15, resolver)
  const all = buildSingleTripExport(route, detours, true, true, 15)
  assert.deepEqual(all.route.points.map(item => item.role), ['START', 'MIDDLE', 'UNLOAD', 'MIDDLE', 'END', 'RETURN'])
  assert.equal(all.route.segments.length, all.route.points.length - 1)
  assert.equal(all.route.distance, 1550)
  assert.equal(all.route.durationMinutes, 44.5)
  assert.equal(all.breakdown.breakDetails[0].extraDistance, 700)
  assert.equal(all.breakdown.breakDetails[0].loadKg, 60)
  assert.equal(all.breakdown.returnDistance, 250)
  assert.equal(all.route.segments.reduce((sum, item) => sum + item.distance, 0), all.route.distance)
  const base = buildSingleTripExport(route, detours, false, false, 15)
  assert.equal(base.route.points.length, 4)
  assert.equal(base.route.distance, 600)
  assert.equal(base.route.durationMinutes, 20)
  assert.equal(base.breakdown.breakCount, 0)
})
