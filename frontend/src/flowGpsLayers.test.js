import assert from 'node:assert/strict'
import test from 'node:test'
import { buildFlowGpsLayers, splitFlowGpsTripLayers, nearestGpsTrackSample } from './flowGpsLayers.js'
import { flowPolylineMeters, flowTripDistanceBreakdown } from './flowActualMap.js'

const stamp = minute => `2026-11-01 06:${String(minute).padStart(2, '0')}:00`
const gps = minute => ({ longitude: 118 + minute * 0.001, latitude: 36, equipmentTime: stamp(minute) })

test('GPS trip slices share boundary fixes and preserve its measured distance', () => {
  const tripRows = [{ index: 0, trip: {
    start: { time: stamp(0) }, end: { time: stamp(3) },
    points: [{ time: stamp(1) }, { time: stamp(2), leaveTime: stamp(2) }]
  } }]
  const original = buildFlowGpsLayers([0, 1, 2, 3].map(gps), tripRows)
  const parts = splitFlowGpsTripLayers(original, tripRows)
  assert.deepEqual(parts.map(layer => layer.key), ['gps-0-to-first', 'gps-0-work', 'gps-0-to-station'])
  assert.deepEqual(parts.map(layer => layer.points.length), [2, 2, 2])
  assert.ok(Math.abs(flowPolylineMeters(parts) - flowPolylineMeters(original)) < 0.001)
})

test('GPS trip without visit times remains one complete layer', () => {
  const tripRows = [{ index: 0, trip: { start: { time: stamp(0) }, end: { time: stamp(3) }, points: [{}] } }]
  const original = buildFlowGpsLayers([0, 1, 2, 3].map(gps), tripRows)
  const parts = splitFlowGpsTripLayers(original, tripRows)
  assert.equal(parts.length, 1)
  assert.equal(parts[0].key, original[0].key)
  assert.ok(parts[0].label.includes('未细分'))
})

test('flow road distance separates point-to-point legs from station legs', () => {
  const points = [
    { ...gps(0), flowRole: 'STATION' }, { ...gps(1), flowRole: 'POINT' },
    { ...gps(2), flowRole: 'POINT' }, { ...gps(3), flowRole: 'STATION' }
  ]
  const layer = { points, segments: points.slice(1).map((point, index) => ({ path: [points[index], point] })) }
  const result = flowTripDistanceBreakdown([layer])
  assert.ok(result.journeyMeters > 0 && result.stationMeters > result.journeyMeters)
  assert.ok(Math.abs(result.journeyMeters + result.stationMeters - flowPolylineMeters([layer])) < 0.001)
})

test('GPS click finds the closest sample on the hit segment and leaves gaps between layers empty', () => {
  const start = Date.parse('2026-11-04T03:40:00+08:00')
  const point = (x, y, seconds) => ({ x, y, equipmentTime: start + seconds * 1000 })
  const layers = [
    { label: 'first', points: [point(0, 0, 0), point(100, 0, 10)] },
    { label: 'second', points: [point(200, 0, 30), point(300, 0, 40)] }
  ]
  const hit = (x, y) => nearestGpsTrackSample(layers, { x, y }, sample => sample)
  assert.equal(hit(36, 8)?.time, start)
  assert.equal(hit(70, 8)?.time, start + 10000)
  assert.equal(hit(220, 0)?.layer, layers[1])
  assert.equal(hit(150, 0), null)
  assert.equal(hit(70, 13), null)
})
