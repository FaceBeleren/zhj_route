import { test } from 'node:test'
import assert from 'node:assert/strict'
import { applyGarbageDensity } from '../src/garbageDensity.js'
test('density changes weight and conversion, clearing restores database snapshot', () => {
  const point = { estimatedVolumeLiter:3960, estimatedWeightKg:495, litersPerTon:8000, weightSource:'CONTAINER_ESTIMATE' }
  applyGarbageDensity([point],200)
  assert.equal(point.estimatedWeightKg,792)
  assert.equal(point.litersPerTon,5000)
  applyGarbageDensity([point],125)
  assert.equal(point.estimatedWeightKg,495)
  applyGarbageDensity([point],'')
  assert.equal(point.litersPerTon,8000)
  assert.equal(point.weightSource,'CONTAINER_ESTIMATE')
})
test('invalid values do not replace weights and missing volume is not invented', () => {
  const point = { estimatedWeightKg:10 }
  for(const value of [0,-1,NaN,Infinity,'abc']) assert.equal(applyGarbageDensity([point],value),false)
  applyGarbageDensity([point],200)
  assert.equal(point.estimatedWeightKg,10)
})
