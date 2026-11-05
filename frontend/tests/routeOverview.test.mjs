import test from 'node:test'
import assert from 'node:assert/strict'
import { buildRouteOverviewLayers, hasCompleteRoadGeometry, routeOverviewPoints } from '../src/routeOverview.js'

test('多趟总览逐趟保留道路，不把两趟终点与起点连成一段', () => {
  const routes = [
    { routeNo: 1, tripNo: 1, points: [{ facilityId: 'a' }, { facilityId: 'b' }], roadSegments: [{ path: [{ longitude: 1, latitude: 1 }, { longitude: 2, latitude: 2 }] }] },
    { routeNo: 2, tripNo: 2, points: [{ facilityId: 'c' }, { facilityId: 'd' }], roadSegments: [{ path: [{ longitude: 3, latitude: 3 }, { longitude: 4, latitude: 4 }] }] }
  ]
  const returnTrip = { points: [{ facilityId: 'd' }, { facilityId: 'a' }], roadSegments: [{ path: [{ longitude: 4, latitude: 4 }, { longitude: 1, latitude: 1 }] }] }
  const layers = buildRouteOverviewLayers(routes, route => route.points, route => route.roadSegments, returnTrip)
  assert.equal(layers.length, 3)
  assert.deepEqual(layers.map(layer => layer.segments.length), [1, 1, 1])
  assert.notEqual(layers[0].color, layers[1].color)
  assert.equal(layers[2].color, '#8b5cf6')
  assert.deepEqual(routeOverviewPoints(layers).map(point => point.overviewOrder), [1, 2, 1, 2, 1, 2])
})

test('总览道路模式只接受每段都有真实道路几何的路线', () => {
  const points = [{ longitude: 118, latitude: 36 }, { longitude: 118.1, latitude: 36.1 }, { longitude: 118.2, latitude: 36.2 }]
  const segments = points.slice(1).map((point, index) => ({ pathSource: 'OD_CACHE', path: [points[index], point] }))
  assert.equal(hasCompleteRoadGeometry(points, segments), true)
  assert.equal(hasCompleteRoadGeometry(points, segments.slice(0, 1)), false)
  assert.equal(hasCompleteRoadGeometry(points, [{ ...segments[0], pathSource: 'DIRECT' }, segments[1]]), false)
  assert.equal(hasCompleteRoadGeometry(points, [{ ...segments[0], path: [points[0]] }, segments[1]]), false)
  assert.deepEqual(buildRouteOverviewLayers([{ routeNo: 1 }, { routeNo: 2 }], () => [], (_, index) => [index]).map(layer => layer.segments), [[0], [1]])
})
