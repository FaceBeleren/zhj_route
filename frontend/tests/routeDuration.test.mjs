import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
const displayCode = source.slice(source.indexOf('function routeDisplayTravelDuration('), source.indexOf('const selectedMultiRouteDisplayTravelDuration'))
const { routeDisplayTravelDuration, routeDisplayTotalDuration } = new Function(`${displayCode}; return { routeDisplayTravelDuration, routeDisplayTotalDuration }`)()
const payloadCode = source.slice(source.indexOf('function assignmentReorderRoutePayload('), source.indexOf('async function requestAssignmentTripReorder('))
const compact = new Function('planningSegmentsForSchedule', `${payloadCode}; return compactAssignmentReorderResult`)(route => route.segments || [])

test('repairing first trip preserves unchanged second trip duration through compact request', () => {
  const second = {
    tripNo: 2, durationMinutes: 218, travelDurationMinutes: 180,
    planningTravelDurationMinutes: 180, displayTravelDurationMinutes: 185,
    operationDurationMinutes: 38, totalDurationMinutes: 218,
    planningTotalDurationMinutes: 218, displayTotalDurationMinutes: 223,
    segments: [{ distance: 86950, durationMinutes: 180 }], points: []
  }
  const request = JSON.parse(JSON.stringify(compact({ routes: [{ tripNo: 1 }, second] })))
  // The backend rebuilds the moved first trip, retaining the unchanged second trip.
  const response = { routes: [{ tripNo: 1, durationMinutes: 393 }, request.routes[1]] }
  for (const field of ['travelDurationMinutes', 'planningTravelDurationMinutes', 'displayTravelDurationMinutes', 'operationDurationMinutes', 'totalDurationMinutes', 'planningTotalDurationMinutes', 'displayTotalDurationMinutes']) {
    assert.equal(response.routes[1][field], second[field], field)
  }
  assert.equal(routeDisplayTotalDuration(response.routes[0]), 393)
  assert.equal(routeDisplayTotalDuration(response.routes[1]), 218)
})

test('older compact repair results fall back to durationMinutes instead of showing zero', () => {
  assert.equal(routeDisplayTotalDuration({ durationMinutes: 218, segments: [{ durationMinutes: 180 }] }), 218)
})

test('missing totals are reconstructed from travel and point operations', () => {
  const route = { segments: [{ durationMinutes: 80 }, { durationMinutes: 100 }], points: [{ operationDurationMinutes: 18 }, { operationDurationMinutes: 20 }] }
  assert.equal(routeDisplayTravelDuration(route), 180)
  assert.equal(routeDisplayTotalDuration(route), 218)
})

test('planning total takes precedence over display total and explicit zero is retained', () => {
  assert.equal(routeDisplayTotalDuration({ planningTotalDurationMinutes: 218, totalDurationMinutes: 220, displayTotalDurationMinutes: 230 }), 218)
  assert.equal(routeDisplayTotalDuration({ planningTotalDurationMinutes: 0, durationMinutes: 218 }), 0)
  assert.equal(routeDisplayTotalDuration(null), 0)
})
