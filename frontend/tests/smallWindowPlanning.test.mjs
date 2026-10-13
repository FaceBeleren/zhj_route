import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { hasPlanningWindow, planningWindowCount, smallWindowDisabledReason } from '../src/smallWindowPlanning.js'
test('two-stage preview stops before insertion and pending markers never enter playback or lines', () => {
  const app = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
  const map = readFileSync(new URL('../src/components/RouteMapPanel.vue', import.meta.url), 'utf8')
  assert.match(app, /task.status === 'WAITING_INSERTION'\) break/)
  assert.match(app, /开始尝试插入时间窗点（第二阶段）/)
  assert.match(app, /async function insertSmallWindowPlan/)
  assert.match(app, /async function closeSmallWindowPlan/)
  assert.match(app, /:pending-points=/)
  const playback = map.slice(map.indexOf('const playbackPoints ='), map.indexOf('const playbackTotalDistance ='))
  assert.doesNotMatch(playback, /pending/)
  const geometry = map.slice(map.indexOf('const originalLinePoints ='), map.indexOf('const optimizedFacilityIdSet ='))
  assert.doesNotMatch(geometry, /pending/)
  assert.match(map, /pendingAllPoints.value.forEach/)
  assert.match(map, /routePlot.pending.points/)
})
test('waiting preview performs no insertion request; explicit click starts stage two, close cancels waiting task', async () => {
  const app = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
  const source = app.slice(app.indexOf('async function pollSmallWindowTask('), app.indexOf('function cancelSmallWindowPlan('))
  const requests = []
  const scope = {
    smallWindowDialog: { taskId: 't1', fingerprint: 'same', visible: true, running: false, status: 'QUEUED' },
    fixedMasterFingerprint: { value: 'same' }, fixedMasterMessage: { value: '' }, fixedMasterRunning: { value: false },
    api: async (url) => { requests.push(url); return { taskId: 't1', status: 'DONE', feasibleCandidates: 7, progress: { phase: 'DONE', bestCandidate: { distance: 66000 } }, response: { result: { routes: [] } } } }
  }
  const actions = new Function('scope', `with(scope) { ${source}; return { pollSmallWindowTask, insertSmallWindowPlan, closeSmallWindowPlan } }`)(scope)
  const basePreview = { routes: [{ points: [{ facilityId: 1 }] }], pendingPoints: [{ facilityId: 2 }] }
  await actions.pollSmallWindowTask({ taskId: 't1', status: 'WAITING_INSERTION', basePreview })
  assert.equal(requests.length, 0)
  assert.equal(scope.smallWindowDialog.basePreview, basePreview)
  assert.equal(scope.smallWindowDialog.status, 'WAITING_INSERTION')
  await actions.insertSmallWindowPlan()
  assert.deepEqual(requests, ['/api/optimize/small-window/tasks/t1/insert'])
  assert.equal(scope.smallWindowDialog.status, 'DONE')
  assert.equal(scope.smallWindowDialog.feasibleCandidates, 7)
  assert.equal(scope.smallWindowDialog.progress.bestCandidate.distance, 66000)
  assert.equal(scope.fixedMasterRunning.value, false)
  scope.smallWindowDialog.status = 'WAITING_INSERTION'
  await actions.closeSmallWindowPlan()
  assert.equal(requests.at(-1), '/api/optimize/small-window/tasks/t1/cancel')
  assert.equal(scope.smallWindowDialog.visible, false)
})
test('counts each allowed/barred point once and enables only 1 to 5', () => {
  const p = { allowTimeBegin: '04:00', barredTimeBegin: '08:00', barredTimeEnd: '09:00' }
  assert.equal(planningWindowCount([p]), 1)
  assert.equal(hasPlanningWindow({ barredTimeBegin: '08:00' }), false)
  assert.equal(hasPlanningWindow({ allowTimeEnd: '' }), false)
  assert.equal(smallWindowDisabledReason([p], true, false), '')
  assert.equal(smallWindowDisabledReason(Array(5).fill(p), true, false), '')
  assert.match(smallWindowDisabledReason(Array(6).fill(p), true, false), /仅支持/)
  assert.match(smallWindowDisabledReason([], true, false), /没有时间窗/)
  assert.match(smallWindowDisabledReason([p], false, false), /排班/)
  assert.match(smallWindowDisabledReason([p], true, true), /执行/)
})
test('new algorithm previews first and applies all daily masks atomically, preserving normal generation', () => {
  const app = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
  assert.ok(app.indexOf('@click="generateSmallWindowPlan"') > app.indexOf('@click="generateFixedMaster"'))
  const generate = app.slice(app.indexOf('async function generateSmallWindowPlan('), app.indexOf('async function generateFixedMaster('))
  const apply = generate.slice(generate.indexOf('async function applySmallWindowPlan('))
  assert.ok(apply.indexOf('await calculateFixedMasterDays(') < apply.indexOf('fixedMaster.value ='))
  assert.match(apply, /fingerprint !== fixedMasterFingerprint.value/)
  assert.match(generate, /\/small-window\/tasks/)
  assert.match(generate, /cancel/)
  assert.match(generate, /allScenariosPassed/)
})
