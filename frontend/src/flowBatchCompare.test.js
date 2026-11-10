import test from 'node:test'
import assert from 'node:assert/strict'
import { buildFlowOrderComparison, gpsLayerType, measureSelectedGpsLayers, flowBatchMileageColumns, flowBatchMileageByColumn, summarizeFlowBatch } from './flowBatchCompare.js'

test('batch and single-day scoring use the same first-collection sequence across trips', () => {
  const template = { points: ['A', 'B', 'C', 'D'].map(facilityId => ({ facilityId, facilityName: facilityId })) }
  const point = (facilityId, matchType = 0) => ({ facilityId, matchType })
  const day = { trips: [
    { carCode: 'CAR', points: [point('B'), point('A'), point('B'), point('X', 1)] },
    { carCode: 'CAR', points: [point('D'), point('C'), point('X')] },
    { carCode: 'OTHER', points: [point('A')] }
  ] }
  const result = buildFlowOrderComparison(template, day, 'CAR')
  assert.equal(result.sharedCount, 4)
  assert.equal(result.correctPairs, 4)
  assert.equal(result.pairCount, 6)
  assert.equal(result.lcsLength, 2)
  assert.deepEqual(result.actualSequence.map(point => point.id), ['B', 'A', 'D', 'C', 'X'])
})

test('selected GPS types measure every matching trip without bridging layers', () => {
  const layers = [
    { kind: 'trip', key: 'gps-0-work', label: '首趟作业', points: [{x:0},{x:3}] },
    { kind: 'trip', key: 'gps-1-to-station', label: '去场站', points: [{x:3},{x:8}] },
    { kind: 'trip', key: 'gps-2-work', label: '次趟作业', points: [{x:10},{x:12}] }
  ]
  assert.equal(gpsLayerType(layers[0]), 'work')
  const measured = measureSelectedGpsLayers(layers, new Set(['work']), (a,b) => b.x-a.x)
  assert.equal(measured.meters, 5)
  assert.equal(measured.segments.length, 2)
  assert.equal(measureSelectedGpsLayers(layers, new Set(['before']), (a,b) => b.x-a.x), null)
  assert.equal(measureSelectedGpsLayers([{ kind:'trip', key:'gps-3-work', points:[{x:1}] }], new Set(['work']), (a,b) => b.x-a.x), null)
})

test('summary excludes missing mileage and insufficient order evidence', () => {
  const rows = [
    { phase:'交付前',sharedCount:3,correctPairs:2,pairCount:3,orderRate:2/3,gpsMeters:20000 },
    { phase:'交付前',sharedCount:2,correctPairs:1,pairCount:1,orderRate:null,gpsMeters:null },
    { phase:'交付前',sharedCount:4,correctPairs:6,pairCount:6,orderRate:1,gpsMeters:10000 },
    { phase:'交付后',sharedCount:3,correctPairs:3,pairCount:3,orderRate:1,gpsMeters:12000 }
  ]
  const result = summarizeFlowBatch(rows,'交付前')
  assert.equal(result.days,3)
  assert.equal(result.scoreDays,2)
  assert.equal(result.gpsDays,2)
  assert.equal(result.correctPairs,8)
  assert.equal(result.pairCount,9)
  assert.equal(result.averageGpsKm,15)
})
test('batch mileage columns align two-trip days with one-trip days and preserve measured zero', () => {
  const types = new Set(['before', 'work', 'to-station', 'after'])
  const columns = flowBatchMileageColumns([{ tripCount: 1 }, { tripCount: 2 }], types)
  assert.deepEqual(columns, ['首趟流水前', '第1趟 · 首点到末点', '第1趟 · 末点到场站', '第2趟 · 首点到末点', '第2趟 · 末点到场站', '末趟流水后'])
  const mileage = flowBatchMileageByColumn([
    { type: 'work', tripNo: 1, meters: 1000 },
    { type: 'work', tripNo: 1, meters: 2000 },
    { type: 'after', tripNo: null, meters: 0 }
  ])
  assert.deepEqual(columns.map(column => mileage[column]), [undefined, 3000, undefined, undefined, undefined, 0])
})
