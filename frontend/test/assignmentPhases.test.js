import test from 'node:test'
import assert from 'node:assert/strict'
import { balanceAssignmentPhases } from '../src/assignmentPhases.js'

function point(index, period, collectCount, weightKg) {
  return {
    index,
    period,
    weightKg,
    dueOffsets: new Set(Array.from({ length: collectCount }, (_, occurrence) => Math.ceil(occurrence * period / collectCount)))
  }
}

test('daily points stay every day and weekly points spread across phases', () => {
  const points = [point(0, 1, 1, 495), ...Array.from({ length: 7 }, (_, index) => point(index + 1, 7, 1, 495))]
  const result = balanceAssignmentPhases(points, 7)
  assert.deepEqual(result.dayPlans.map(day => day.pointCount), Array(7).fill(2))
  assert.deepEqual(result.dayPlans.map(day => day.weightKg), Array(7).fill(990))
  assert.equal(new Set(result.points.slice(1).map(item => item.phase)).size, 7)
})

test('each point keeps its configured collection count over the common cycle', () => {
  const result = balanceAssignmentPhases([point(0, 1, 1, 495), point(1, 2, 1, 700), point(2, 3, 2, 300)], 6)
  assert.deepEqual(result.points.map(item => result.dayPlans.filter(day => day.indices.includes(item.index)).length), [6, 3, 4])
  assert.equal(result.dayPlans.reduce((sum, day) => sum + day.weightKg, 0), 6 * 495 + 3 * 700 + 4 * 300)
})

test('different estimated weights are balanced without changing per-visit weight', () => {
  const result = balanceAssignmentPhases([point(0, 2, 1, 1000), point(1, 2, 1, 100)], 2)
  assert.deepEqual(result.dayPlans.map(day => day.weightKg).sort((a, b) => a - b), [100, 1000])
  assert.equal(result.points[0].phase === result.points[1].phase, false)
})

test('weight balance takes priority over equal point counts', () => {
  const result = balanceAssignmentPhases([point(0, 2, 1, 1000), point(1, 2, 1, 100), point(2, 2, 1, 100), point(3, 2, 1, 100)], 2)
  assert.deepEqual(result.dayPlans.map(day => day.weightKg).sort((a, b) => a - b), [300, 1000])
  assert.deepEqual(result.dayPlans.map(day => day.pointCount).sort((a, b) => a - b), [1, 3])
})

test('same-phase mode preserves the former first-day schedule', () => {
  const result = balanceAssignmentPhases([point(0, 1, 1, 495), point(1, 7, 1, 495)], 7, 'SAME')
  assert.equal(result.points[1].phase, 0)
  assert.deepEqual(result.dayPlans.map(day => day.pointCount), [2, 1, 1, 1, 1, 1, 1])
  assert.equal(new Set(result.dayPlans.map(day => day.key)).size, 2)
})

test('flat mode spreads seven weekly points one per day in original route order', () => {
  const points = Array.from({ length: 7 }, (_, index) => point(index, 7, 1, index === 0 ? 2000 : 100))
  const result = balanceAssignmentPhases(points, 7, 'FLAT')
  assert.deepEqual(result.points.map(item => item.phase), [0, 1, 2, 3, 4, 5, 6])
  assert.deepEqual(result.dayPlans.map(day => day.indices), [[0], [1], [2], [3], [4], [5], [6]])
})

test('flat mode spreads fourteen weekly points two per day', () => {
  const points = Array.from({ length: 14 }, (_, index) => point(index, 7, 1, 495))
  const result = balanceAssignmentPhases(points, 7, 'FLAT')
  assert.deepEqual(result.dayPlans.map(day => day.indices), Array.from({ length: 7 }, (_, day) => [day, day + 7]))
})

test('flat mode rotates frequency groups independently and preserves collection counts', () => {
  const points = [point(0, 7, 1, 100), point(1, 2, 1, 100), point(2, 7, 1, 100), point(3, 2, 1, 100), point(4, 3, 2, 100)]
  const result = balanceAssignmentPhases(points, 42, 'FLAT')
  assert.deepEqual(result.points.map(item => item.phase), [0, 0, 1, 1, 0])
  assert.deepEqual(result.points.map(item => result.dayPlans.filter(day => day.indices.includes(item.index)).length), [6, 21, 6, 21, 28])
})
