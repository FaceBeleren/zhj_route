import test from 'node:test'
import assert from 'node:assert/strict'
import { planSingleTripBreaks, buildSingleTripDetours } from '../src/singleTripDetours.js'

const point = (id, role, weight = 0) => ({ facilityId: id, facilityName: id, role, estimatedWeightKg: weight, longitude: id.length, latitude: 1 })
const segment = (from, to, distance, durationMinutes) => ({ distance, durationMinutes, pathSource: 'OD_CACHE', path: [from, to] })

test('在下一整点装不下时才从上一点卸料，等于阈值不卸料', () => {
  const route = { points: [point('S', 'START'), point('A', 'MIDDLE', 40), point('B', 'MIDDLE', 50), point('C', 'MIDDLE', 20), point('E', 'END')] }
  assert.deepEqual(planSingleTripBreaks(route, 100, 0.9).breaks, [2])
})

test('新增里程扣掉被替换的直达段，并计入中途卸料时间', async () => {
  const points = [point('S', 'START'), point('A', 'MIDDLE', 60), point('B', 'MIDDLE', 50), point('E', 'END')]
  const route = { points, roadSegments: [segment(points[0], points[1], 100, 1), segment(points[1], points[2], 200, 2), segment(points[2], points[3], 300, 3)], displayTotalDurationMinutes: 20 }
  const resolveRoad = async ([from, end, to]) => ({ segments: [segment(from, end, 500, 5), segment(end, to, 400, 4)] })
  const result = await buildSingleTripDetours(route, 100, 0.9, 15, resolveRoad)
  assert.deepEqual(result.breaks, [1])
  assert.equal(result.baseDistance, 600)
  assert.equal(result.extraDistance, 700)
  assert.equal(result.totalDistance, 1300)
  assert.equal(result.extraDurationMinutes, 22)
  assert.equal(result.totalDurationMinutes, 42)
  assert.equal(result.expandedSegments.length, 4)
  assert.equal(result.detourSegments.length, 2)
})

test('单个点超过阈值或卸料道路缺失时明确失败', async () => {
  const points = [point('S', 'START'), point('A', 'MIDDLE', 120), point('E', 'END')]
  assert.throws(() => planSingleTripBreaks({ points }, 100, 0.9), /单点垃圾量超过/)
  const valid = [point('S', 'START'), point('A', 'MIDDLE', 80), point('B', 'MIDDLE', 80), point('E', 'END')]
  const route = { points: valid, roadSegments: [segment(valid[0], valid[1], 1, 1), segment(valid[1], valid[2], 1, 1), segment(valid[2], valid[3], 1, 1)], displayTotalDurationMinutes: 3 }
  await assert.rejects(buildSingleTripDetours(route, 100, 0.9, 15, async () => ({ segments: [] })), /道路数据不完整/)
})
