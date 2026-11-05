import test from 'node:test'
import assert from 'node:assert/strict'
import { buildFlowGpsLayers, flowTimeMillis } from '../src/flowGpsLayers.js'

const day = '2026-09-23'
const time = clock => `${day}T${clock}`
const gps = clock => ({ longitude: 118, latitude: 36, equipmentTime: flowTimeMillis(time(clock)) })
const rows = [
  { index: 0, trip: { points: [{ time: time('04:00:00') }], end: { time: time('06:00:00') } } },
  { index: 1, trip: { start: { time: time('06:00:00') }, points: [{ time: time('07:00:00') }], end: { time: time('09:00:00') } } }
]

test('GPS 毫秒时间戳与无时区流水时间按中国时区对齐', () => {
  assert.equal(flowTimeMillis(time('00:00:00')), 1790092800000)
  assert.equal(flowTimeMillis('1790092800000'), 1790092800000)
})

test('按趟次时间切层，首趟前和末趟后独立，不跨层连线', () => {
  const layers = buildFlowGpsLayers([
    gps('03:40:00'), gps('03:50:00'), gps('04:10:00'), gps('05:50:00'),
    gps('06:00:00'), gps('08:30:00'), gps('09:10:00'), gps('09:20:00')
  ], rows)
  assert.deepEqual(layers.map(layer => layer.kind), ['before', 'trip', 'trip', 'after'])
  assert.deepEqual(layers.map(layer => layer.points.length), [2, 2, 2, 2])
  assert.deepEqual(layers.filter(layer => layer.kind === 'trip').map(layer => layer.tripIndex), [0, 1])
  assert.ok(layers.every(layer => layer.segments.length === 1 && layer.segments[0].path.length === 2))
})

test('缺少趟次时间或 GPS 时间时保持未归属，不冒充某趟', () => {
  const layers = buildFlowGpsLayers([gps('04:30:00'), { longitude: 118, latitude: 36, equipmentTime: '' }],
    [{ index: 0, trip: { points: [{ time: null }] } }])
  assert.deepEqual(layers.map(layer => layer.kind), ['unassigned'])
  assert.equal(layers[0].points.length, 2)
})

test('两趟之间有时间空隙时单列灰色片段', () => {
  const separated = [rows[0], { index: 1, trip: { start: { time: time('07:00:00') }, end: { time: time('09:00:00') } } }]
  const layers = buildFlowGpsLayers([gps('05:00:00'), gps('06:20:00'), gps('06:40:00'), gps('08:00:00')], separated)
  assert.deepEqual(layers.map(layer => layer.kind), ['trip', 'between', 'trip'])
  assert.equal(layers[1].label, 'GPS · 趟间未归属')
  assert.equal(layers[1].segments.length, 1)
})
