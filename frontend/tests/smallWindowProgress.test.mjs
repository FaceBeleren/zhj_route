import { test } from 'node:test'
import assert from 'node:assert/strict'
import { planningProgressView } from '../src/smallWindowProgress.js'
import { readFileSync } from 'node:fs'
test('sticky progress card is mounted above map and timer is cleaned up', () => {
  const app = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
  const component = readFileSync(new URL('../src/components/SmallWindowProgress.vue', import.meta.url), 'utf8')
  const modal = app.slice(app.indexOf('<div v-if="smallWindowDialog.visible"'), app.indexOf('<ManualRouteTest v-if="manualTest"'))
  assert.ok(modal.indexOf('<SmallWindowProgress') < modal.indexOf('<RouteMapPanel'))
  assert.match(component, /position: sticky/)
  assert.match(component, /clearInterval\(timer\)/)
  assert.match(component, /取消计算，原方案保留/)
  assert.match(component, /不作为百分比/)
})
test('road percentage uses actual pairs; elapsed and last-work age advance while running', () => {
  const task = { running: true, status: 'RUNNING', progress: { phase: 'MATRIX', receivedAt: 1000, elapsedMs: 60000, updatedAgoMs: 2000, roads: { completed: 15, total: 60 } } }
  const view = planningProgressView(task, 4000)
  assert.equal(view.roadPercent, 25)
  assert.equal(view.elapsed, '1分3秒')
  assert.equal(view.updatedSeconds, 5)
  assert.equal(view.stale, false)
  assert.equal(planningProgressView(task, 20000).stale, true)
})
test('search has no fake percentage, no feasible candidate is distinct from a zero distance best', () => {
  const task = { running: true, status: 'RUNNING', progress: { phase: 'SEARCH', capacityPruned: 32, bestCandidate: null } }
  assert.equal(planningProgressView(task).roadPercent, null)
  assert.equal(planningProgressView(task).best, null)
  task.progress.bestCandidate = { distance: 0, durationMinutes: 1 }
  assert.equal(planningProgressView(task).best.distance, 0)
})
test('terminal and waiting states freeze elapsed, override stale search phase, and do not warn as stalled', () => {
  for (const status of ['WAITING_INSERTION', 'DONE', 'CANCELLED', 'FAILED']) {
    const view = planningProgressView({ running: true, status, progress: { phase: 'SEARCH', receivedAt: 1000, elapsedMs: 7000, updatedAgoMs: 5000 } }, 200000)
    assert.equal(view.phase, status)
    assert.equal(view.elapsed, '0分7秒')
    assert.equal(view.stale, false)
  }
})
