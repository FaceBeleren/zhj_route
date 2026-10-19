import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createManualDraft } from '../src/manualRouteTest.js'
import { twoOptCandidates, optimizeTwoOpt } from '../src/twoOptAdjustment.js'

function fixture() {
  const point = (id, role = 'MIDDLE') => ({ facilityId: id, longitude: id, latitude: 36, role, operationDurationMinutes: 0, estimatedWeightKg: 1 })
  const draft = createManualDraft({ routes: [{ routeNo: 1, tripNo: 1, vehicleId: 'v', maxCapacityKg: 100, workLimitMinutes: 480, points: [point(0, 'START'), point(1), point(2), point(3), point(4), point(5, 'END')] }] })
  const calls = [], target = new Set(['0:1','1:3','3:2','2:4','4:5'])
  const resolveRoad = async points => {
    return { segments: points.slice(1).map((p, i) => {
      const key = `${points[i].facilityId}:${p.facilityId}`; calls.push(key)
      return { distance: target.has(key) ? 1 : 20, durationMinutes: 1, pathSource: 'OD_CACHE', path: [points[i], p] }
    }) }

  }
  const buildSchedules = result => new Map(result.routes.map(r => [r.routeNo, { pointsByOrder: new Map(r.points.map((p, i) => [p.order, { arrivalMinutes: i, arrivalTime: String(i) }])) }]))
  return { draft, calls, args: { draft, routeIndex: 0, options: {}, resolveRoad, buildSchedules, isViolation: () => false } }
}

test('four movable points yield six reversals; thirty yield 435', () => {
  const f = fixture()
  assert.equal([...twoOptCandidates(f.draft, 0)].length, 6)
  const route = f.draft.routes[0], sample = route.points[1]
  route.points = [route.points[0], ...Array.from({ length: 30 }, (_, i) => ({ ...sample, manualToken: `p${i}` })), route.points.at(-1)]
  assert.equal([...twoOptCandidates(f.draft, 0)].length, 435)
})
test('anchors split blocks and other trips never move', () => {
  const f = fixture(); f.draft.routes[0].points[3].weightSource = 'ANCHOR'
  f.draft.routes.push(structuredClone(f.draft.routes[0]))
  const candidates = [...twoOptCandidates(f.draft, 0)]
  assert.equal(candidates.length, 1)
  for (const c of candidates) {
    assert.deepEqual(c.routes[1], f.draft.routes[1])
    assert.equal(c.routes[0].points[3].manualToken, '0:3')
  }
})
test('finds directed-road improvement, caches edges, preserves baseline', async () => {
  const f = fixture(), before = JSON.stringify(f.draft)
  const result = await optimizeTwoOpt(f.args)
  assert.ok(result.accepted > 0)
  assert.deepEqual(result.candidate.routes[0].points.map(p => p.facilityId), [0,1,3,2,4,5])
  assert.equal(result.comparison.scenarios[0].manual.distance, 5)
  assert.ok(f.calls.includes('3:2'))
  assert.equal(f.calls.length, new Set(f.calls).size)
  assert.equal(JSON.stringify(f.draft), before)
})
test('time window conflicts reject shorter reversals', async () => {
  const f = fixture()
  f.args.isViolation = (p, arrival) => p.facilityId === 2 && arrival !== 2
  const result = await optimizeTwoOpt(f.args)
  assert.equal(result.accepted, 0)
  assert.deepEqual(result.candidate, f.draft)
})
test('cancellation returns no partial candidate', async () => {
  const f = fixture(), controller = new AbortController(), before = JSON.stringify(f.draft)
  await assert.rejects(optimizeTwoOpt({ ...f.args, signal: controller.signal, onProgress: p => { if (p.evaluations) controller.abort() } }), e => e.name === 'AbortError')
  assert.equal(JSON.stringify(f.draft), before)
})
test('candidate limit reports incomplete search', async () => {
  const f = fixture()
  const result = await optimizeTwoOpt({ ...f.args, maxEvaluations: 1 })
  assert.equal(result.evaluations, 1)
  assert.equal(result.limited, true)
})
test('invalid weight and excess accumulated work do not pass as improvements', async () => {
  const f = fixture()
  f.draft.routes[0].points[1].estimatedWeightKg = null
  await assert.rejects(optimizeTwoOpt(f.args), /重量/)
  f.draft.routes[0].points[1].estimatedWeightKg = 1
  f.args.options.workHours = 0.01
  assert.equal((await optimizeTwoOpt(f.args)).accepted, 0)
})

test('straight-line fallback cannot produce a comparison', async () => {
  const f = fixture(), resolve = f.args.resolveRoad
  f.args.resolveRoad = async points => { const r = await resolve(points); r.segments[0].pathSource = 'DIRECT'; return r }
  await assert.rejects(optimizeTwoOpt(f.args), /道路数据/)
})
test('time budget is reported and cancellation of baseline request propagates', async () => {
  const f = fixture()
  assert.equal((await optimizeTwoOpt({ ...f.args, timeLimitMs: 0 })).limited, true)
  const controller = new AbortController()
  f.args.resolveRoad = async (_points, _options, signal) => { assert.equal(signal, controller.signal); controller.abort(); throw new DOMException('cancel', 'AbortError') }
  await assert.rejects(optimizeTwoOpt({ ...f.args, signal: controller.signal }), e => e.name === 'AbortError')
})
