import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { drawProjectedRoute, mapCaptureCrop, mapScreenshotName } from '../src/mapScreenshot.js'

test('按当前地图在视口中的位置裁切，兼容高清屏帧尺寸', () => {
  assert.deepEqual(mapCaptureCrop({ left: 20, top: 100, right: 620, bottom: 400, width: 600, height: 300 }, 1200, 800, 2400, 1600),
    { x: 40, y: 200, width: 1200, height: 600 })
  assert.throws(() => mapCaptureCrop({ left: 20, top: 100, right: 620, bottom: 900, width: 600, height: 800 }, 1200, 800, 2400, 1600), /未完整显示/)
})

test('截图文件名可排序，公共地图组件在普通 HTTP 下可改用地图元素截图', () => {
  assert.equal(mapScreenshotName(new Date(2026, 9, 19, 8, 3, 4)), '路线地图-20261019-080304.png')
  const source = readFileSync(new URL('../src/components/RouteMapPanel.vue', import.meta.url), 'utf8')
  assert.match(source, /保存截图/)
  assert.match(source, /getDisplayMedia/)
  assert.match(source, /import\('html2canvas'\)/)
  assert.match(source, /captureMapElement\(target\)/)
  assert.match(source, /mapInstance\.pointToPixel/)
  assert.doesNotMatch(source, /context\.drawImage\(marker/)
  assert.match(source, /displaySurface !== 'browser'/)
  assert.match(source, /getTracks\(\)\.forEach\(track => track\.stop\(\)\)/)
})

test('截图路线按地图投影坐标绘制，不依赖百度内部 SVG', () => {
  const calls = []
  const context = Object.fromEntries(['save', 'restore', 'beginPath', 'moveTo', 'lineTo', 'setLineDash', 'stroke'].map(name => [name, (...args) => calls.push([name, ...args])]))
  const points = [{ longitude: 1, latitude: 2 }, { longitude: 3, latitude: 4 }]
  assert.equal(drawProjectedRoute(context, points, point => ({ x: point.longitude * 10, y: point.latitude * 10 }), { color: '#2563eb', width: 5, opacity: 0.9 }), true)
  assert.deepEqual(calls.filter(call => call[0] === 'moveTo' || call[0] === 'lineTo'), [['moveTo', 10, 20], ['lineTo', 30, 40]])
  assert.equal(calls.filter(call => call[0] === 'stroke').length, 1)
  assert.equal(drawProjectedRoute(context, points.slice(0, 1), point => point, { color: '#2563eb', width: 5, opacity: 0.9 }), false)
})
