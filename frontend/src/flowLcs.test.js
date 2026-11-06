import assert from 'node:assert/strict'
import test from 'node:test'
import { flowLcsSequence } from './flowLcs.js'

test('returns a complete common sequence in original order', () => {
  const plan = ['A', 'B', 'C', 'D']
  const actual = ['B', 'A', 'C', 'D']
  const result = flowLcsSequence(plan, actual)
  assert.equal(result.length, 3)
  assert.ok(['A,C,D', 'B,C,D'].includes(result.join(',')))
})

test('uses only the shared points and has the same length as the shown LCS', () => {
  const result = flowLcsSequence(['A', 'B', 'C', 'D', 'E'], ['X', 'C', 'A', 'D', 'E'])
  assert.deepEqual(result, ['A', 'D', 'E'])
})
