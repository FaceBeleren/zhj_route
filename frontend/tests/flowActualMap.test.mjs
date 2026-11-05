import test from 'node:test'
import assert from 'node:assert/strict'
import { buildFlowActualMap, buildFlowParkingAccessLayers, flowPolylineMeters, flowRoadKey, normalizeFlowRoadSegments } from '../src/flowActualMap.js'

const point = (facilityId, longitude, matchType = 0) => ({ facilityId, facilityName: facilityId, longitude, latitude: 36, matchType })

test('流水地图按车和已切分趟次独立连线，不跨趟连接', () => {
  const rows = [
    { index: 0, trip: { carCode: '车A', start: null, points: [point('A', 118), point('B', 118.1)], end: point('中转站', 118.2) } },
    { index: 1, trip: { carCode: '车A', start: point('中转站', 118.2), points: [point('C', 118.3)], end: point('处理厂', 118.4) } },
    { index: 2, trip: { carCode: '车B', start: null, points: [point('D', 119)], end: point('另一场站', 119.1) } }
  ]
  const map = buildFlowActualMap(rows)
  assert.deepEqual(map.layers.map(layer => layer.points.map(item => item.facilityId)), [['A', 'B', '中转站'], ['中转站', 'C', '处理厂'], ['D', '另一场站']])
  assert.deepEqual(map.layers.map(layer => layer.segments.length), [2, 2, 1])
  assert.deepEqual(map.points.map(item => item.overviewOrder), [1, 2, 3, 1, 2, 3, 1, 2])
  assert.notEqual(map.layers[0].color, map.layers[1].color)
  assert.equal(map.missingCoordinateCount, 0)
  assert.equal(map.points.some(item => item.flowThrough), false)
})

test('只展示收运标记时仍保留场站和完整线路，并明确跳过无坐标事件', () => {
  const trip = { carCode: '车A', start: point('中转站', 118), points: [point('途经', 118.1, 1), point('收运', 118.2), { facilityId: '无坐标', longitude: null, latitude: null, matchType: 0 }], end: point('处理厂', 118.3) }
  const map = buildFlowActualMap([{ trip, index: 3 }], true)
  assert.deepEqual(map.layers[0].points.map(item => item.facilityId), ['中转站', '途经', '收运', '处理厂'])
  assert.deepEqual(map.markers.map(item => item.facilityId), ['中转站', '收运', '处理厂'])
  assert.equal(map.layers[0].label, '第4趟 · 车A')
  assert.equal(map.missingCoordinateCount, 1)
})

test('途经点保持白色标记语义，切换只展示收运时仍参与连线', () => {
  const trip = { carCode: '车A', points: [point('收运A', 118), point('途经B', 118.1, 1), point('收运C', 118.2)], end: point('中转站', 118.3) }
  const all = buildFlowActualMap([{ trip, index: 0 }])
  assert.equal(all.points.find(item => item.facilityId === '途经B').flowThrough, true)
  assert.equal(buildFlowActualMap([{ trip, index: 0 }], true).points.some(item => item.facilityId === '途经B'), true)
  assert.equal(buildFlowActualMap([{ trip, index: 0 }], true).markers.some(item => item.facilityId === '途经B'), false)
})

test('同一设施多次收运和途经只画一个标记，但每次顺序与线路事件都保留', () => {
  const events = Array.from({ length: 12 }, (_, index) => point(`其他${index + 1}`, 118 + index * 0.001))
  events[0] = point('A', 118, 0)
  events[5] = point('A', 118, 1)
  events[11] = point('A', 118, 0)
  events[2] = point('仅途经', 118.2, 1)
  const map = buildFlowActualMap([{ trip: { carCode: '车A', points: events }, index: 0 }])
  const marker = map.markers.find(item => item.facilityId === 'A')
  assert.equal(map.layers[0].points.length, 12)
  assert.equal(map.layers[0].segments.length, 11)
  assert.equal(map.markers.filter(item => item.facilityId === 'A').length, 1)
  assert.equal(marker.flowCollectionCount, 2)
  assert.equal(marker.flowThroughCount, 1)
  assert.equal(marker.flowVisitLabel, '1、6、12')
  assert.match(marker.flowVisitTitle, /车A第1趟第6位（途经）/)
  const through = map.markers.find(item => item.facilityId === '仅途经')
  assert.equal(through.flowCollectionCount, 0)
  assert.equal(through.flowThroughCount, 1)
  const collectedOnly = buildFlowActualMap([{ trip: { carCode: '车A', points: events }, index: 0 }], true)
  assert.equal(collectedOnly.markers.find(item => item.facilityId === 'A').flowVisitLabel, '1、6、12')
  assert.match(collectedOnly.markers.find(item => item.facilityId === 'A').flowVisitTitle, /第12位/)
})

