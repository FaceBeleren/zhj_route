import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { assertManualMembership, buildManualComparison, compareManualMetrics, createManualDraft, moveManualPoint } from '../src/manualRouteTest.js'

const app = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
const clock = app.slice(app.indexOf('function parseClockMinutes('), app.indexOf('function planningSegmentsForSchedule('))
const schedule = app.slice(app.indexOf('function planningSegmentsForSchedule('), app.indexOf('function timeWindowStatsForResult('))
const windows = app.slice(app.indexOf('function clockMinuteOfDay('), app.indexOf('function pointArrivalViolates('))
const { buildSchedules, isViolation } = new Function('routeDisplayTotalDuration', `${clock}; ${schedule}; ${windows}; return { buildSchedules: buildRouteSchedules, isViolation: arrivalViolatesTimeWindow }`)(route => route.durationMinutes)
const point = (id, role = 'MIDDLE', operation = 10) => ({ facilityId: id, facilityName: `点${id}`, longitude: 118 + id / 1000, latitude: 36.8, role, operationDurationMinutes: operation })
function fixture() {
  const result = { distanceMode: 'DIRECT', routes: [{ routeNo: 1, tripNo: 1, vehicleId: 'v1', points: [point(-1, 'START', 0), point(1), point(2, 'MIDDLE', 0), point(3), point(-2, 'END', 15)], durationMinutes: 999 }], unassignedPointCount: 0 }
  const original = createManualDraft(result)
  const draft = createManualDraft(result)
  const requests = []
  const resolveRoad = async points => {
    const ids = points.map(point => point.facilityId)
    requests.push(ids)
    const shorter = ids.join(',') === '-1,1,3,2,-2' || ids.join(',') === '-1,3,2,-2'
    return { segments: points.slice(1).map((point, i) => ({ order: i + 1, distance: shorter ? 500 : 1000, durationMinutes: shorter ? 5 : 10, pathSource: 'OD_CACHE', path: [points[i], point] })) }
  }
  const options = { plannedStartTime: '02:00' }
  const calculate = extra => buildManualComparison({ original, draft, options, resolveRoad, buildSchedules, isViolation, ...extra })
  return { result, original, draft, requests, calculate, resolveRoad }
}

test('move changes only order, keeps duplicates and operation, locks endpoints and rejects deletion', () => {
  const { result, original, draft } = fixture()
  const snapshot = JSON.stringify(result)
  assert.equal(moveManualPoint(draft, 0, 0, 1), false)
  assert.equal(moveManualPoint(draft, 0, 1, -1), false)
  assert.equal(moveManualPoint(draft, 0, 4, -1), false)
  assert.equal(moveManualPoint(draft, 0, 3, -1), true)
  assert.deepEqual(draft.routes[0].points.map(point => point.facilityId), [-1, 1, 3, 2, -2])
  assertManualMembership(original, draft)
  assert.equal(JSON.stringify(result), snapshot)
  draft.routes[0].points.splice(1, 1)
  assert.throws(() => assertManualMembership(original, draft), /删除/)
})

test('real roads are recomputed for both sides instead of comparing against old direct totals', async () => {
  const f = fixture()
  moveManualPoint(f.draft, 0, 3, -1)
  const before = JSON.stringify(f.original)
  const draftBefore = JSON.stringify(f.draft)
  const result = await f.calculate()
  assert.deepEqual(f.requests, [[-1, 1, 2, 3, -2], [-1, 1, 3, 2, -2]])
  assert.equal(result.scenarios[0].generated.distance, 4000)
  assert.equal(result.scenarios[0].generated.duration, 75)
  assert.equal(result.scenarios[0].manual.duration, 55)
  assert.equal(result.verdict.generated, 'red')
  assert.equal(result.verdict.manual, 'green')
  assert.equal(JSON.stringify(f.original), before)
  assert.equal(JSON.stringify(f.draft), draftBefore)
})

test('shorter first trip can make unchanged second trip early: manual yellow and system green', async () => {
  const f = fixture()
  for (const result of [f.original, f.draft]) result.routes.push({ routeNo: 2, tripNo: 2, vehicleId: 'v1', points: [point(-2, 'START', 0), { ...point(4, 'MIDDLE', 0), allowTimeBegin: '03:20', allowTimeEnd: '04:00' }, point(-2, 'END', 15)] })
  moveManualPoint(f.draft, 0, 3, -1)
  const result = await f.calculate()
  const full = result.scenarios[0]
  assert.equal(full.generated.result.routes[1].points[1].comparisonArrivalTime, '03:25')
  assert.equal(full.manual.result.routes[1].points[1].comparisonArrivalTime, '03:05')
  assert.equal(full.manual.result.routes[1].points[1].timeWindowViolation, true)
  assert.equal(result.verdict.generated, 'green')
  assert.equal(result.verdict.manual, 'yellow')
})

test('full template and every skip day are checked, and daily conflict blocks green verdict', async () => {
  const f = fixture()
  for (const result of [f.original, f.draft]) Object.assign(result.routes[0].points[2], { allowTimeBegin: '02:25', allowTimeEnd: '04:00' })
  moveManualPoint(f.draft, 0, 3, -1)
  const result = await f.calculate({ scheduleDays: [{ day: 1, facilityIds: [1, 2, 3] }, { day: 2, facilityIds: [2, 3] }, { day: 3, facilityIds: [2] }] })
  assert.equal(result.scenarios.length, 4)
  assert.equal(result.scenarios[0].manual.violations, 0)
  assert.equal(result.scenarios[3].manual.violations, 1)
  assert.deepEqual(result.scenarios[3].manual.result.routes[0].points.map(point => point.facilityId), [-1, 2, -2])
  assert.equal(result.verdict.manual, 'yellow')
  assert.equal(result.verdict.generated, 'green')
  assert.equal(f.requests.filter(ids => ids.join(',') === '-1,2,-2').length, 1)
})

