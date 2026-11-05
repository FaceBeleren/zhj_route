import test from 'node:test'
import assert from 'node:assert/strict'
import { buildFlowPlaybackChoices } from '../src/flowPlaybackChoices.js'

const point = longitude => ({ longitude, latitude: 36 })
const layer = (key, from, to, flags = {}) => ({ key, points: [point(from), point(to)],
  segments: [{ path: [point(from), point(to)] }], ...flags })

test('route playback follows departure, trips, return; GPS remains a separate choice', () => {
  const displayed = [layer('trip1', 2, 3), layer('trip2', 3, 4), layer('departure', 1, 2, { departureFromParking: true }),
    layer('return', 4, 5, { returnToParking: true }), layer('gps', 10, 11, { gpsActual: true })]
  const gps = [layer('gpsA', 10, 11), layer('gpsB', 20, 21)]
  const choices = buildFlowPlaybackChoices(displayed, gps, '鲁CL0725', 'ROAD')
  assert.deepEqual(choices[0].paths.map(path => path[0].longitude), [1, 2, 3, 4])
  assert.deepEqual(choices[1].paths.map(path => path[0].longitude), [10, 20])
  assert.equal(choices[0].label, '流水道路路线')
})

test('multiple vehicles without selecting one cannot play a combined route', () => {
  const choices = buildFlowPlaybackChoices([layer('trip1', 1, 2)], [], '', 'DIRECT')
  assert.deepEqual(choices.map(choice => choice.paths.length), [0, 0])
})
