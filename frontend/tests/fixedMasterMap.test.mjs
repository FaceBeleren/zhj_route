import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { ref, computed } from 'vue'
const app = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
const code = app.slice(app.indexOf('// Independent display state:'), app.indexOf('function buildDirectRouteSegments('))
test('map toggles reuse actual and retained geometries without changing daily metrics', () => {
  const all = [{ facilityId: -1 }, { facilityId: 1 }, { facilityId: 2, scheduleSkipped: true }, { facilityId: -2 }]
  const route = { points: all.filter(point => !point.scheduleSkipped), fullTemplatePoints: all, roadSegments: [{ pathSource: 'OD_CACHE', distance: 100 }], fullTemplateSegments: [{ pathSource: 'OD_CACHE', distance: 300 }], durationMinutes: 40 }
  const scope = { ref, computed, fixedMasterDay: ref(1), fixedMasterDisplayedRoute: ref(route), buildDirectRouteSegments: points => points.slice(1).map(() => ({ pathSource: 'DIRECT' })) }
  const state = new Function('scope', `with (scope) { ${code}; return { fixedMasterMapMode, fixedMasterMapIncludeSkipped, fixedMasterMapPoints, fixedMasterMapSegments }; }`)(scope)
  assert.equal(state.fixedMasterMapPoints.value.length, 3)
  assert.equal(state.fixedMasterMapSegments.value[0].distance, 100)
  state.fixedMasterMapIncludeSkipped.value = true
  assert.equal(state.fixedMasterMapPoints.value.length, 4)
  assert.equal(state.fixedMasterMapSegments.value[0].distance, 300)
  state.fixedMasterMapMode.value = 'DIRECT'
  assert.equal(state.fixedMasterMapSegments.value[0].pathSource, 'DIRECT')
  assert.equal(route.durationMinutes, 40)
})
