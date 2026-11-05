import { test } from 'node:test'
import assert from 'node:assert/strict'
import { distanceAtSlider, playbackMetersPerSecond, playbackPathsDistance, playbackPointAtDistance, wheelZoomTarget, WHEEL_ZOOM_INTERVAL_MS } from '../src/routeMapMotion.js'

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

test('dragging the progress slider seeks to the matching route distance', () => {
  assert.equal(distanceAtSlider(0, 100, 64829), 0)
  assert.equal(distanceAtSlider(50, 100, 64829), 32414.5)
  assert.equal(distanceAtSlider(100, 100, 64829), 64829)
  assert.equal(distanceAtSlider(120, 100, 64829), 64829)
})

test('multi-part playback walks each visible path without drawing travel across gaps', () => {
  const paths = [
    [{ longitude: 118, latitude: 36 }, { longitude: 118.001, latitude: 36 }],
    [{ longitude: 119, latitude: 36 }, { longitude: 119.001, latitude: 36 }]
  ]
  const firstLength = playbackPathsDistance([paths[0]])
  assert.ok(playbackPathsDistance(paths) < 300)
  assert.equal(playbackPointAtDistance(paths, 0).longitude, 118)
  assert.equal(playbackPointAtDistance(paths, firstLength).longitude, 118.001)
  assert.ok(playbackPointAtDistance(paths, firstLength + 1).longitude > 119)
})
