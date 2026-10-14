import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createMapViewLink, markChangedOrder } from '../src/comparisonMap.js'

function map(lng, zoom) {
  let center = { lng, lat: 36 }, level = zoom
  const listeners = new Map()
  return {
    updates: 0,
    getCenter: () => center,
    getZoom: () => level,
    addEventListener(event, handler) { listeners.set(event, handler) },
    removeEventListener(event) { listeners.delete(event) },
    centerAndZoom(point, nextZoom) {
      center = point; level = nextZoom; this.updates++
      this.fire('moveend'); this.fire('zoomend')
    },
    fire(event) { listeners.get(event)?.() },
    drag(lng) { center = { lng, lat: 36 }; this.fire('moving') },
    zoom(value) { level = value; this.fire('zoomend') }
  }
}
const point = (lng, lat) => ({ lng, lat })
test('comparison maps share initial view and synchronize either direction without feedback', () => {
  const link = createMapViewLink(), left = map(118, 12), right = map(119, 15)
  const unlinkLeft = link.register(left, point), unlinkRight = link.register(right, point)
  assert.deepEqual(right.getCenter(), left.getCenter())
  assert.equal(right.getZoom(), 12)
  left.drag(118.2)
  assert.deepEqual(right.getCenter(), left.getCenter())
  right.zoom(16)
  assert.equal(left.getZoom(), 16)
  const updates = left.updates + right.updates
  left.fire('moveend'); right.fire('zoomend')
  assert.equal(left.updates + right.updates, updates)
  unlinkRight(); left.drag(118.3)
  assert.equal(right.getCenter().lng, 118.2)
  unlinkLeft()
})
test('separate dialogs do not share view state', () => {
  const left = map(118, 12), unrelated = map(119, 13)
  createMapViewLink().register(left, point)
  createMapViewLink().register(unrelated, point)
  left.drag(117)
  assert.equal(unrelated.getCenter().lng, 119)
})
test('mark changed occurrence order without mutating results or merging repeated facilities', () => {
  const before = ['start', 'a', 'b', 'c', 'end'].map(manualToken => ({ manualToken, facilityId: 1 }))
  const after = [before[0], before[2], before[1], before[3], before[4]]
  const marked = markChangedOrder(before, after)
  assert.deepEqual(marked.map(p => p.comparisonOrderChanged), [false, true, true, false, false])
  assert.equal(after[1].comparisonOrderChanged, undefined)
  assert.deepEqual(markChangedOrder(before, before).map(p => p.comparisonOrderChanged), [false, false, false, false, false])
})
