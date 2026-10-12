import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { hasPlanningWindow, planningWindowCount, smallWindowDisabledReason } from '../src/smallWindowPlanning.js'
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