test('跨车跨趟重复点共用标记，并写明各趟内序号', () => {
  const map = buildFlowActualMap([
    { trip: { carCode: '车A', points: [point('A', 118)] }, index: 0 },
    { trip: { carCode: '车B', points: [point('A', 118)] }, index: 1 }
  ])
  assert.equal(map.layers.length, 2)
  assert.equal(map.markers.length, 1)
  assert.equal(map.markers[0].flowVisitLabel, '1、2')
  assert.match(map.markers[0].flowVisitTitle, /车A第1趟第1位/)
  assert.match(map.markers[0].flowVisitTitle, /车B第2趟第1位/)
})

test('每天每车仅首趟出场和末趟返场，并随趟次筛选显示', () => {
  const rows = [
    { index: 0, trip: { carCode: '车A', points: [point('收运A', 118)], end: point('中转站A', 118.1) } },
    { index: 1, trip: { carCode: '车B', points: [point('收运B', 119)], end: point('处理厂B', 119.1) } },
    { index: 2, trip: { carCode: '车A', points: [point('收运C', 118.15)], end: point('处理厂A', 118.2) } }
  ]
  const parking = point('停车场', 118.5)
  const all = buildFlowParkingAccessLayers(rows, rows, parking)
  assert.equal(all.length, 4)
  assert.deepEqual(all.map(layer => layer.points.map(item => item.facilityId)), [
    ['停车场', '收运A'], ['停车场', '收运B'], ['处理厂B', '停车场'], ['处理厂A', '停车场']
  ])
  assert.ok(all.every(layer => layer.color === '#8b5cf6'))
  assert.deepEqual(buildFlowParkingAccessLayers(rows, [rows[0]], parking).map(layer => layer.departureFromParking), [true])
  assert.deepEqual(buildFlowParkingAccessLayers(rows, [rows[2]], parking).map(layer => layer.returnToParking), [true])
})

test('无停车场坐标不推算；末趟未到站时仅保留有首点的出场', () => {
  const rows = [{ index: 0, trip: { carCode: '车A', points: [point('首点', 118)], end: point('处理厂', 118.2) } }, { index: 1, trip: { carCode: '车A', end: null } }]
  const access = buildFlowParkingAccessLayers(rows, rows, point('停车场', 118.5))
  assert.equal(access.length, 1)
  assert.deepEqual(access[0].points.map(item => item.facilityId), ['停车场', '首点'])
  assert.deepEqual(buildFlowParkingAccessLayers(rows, rows, { facilityId: '停车场' }), [])
})

test('只接受真实道路折线并按折线坐标累计里程，不用后端距离或跨趟连线', () => {
  const a = point('A', 118)
  const b = point('B', 118.01)
  const c = point('C', 118.02)
  const first = normalizeFlowRoadSegments([a, b], [{ pathSource: 'OD_CACHE', distance: 999999, path: [a, b] }])
  const second = normalizeFlowRoadSegments([b, c], [{ pathSource: 'BAIDU_ONLINE', distance: 1, path: [b, c] }])
  const length = flowPolylineMeters([{ segments: first }, { segments: second }])
  assert.ok(length > 1700 && length < 1900)
  assert.notEqual(length, 1000000)
  assert.notEqual(flowRoadKey([a, b]), flowRoadKey([a, c]))
  assert.throws(() => normalizeFlowRoadSegments([a, b], [{ pathSource: 'DIRECT', path: [a, b] }]), /不完整/)
  assert.equal(normalizeFlowRoadSegments([a, { ...b, longitude: 118 }], [{ pathSource: 'DIRECT', path: [a, { ...b, longitude: 118 }] }])[0].pathSource, 'SAME_POINT')
})
