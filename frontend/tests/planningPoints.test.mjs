import { test } from 'node:test'
import assert from 'node:assert/strict'
import { isDisposalPoint, collectionPlanningPoints } from '../src/planningPoints.js'
test('exclude disposal by numeric type or legacy type label, not facility name', () => {
  assert.equal(isDisposalPoint({ facilityType: 5 }), true)
  assert.equal(isDisposalPoint({ facilityType: '5' }), true)
  assert.equal(isDisposalPoint({ facilityTypeName: '处置场' }), true)
  assert.equal(isDisposalPoint({ facilityType: 6, facilityTypeName: '处置场' }), false)
  assert.equal(isDisposalPoint({ facilityType: 2, facilityTypeName: '中转站' }), false)
  assert.equal(isDisposalPoint({ facilityName: '生活垃圾处理厂旁收集点' }), false)
})
test('filter is non-mutating and keeps collection order', () => {
  const points = [{ facilityId: 1, facilityType: 6 }, { facilityId: 2, facilityType: 5 }, { facilityId: 3 }]
  assert.deepEqual(collectionPlanningPoints(points).map(p => p.facilityId), [1, 3])
  assert.equal(points.length, 3)
})
