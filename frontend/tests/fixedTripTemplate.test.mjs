import { test } from 'node:test'
import assert from 'node:assert/strict'
import { maskFixedTripTemplate, buildFixedDailyRouteResult } from '../src/fixedTripTemplate.js'
import { readFileSync } from 'node:fs'
const template = { distanceMode: 'ROAD', routes: [{ routeNo: 1, tripNo: 1, vehicleId: 'V', distance: 1000, durationMinutes: 65, planningTravelDurationMinutes: 20,
  segments: Array.from({ length: 4 }, () => ({ durationMinutes: 5, path: [{ longitude: 118, latitude: 36 }] })),
  points: [{ facilityId: -1, role: 'START', operationDurationMinutes: 0 }, ...[1, 2, 3].map(id => ({ facilityId: id, role: 'MIDDLE', operationDurationMinutes: 10 })), { facilityId: -2, role: 'END', operationDurationMinutes: 15 }] }] }
test('bypass removes detour and handling, advances next trip, keeps both statistics and stable template tokens', async () => {
  const full = JSON.parse(JSON.stringify(template))
  full.routes[0].segments.forEach(segment => Object.assign(segment, { distance: 250, pathSource: 'OD_CACHE' }))
  full.routes.push({ ...full.routes[0], routeNo: 2, tripNo: 2 })
  const before = JSON.stringify(full)
  const result = await buildFixedDailyRouteResult(full, new Set(['1', '3']), async points => {
    assert.deepEqual(points.map(point => point.facilityId), [-1, 1, 3, -2])
    return { segments: points.slice(1).map((point, index) => ({ order: index + 1, distance: 100, durationMinutes: 3, pathSource: 'OD_CACHE', path: [point] })) }
  })
  const route = result.routes[0]
  assert.equal(route.distance, 300)
  assert.equal(route.durationMinutes, 44)
  assert.equal(route.retainedRoadDistance, 1000)
  assert.equal(route.retainedRoadTotalDurationMinutes, 55)
  assert.equal(route.points[2].templateToken, '01-003')
  assert.equal(route.points[2].order, 3)
  assert.equal(route.points.at(-1).operationDurationMinutes, 15)
  assert.equal(JSON.stringify(full), before)
  const app = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
  const code = app.slice(app.indexOf('function planningSegmentsForSchedule('), app.indexOf('function timeWindowStatsForResult('))
  const build = new Function('parseClockMinutes', 'formatPlanClock', 'routeDisplayTotalDuration', `${code}; return buildRouteSchedules;`)(() => 120, value => String(value), route => route.durationMinutes)
  assert.equal(build(result, '02:00').get(1).points[2].arrivalMinutes, 136)
  assert.equal(build(result, '02:00').get(2).startMinutes, 164)
})
test('missing road geometry fails daily rebuild without mutating full template', async () => {
  const before = JSON.stringify(template)
  await assert.rejects(buildFixedDailyRouteResult(template, new Set(['1', '3']), async () => ({ segments: [] })), /完整道路/)
  assert.equal(JSON.stringify(template), before)
})
test('mask keeps ABC road while B becomes white and its handling time is removed', () => {
  const before = JSON.stringify(template)
  const result = maskFixedTripTemplate(template, new Set(['1', '3']))
  assert.deepEqual(result.routes[0].points.map(point => point.facilityId), [-1, 1, 2, 3, -2])
  assert.equal(result.routes[0].points[2].scheduleSkipped, true)
  assert.equal(result.routes[0].points[3].templateToken, '01-003')
  assert.equal(result.routes[0].segments, template.routes[0].segments)
  assert.equal(result.routes[0].distance, 1000)
  assert.equal(result.routes[0].durationMinutes, 55)
  assert.equal(JSON.stringify(template), before)
})
test('day with no collections retains complete trip and marks all collection points white', () => {
  const result = maskFixedTripTemplate(template, new Set())
  assert.equal(result.routes.length, 1)
  assert.equal(result.skippedPointCount, 3)
  assert.equal(result.routes[0].points[0].scheduleSkipped, false)
})
test('unassigned due points remain visible as missing work', () => {
  assert.equal(maskFixedTripTemplate(template, new Set(['1', '4'])).dueUnassignedCount, 1)
})
test('actual daily schedule advances C and the next trip after skipping B handling', () => {
  const app = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
  const code = app.slice(app.indexOf('function planningSegmentsForSchedule('), app.indexOf('function timeWindowStatsForResult('))
  const build = new Function('parseClockMinutes', 'formatPlanClock', 'routeDisplayTotalDuration', `${code}; return buildRouteSchedules;`)(() => 120, value => String(value), route => route.durationMinutes)
  const full = JSON.parse(JSON.stringify(template))
  full.routes.push({ ...full.routes[0], routeNo: 2, tripNo: 2 })
  const original = build(full, '02:00')
  const masked = build(maskFixedTripTemplate(full, new Set(['1', '3'])), '02:00')
  assert.equal(original.get(1).points[3].arrivalMinutes - masked.get(1).points[3].arrivalMinutes, 10)
  assert.equal(original.get(2).startMinutes - masked.get(2).startMinutes, 10)
  assert.equal(masked.get(1).points[2].arrivalMinutes, masked.get(1).points[2].departureMinutes)
})
test('map marker normalization preserves skip state and colors skipped markers white', () => {
  const source = readFileSync(new URL('../src/components/RouteMapPanel.vue', import.meta.url), 'utf8')
  const normalize = source.slice(source.indexOf('function normalizeRoutePoints('), source.indexOf('function pathCoordinatesFromSegments('))
  const appearance = source.slice(source.indexOf('function markerAppearance('), source.indexOf('function fallbackMarkerStyle('))
  const map = new Function('props', `${normalize}\n${appearance}\nreturn { normalizeRoutePoints, markerAppearance };`)({ showPointSemantics: false })
  const point = map.normalizeRoutePoints([{ longitude: 118, latitude: 36.8, scheduleSkipped: true }])[0]
  assert.equal(point.scheduleSkipped, true)
  assert.equal(map.markerAppearance(point, '#16a34a').fill, '#ffffff')
  assert.equal(map.markerAppearance({ scheduleSkipped: false }, '#16a34a').fill, '#16a34a')
})
