import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { mapCaptureCrop, mapScreenshotName } from '../src/mapScreenshot.js'

test('按当前地图在视口中的位置裁切，兼容高清屏帧尺寸', () => {
  assert.deepEqual(mapCaptureCrop({ left: 20, top: 100, right: 620, bottom: 400, width: 600, height: 300 }, 1200, 800, 2400, 1600),
    { x: 40, y: 200, width: 1200, height: 600 })
  assert.throws(() => mapCaptureCrop({ left: 20, top: 100, right: 620, bottom: 900, width: 600, height: 800 }, 1200, 800, 2400, 1600), /未完整显示/)
})

test('截图文件名可排序，公共地图组件提供截图按钮和当前标签页采集', () => {
  assert.equal(mapScreenshotName(new Date(2026, 9, 19, 8, 3, 4)), '路线地图-20261019-080304.png')
  const source = readFileSync(new URL('../src/components/RouteMapPanel.vue', import.meta.url), 'utf8')
  assert.match(source, /保存截图/)
  assert.match(source, /getDisplayMedia/)
  assert.match(source, /displaySurface !== 'browser'/)
  assert.match(source, /getTracks\(\)\.forEach\(track => track\.stop\(\)\)/)
})
