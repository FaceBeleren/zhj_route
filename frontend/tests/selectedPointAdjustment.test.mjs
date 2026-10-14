import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createManualDraft, assertManualMembership } from '../src/manualRouteTest.js'
import { selectedAdjustmentCandidates, optimizeSelectedPoints } from '../src/selectedPointAdjustment.js'

const app = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
const clock = app.slice(app.indexOf('function parseClockMinutes('), app.indexOf('function planningSegmentsForSchedule('))
const schedule = app.slice(app.indexOf('function planningSegmentsForSchedule('), app.indexOf('function timeWindowStatsForResult('))
const windows = app.slice(app.indexOf('function clockMinuteOfDay('), app.indexOf('function pointArrivalViolates('))
const { buildSchedules, isViolation } = new Function('routeDisplayTotalDuration', `${clock}; ${schedule}; ${windows}; return { buildSchedules: buildRouteSchedules, isViolation: arrivalViolatesTimeWindow }`)(route => route.durationMinutes)
const point = (id, role = 'MIDDLE') => ({ facilityId: id, facilityName: `p${id}`, longitude: 118 + id / 1000, latitude: 36, role, estimatedWeightKg: 1, operationDurationMinutes: 0 })
const ids = input => input.routes[0].points.map(p => p.facilityId).join(',')
function fixture() {
  const draft = createManualDraft({ unassignedPointCount: 0, routes: [{ routeNo: 1, tripNo: 1, vehicleId: 'v', maxCapacityKg: 100, workLimitMinutes: 480, points: [point(-1, 'START'), point(1), point(2), point(3), point(4), point(-2, 'END')] }] })
  const requests = []
  const target = '-1,3,4,1,2,-2', base = ids(draft)
  const resolveRoad = async points => {
    const sequence = points.map(p => p.facilityId).join(','); requests.push(sequence)
    const cost = sequence === target ? 5 : sequence === base ? 10 : 20
    return { segments: points.slice(1).map((p, i) => ({ distance: cost, durationMinutes: 10, pathSource: 'OD_CACHE', path: [points[i], p] })) }
  }
  const args = { draft, selectedTokens: ['0:3', '0:4'], options: { plannedStartTime: '03:00' }, resolveRoad, buildSchedules, isViolation }
  return { draft, requests, args, target }
}
test('joint chain move finds improvement even when no selected single-point move improves', async () => {
  const f = fixture(), before = JSON.stringify(f.draft), progress = []
  const result = await optimizeSelectedPoints({ ...f.args, onProgress: value => progress.push(value) })
  assert.equal(result.improved, true)
  assert.equal(ids(result.candidate), f.target)
  assert.equal(result.comparison.verdict.manual, 'green')
  assert.equal(JSON.stringify(f.draft), before)
  assert.equal(f.requests.length, new Set(f.requests).size)
  assert.ok(progress.some(p => p.best?.allScenariosPassed))
})
test('candidates keep every anchor, trip membership, and unselected relative order', () => {
  const f = fixture()
  f.draft.routes[0].points[3].weightSource = 'ANCHOR'
  const candidates = [...selectedAdjustmentCandidates(f.draft, ['0:1', '0:2', '0:4'])]
  assert.ok(candidates.length > 0)
  for (const candidate of candidates) {
    assertManualMembership(f.draft, candidate)
    assert.equal(candidate.routes[0].points[3].manualToken, '0:3')
    assert.deepEqual(candidate.routes[0].points.slice(4).map(p => p.manualToken), ['0:4', '0:5'])
  }
})
test('non-adjacent selected points may swap but unselected points never reverse', () => {
  const f = fixture(), chosen = new Set(['0:1', '0:3'])
  for (const candidate of selectedAdjustmentCandidates(f.draft, chosen)) {
    assert.deepEqual(candidate.routes[0].points.filter(p => !chosen.has(p.manualToken)).map(p => p.manualToken), ['0:0', '0:2', '0:4', '0:5'])
  }
})
test('shorter full-template passing candidate is yellow if a skip day is early', async () => {
  const f = fixture()
  f.draft.routes[0].points[3].allowTimeBegin = '03:10'
  const originalRoad = f.args.resolveRoad
  f.args.resolveRoad = async points => {
    const road = await originalRoad(points)
    if (points.map(p => p.facilityId).join(',') === '-1,3,2,-2') road.segments.forEach(s => { s.durationMinutes = 1 })
    return road
  }
  const result = await optimizeSelectedPoints({ ...f.args, scheduleDays: [{ day: 1, facilityIds: [2, 3] }] })
  assert.equal(result.improved, true)
  assert.equal(result.conflictOnly, true)
  assert.equal(result.comparison.scenarios[0].manual.violations, 0)
  assert.ok(result.comparison.scenarios[1].manual.violations > 0)
  assert.equal(result.comparison.verdict.manual, 'yellow')
})
test('same-vehicle accumulated work hours reject an apparently shorter route', async () => {
  const f = fixture()
  f.draft.routes.push({ ...structuredClone(f.draft.routes[0]), routeNo: 2, tripNo: 2, points: [point(-2, 'START'), point(5), point(-2, 'END')] })
  f.draft.routes[1].points.forEach((point, index) => { point.manualToken = `1:${index}` })
  f.draft.routes.forEach(route => { route.workLimitMinutes = 60 })
  const result = await optimizeSelectedPoints(f.args)
  assert.equal(result.improved, false)
  assert.equal(result.conflictOnly, false)
})
test('cancel never returns a partial winner or modifies edit baseline', async () => {
  const f = fixture(), controller = new AbortController(), before = JSON.stringify(f.draft)
  await assert.rejects(optimizeSelectedPoints({ ...f.args, signal: controller.signal, onProgress: value => { if (value.evaluations > 0) controller.abort() } }), error => error.name === 'AbortError')
  assert.equal(JSON.stringify(f.draft), before)
})
test('infeasible baseline is not painted green when shorter candidate also conflicts', async () => {
  const f = fixture(); f.draft.routes[0].points[3].allowTimeEnd = '02:59'
  const result = await optimizeSelectedPoints(f.args)
  assert.equal(result.conflictOnly, true)
  assert.equal(result.comparison.verdict.generated, 'yellow')
  assert.match(result.message, /基准本身也未通过/)
})
test('shortest conflicting candidate wins over a longer time-window-passing improvement', async () => {
  const f = fixture()
  f.draft.routes[0].points[3].allowTimeBegin = '03:10'
  const passing = '-1,1,3,4,2,-2'
  f.args.resolveRoad = async points => {
    const sequence = points.map(p => p.facilityId).join(',')
    const cost = sequence === f.target ? 5 : sequence === passing ? 7 : sequence === ids(f.draft) ? 10 : 20
    return { segments: points.slice(1).map((p, i) => ({ distance: cost, durationMinutes: sequence === f.target ? 1 : 10, pathSource: 'OD_CACHE', path: [points[i], p] })) }
  }
  const progress = []
  const result = await optimizeSelectedPoints({ ...f.args, onProgress: p => progress.push(p) })
  assert.equal(ids(result.candidate), f.target)
  assert.equal(result.improved, true)
  assert.equal(result.conflictOnly, true)
  assert.equal(result.comparison.verdict.manual, 'yellow')
  assert.ok(result.comparison.manualConflicts > 0)
  assert.ok(progress.some(p => p.best?.timeWindowConflicts > 0))
  assert.ok(result.rounds >= 2, 'time-window conflicts must not stop iterative search from the chosen winner')
  assert.match(result.message, /不参与搜索筛选/)
})
test('daily progress resets only under a new candidate identity and completion counts are unambiguous', async () => {
  const f = fixture(), progress = []
  const scheduleDays = Array.from({ length: 3 }, (_, i) => ({ day: i + 1, facilityIds: [1, 2, 3, 4] }))
  await optimizeSelectedPoints({ ...f.args, scheduleDays, onProgress: value => progress.push(value) })
  for (const candidate of [0, 1, 2]) {
    const updates = progress.filter(p => p.activeCandidate === candidate && p.phase === 'DAY')
    assert.deepEqual(updates.map(p => p.day), [1, 2, 3])
    assert.deepEqual(updates.map(p => p.completedDays), [0, 1, 2])
    assert.ok(updates.every(p => p.totalDays === 3 && p.evaluations === Math.max(0, candidate - 1)))
  }
  const finished = progress.find(p => p.activeCandidate === 1 && p.phase === 'CANDIDATE_DONE')
  assert.equal(finished.evaluations, 1)
  assert.equal(finished.completedDays, 3)
})
test('missing roads or selecting anchors fails without success candidate', async () => {
  const f = fixture()
  await assert.rejects(optimizeSelectedPoints({ ...f.args, selectedTokens: ['0:0'] }), /锚点/)
  await assert.rejects(optimizeSelectedPoints({ ...f.args, resolveRoad: async () => ({ segments: [] }) }), /道路数据/)
})
test('selection UI previews and adopts explicitly, cancels fetch, and confirms conflicting replacement', () => {
  const ui = readFileSync(new URL('../src/components/ManualRouteTest.vue', import.meta.url), 'utf8')
  assert.match(ui, /选点自动调整/)
  assert.match(ui, /采用候选到编辑列表/)
  assert.match(ui, /autoController\?\.abort/)
  assert.match(ui, /调整前路线（本次编辑基准）/)
  assert.match(ui, /window.confirm/)
  assert.match(app, /async function resolveManualTestRoad\(points, options, signal\)/)
})