test('color comparison is distance first, duration as tie-breaker, ties neutral', () => {
  const base = { distance: 1000, duration: 10 }
  assert.equal(compareManualMetrics(base, { distance: 900, duration: 20 }, 0).manual, 'green')
  assert.equal(compareManualMetrics(base, { distance: 1100, duration: 5 }, 0).manual, 'red')
  assert.equal(compareManualMetrics(base, { distance: 1000, duration: 5 }, 0).manual, 'green')
  assert.equal(compareManualMetrics(base, base, 0).manual, 'neutral')
  assert.equal(compareManualMetrics(base, { distance: 900, duration: 20 }, 1).manual, 'yellow')
})

test('failure or straight-line fallback cannot produce a success comparison', async () => {
  const f = fixture()
  await assert.rejects(f.calculate({ resolveRoad: async points => ({ segments: points.slice(1).map(() => ({ distance: 10, durationMinutes: 1, pathSource: 'DIRECT', path: [point(1), point(2)] })) }) }), /回退直线/)
  await assert.rejects(f.calculate({ resolveRoad: async () => { throw new Error('数据库不可用') } }), /数据库不可用/)
})

test('missing due points cannot make a manual candidate green', async () => {
  const f = fixture()
  moveManualPoint(f.draft, 0, 3, -1)
  const result = await f.calculate({ scheduleDays: [{ day: 1, facilityIds: [1, 2, 3, 99] }] })
  assert.equal(result.scenarios[1].manual.missing, 1)
  assert.equal(result.verdict.manual, 'yellow')
  assert.match(result.verdict.message, /未分配/)
})

test('missing point operations cannot silently become zero-time passing routes', async () => {
  const f = fixture()
  delete f.original.routes[0].points[1].operationDurationMinutes
  delete f.draft.routes[0].points[1].operationDurationMinutes
  await assert.rejects(f.calculate(), /作业时间缺失/)
})

test('duplicate facility occurrences are preserved using separate occurrence tokens', () => {
  const f = fixture()
  f.result.routes[0].points.splice(3, 0, { ...point(2), facilityName: '重复收运点' })
  const original = createManualDraft(f.result)
  const draft = createManualDraft(f.result)
  assert.equal(moveManualPoint(draft, 0, 3, -1), true)
  assertManualMembership(original, draft)
  assert.equal(draft.routes[0].points.filter(point => point.facilityId === 2).length, 2)
  assert.notEqual(draft.routes[0].points[2].manualToken, draft.routes[0].points[3].manualToken)
})

test('map preserves time-window annotations and gives conflict markers red priority', () => {
  const source = readFileSync(new URL('../src/components/RouteMapPanel.vue', import.meta.url), 'utf8')
  const normalize = source.slice(source.indexOf('function normalizeRoutePoints('), source.indexOf('function pathCoordinatesFromSegments('))
  const appearance = source.slice(source.indexOf('function markerAppearance('), source.indexOf('function fallbackMarkerStyle('))
  const map = new Function('props', `${normalize}; ${appearance}; return { normalizeRoutePoints, markerAppearance }`)({ showPointSemantics: false })
  const marker = map.normalizeRoutePoints([{ ...point(2), timeWindowViolation: true, comparisonArrivalTime: '03:05' }])[0]
  assert.equal(marker.timeWindowViolation, true)
  assert.equal(marker.comparisonArrivalTime, '03:05')
  assert.equal(map.markerAppearance(marker, '#16803c').fill, '#dc2626')
  assert.match(app, /openManualRouteTest\('FIXED'\)/)
  assert.match(app, /openManualRouteTest\('ASSIGNMENT'\)/)
})

test('both entry points capture isolated result and parameter snapshots; fixed entry always edits full template', () => {
  const f = fixture()
  const value = value => ({ value })
  const scope = {
    assignmentTripReorderRunning: value(false), assignmentBatchRunning: value(false), fixedMasterRunning: value(false),
    fixedMasterFresh: value(true), fixedMaster: value({ template: f.result, days: [{ day: 1, indices: [0, 2] }] }),
    multiOptimization: value(f.result), optimizeOptions: { plannedStartTime: '02:00', denseSpeedKmh: 15 },
    manualTest: value(null), fixedMasterRouteNo: value(1), selectedMultiRouteNo: value(1), assignmentActiveVersion: value({ name: '方案1' }),
    splitPlanPoints: value([point(1), point(2), point(3)]), deepClone: value => JSON.parse(JSON.stringify(value))
  }
  const code = app.slice(app.indexOf('function openManualRouteTest('), app.indexOf('function openTimeWindowRepair('))
  const open = new Function('scope', `with(scope) { ${code}; return openManualRouteTest }`)(scope)
  open('FIXED')
  assert.deepEqual(scope.manualTest.value.scheduleDays, [{ day: 1, facilityIds: [1, 3] }])
  assert.equal(scope.manualTest.value.result.routes[0].points.length, 5)
  scope.optimizeOptions.denseSpeedKmh = 20
  assert.equal(scope.manualTest.value.options.denseSpeedKmh, 15)
  scope.manualTest.value.result.routes[0].points.reverse()
  assert.equal(f.result.routes[0].points[0].facilityId, -1)
  open('ASSIGNMENT')
  assert.equal(scope.manualTest.value.title, '方案1')
  assert.deepEqual(scope.manualTest.value.scheduleDays, [])
})
