import { test } from 'node:test'
import assert from 'node:assert/strict'
import { playbackMetersPerSecond, wheelZoomTarget, WHEEL_ZOOM_INTERVAL_MS } from '../src/routeMapMotion.js'

test('wheel zoom changes one level at a time and ignores rapid repeated events', () => {
  assert.equal(wheelZoomTarget(12, -600, Infinity), 13)
  assert.equal(wheelZoomTarget(12, 600, WHEEL_ZOOM_INTERVAL_MS), 11)
  assert.equal(wheelZoomTarget(12, -120, WHEEL_ZOOM_INTERVAL_MS - 1), null)
  assert.equal(wheelZoomTarget(12, 0, Infinity), null)
})

test('route playback offers a slow, route-length-scaled pace', () => {
  assert.equal(playbackMetersPerSecond(63000, 1), 700)
  assert.equal(playbackMetersPerSecond(63000, 0.5), 350)
  assert.equal(playbackMetersPerSecond(63000, 0.25), 175)
  assert.equal(playbackMetersPerSecond(100, 0.1), 0.5)
})
