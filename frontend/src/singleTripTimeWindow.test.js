import test from 'node:test'
import assert from 'node:assert/strict'
import { buildSingleTripScenario, repairSingleTripPlan, singleTripTimeline } from './singleTripTimeWindow.js'

const point = (id, longitude, weight = 0, role = 'MIDDLE', allowTimeEnd = '') => ({
  facilityId: id, facilityName: id, longitude, latitude: 0, estimatedWeightKg: weight,
  operationDurationMinutes: role === 'MIDDLE' ? 4 : 0, role, allowTimeEnd
})
const source = { routeNo: 1, tripNo: 1, points: [point('S', 0, 0, 'START'), point('A', 1, 4), point('B', 2, 4), point('C', 3, 4), point('D', 4, 4, 'MIDDLE', '06:35'), point('T', 5, 0, 'END')] }
const road = async points => ({ segments: points.slice(1).map((destination, index) => ({
  distance: Math.abs(destination.longitude - points[index].longitude) * 100,
  durationMinutes: Math.abs(destination.longitude - points[index].longitude) * 7,
  pathSource: 'OD_CACHE', path: [points[index], destination]
})) })
const violates = (stop, minute) => Boolean(stop.allowTimeEnd) && minute % 1440 > 395
const args = [10, 1, 5, road, 360, violates, () => 1]

test('每日跳点后重新划分卸料行程，且每段时间与总耗时一致', async () => {
  const full = await buildSingleTripScenario(source, null, ...args)
  const day = await buildSingleTripScenario(full.route, new Set(['A', 'C', 'D']), ...args)
  assert.deepEqual(day.route.points.map(stop => stop.facilityId), ['S', 'A', 'C', 'D', 'T'])
  assert.equal(day.route.estimatedWeightKg, 12)
  assert.deepEqual(day.timeline.legs.map(leg => leg.points.map(stop => stop.facilityId)), [['S', 'A', 'C', 'T'], ['T', 'D', 'T']])
  assert.equal(day.timeline.legs.reduce((sum, leg) => sum + leg.durationMinutes, 0), day.detours.totalDurationMinutes)
  assert.equal(singleTripTimeline(day.route, day.detours, 360, violates, () => 1, false).legs.length, 1)
})

test('时间窗修正同时检查完整模板和每日卸料后的到达与收完时间', async () => {
  const complete = await buildSingleTripScenario(source, null, ...args)
  const day1 = await buildSingleTripScenario(complete.route, new Set(['A', 'B', 'C', 'D']), ...args)
  const day2 = await buildSingleTripScenario(complete.route, new Set(['A', 'C', 'D']), ...args)
  const result = await repairSingleTripPlan({ complete, days: [{ day: 1, ...day1 }, { day: 2, ...day2 }] }, {
    capacityKg: 10, loadRate: 1, unloadMinutes: 5, resolveRoad: road,
    startMinutes: 360, isViolation: violates, penaltyForViolation: () => 1
  })
  assert.equal(result.before.violations, 3)
  assert.equal(result.after.violations, 0)
  assert.equal(result.complete.route.points[1].facilityId, 'D')
  assert.deepEqual(result.days[1].route.points.filter(stop => stop.role === 'MIDDLE').map(stop => stop.facilityId), ['D', 'A', 'C'])
})


test('完整顺序跨过原卸料边界后，重新划分完整模板和每日路线', async () => {
  const original = { routeNo: 1, tripNo: 1, points: [
    point('S', 0, 0, 'START'), point('A', 1, 6), point('B', 2, 6), point('C', 3, 2), point('T', 4, 0, 'END')
  ] }
  const before = await buildSingleTripScenario(original, null, ...args)
  assert.deepEqual(before.detours.breaks, [1])
  const edited = { ...before.route, points: [before.route.points[0], before.route.points[3], before.route.points[1], before.route.points[2], before.route.points[4]] }
  const complete = await buildSingleTripScenario(edited, null, ...args)
  const day = await buildSingleTripScenario(edited, new Set(['B', 'C']), ...args)
  assert.deepEqual(complete.route.points.map(stop => stop.facilityId), ['S', 'C', 'A', 'B', 'T'])
  assert.deepEqual(complete.detours.breaks, [2])
  assert.deepEqual(day.route.points.map(stop => stop.facilityId), ['S', 'C', 'B', 'T'])
  assert.deepEqual(day.detours.breaks, [])
})
