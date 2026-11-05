import test from 'node:test'
import assert from 'node:assert/strict'
import { buildFinalReturnTrip } from '../src/finalReturnTrip.js'

const point = (id, role, skipped = false) => ({ facilityId: id, facilityName: id, role, scheduleSkipped: skipped, longitude: 118, latitude: 36 })
const route = (...points) => ({ points })

test('多趟从最后实际收运趟的处理厂返回第一趟初始点', async () => {
  const routes = [
    route(point('S', 'START'), point('A', 'MIDDLE'), point('P', 'END')),
    route(point('P', 'START'), point('B', 'MIDDLE'), point('Q', 'END')),
    route(point('Q', 'START'), point('C', 'MIDDLE', true), point('R', 'END'))
  ]
  const calls = []
  const result = await buildFinalReturnTrip(routes, async points => {
    calls.push(points.map(point => point.facilityId))
    return { segments: [{ pathSource: 'OD_CACHE', path: points, distance: 1234, durationMinutes: 12 }] }
  })
  assert.deepEqual(calls, [['Q', 'S']])
  assert.equal(result.pointCount, 0)
  assert.equal(result.distance, 1234)
  assert.equal(result.durationMinutes, 12)
  assert.deepEqual(result.points.map(point => point.facilityId), ['Q', 'S'])
})

test('没有实际收运点时不生成返场，缺失道路不能冒充直线', async () => {
  const routes = [route(point('S', 'START'), point('A', 'MIDDLE', true), point('P', 'END'))]
  assert.equal(await buildFinalReturnTrip(routes, async () => { throw new Error('不应请求道路') }), null)
  routes[0].points[1].scheduleSkipped = false
  await assert.rejects(buildFinalReturnTrip(routes, async () => ({ segments: [{ pathSource: 'DIRECT', path: routes[0].points, distance: 1, durationMinutes: 1 }] })), /道路数据不完整/)
})
