import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { ref, computed, reactive } from 'vue'
import { maskFixedTripTemplate } from '../src/fixedTripTemplate.js'

// Exercise the actual component state/functions without requiring database or browser access.
const app = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
const code = app.slice(app.indexOf('const fixedMaster = ref(null)'), app.indexOf('const assignmentActiveVersionId = ref(null)'))
const repairCode = app.slice(app.indexOf('async function updateFixedMasterTemplate('), app.indexOf('function openTimeWindowRepair('))
const modalCode = app.slice(app.indexOf('function openTimeWindowRepair('), app.indexOf('function showAssignmentVersionResult('))
const exportCode = app.slice(app.indexOf('function fixedMasterExportSchemes('), app.indexOf('async function exportFixedMaster('))
function fixture() {
  const requests = []
  const scope = {
    ref, computed, maskFixedTripTemplate,
    assignmentBatchRunning: ref(false), assignmentTripReorderRunning: ref(false),
    timeRepairScope: ref('ASSIGNMENT'), timeRepairFixedOwner: ref(null), timeRepairVersion: ref(null), timeRepairOriginal: ref(null), timeRepairPreview: ref(null), timeRepairError: ref(''), timeRepairVisible: ref(false),
    timeWindowStatsForResult: () => ({ violationCount: 1, reorderable: true }),
    toDateInput: () => '2026-10-09', assignmentExportResult: value => value,
    selectedSplitRoute: ref({ id: 1 }), selectedSplitCompany: ref({ id: 1 }),
    splitPlanPoints: ref([1, 2, 3].map(id => ({ facilityId: id, facilityName: `点${id}`, longitude: 118 + id / 1000, latitude: 36.8 }))),
    optimizeOptions: reactive({ multiRouteStrategy: 'DIRECT_GROUP', maxRoutes: 10 }),
    endAnchorMode: ref('fixed'), normalizedAssignmentVehicles: () => [], normalizedEndCandidates: () => [],
    assignmentScheduleExportReady: ref(true), assignmentScheduleCycleDays: ref(2),
    assignmentVersions: ref([
      { id: 1, name: '方案1', applicableDays: [1], selectedIndices: new Set([0, 1, 2]), result: { distanceMode: 'DIRECT', unassignedPointCount: 0, routes: [
        { routeNo: 1, tripNo: 1, points: [{ facilityId: -1, role: 'START' }, { facilityId: 3, role: 'MIDDLE' }, { facilityId: 2, role: 'MIDDLE' }, { facilityId: -2, role: 'END' }] },
        { routeNo: 2, tripNo: 2, points: [{ facilityId: -2, role: 'START' }, { facilityId: 1, role: 'MIDDLE' }, { facilityId: -2, role: 'END' }] }
      ] } },
      { id: 2, applicableDays: [2], selectedIndices: new Set([0, 2]) }
    ]),
    deepClone: value => JSON.parse(JSON.stringify(value)),
    api: async (url, options) => {
      const body = JSON.parse(options.body)
      requests.push(body)
      if (url === '/api/optimize/multi-preview/tasks') return { taskId: 'full-job', status: 'DONE', result: JSON.parse(JSON.stringify(scope.assignmentVersions.value[0].result)) }
      assert.equal(url, '/api/optimize/route-segments')
      return { segments: body.points.slice(1).map(() => ({ distance: 100, durationMinutes: 1, pathSource: 'OD_CACHE', path: [{ longitude: 118, latitude: 36.8 }, { longitude: 118.01, latitude: 36.8 }] })), distance: 100 * (body.points.length - 1), durationMinutes: body.points.length - 1 }
    }
  }
  const state = new Function('scope', `with (scope) { ${code}; ${repairCode}; ${modalCode}; ${exportCode}; return { generateFixedMaster, fixedMaster, fixedMasterFresh, fixedMasterDays, fixedMasterMessage, updateFixedMasterTemplate, assignmentWorkbookRequest, openTimeWindowRepair }; }`)(scope)
  return { scope, state, requests }
}

test('independently plans all points even if day one is a subset, then masks dates without API recalculation', async () => {
  const { state, scope, requests } = fixture()
  scope.assignmentVersions.value[0].selectedIndices = new Set([0, 2])
  await state.generateFixedMaster()
  assert.deepEqual(state.fixedMaster.value.points.map(point => point.facilityId), [3, 2, 1])
  assert.deepEqual(requests.map(request => request.points.map(point => point.facilityId)), [[1, 2, 3], [-1, 3, 2, -2], [-2, 1, -2]])
  assert.deepEqual(state.fixedMaster.value.days[1].result.routes.map(route => route.tripNo), [1, 2])
  assert.equal(state.fixedMaster.value.days[0].result.routes[0].points[2].scheduleSkipped, true)
  assert.equal(state.fixedMasterFresh.value, true)
})

test('disabled day restoration uses the independent template without new road requests', async () => {
  const { state, scope, requests } = fixture()
  scope.assignmentVersions.value[1].scheduleDisabled = true
  await state.generateFixedMaster()
  assert.deepEqual(requests[0].points.map(point => point.facilityId), [1, 2, 3])
  assert.equal(state.fixedMasterDays.value[1].disabled, true)
  assert.equal(state.fixedMasterDays.value[1].result, null)
  scope.assignmentVersions.value[1].scheduleDisabled = false
  assert.equal(state.fixedMasterFresh.value, true)
  assert.equal(state.fixedMasterDays.value[1].result.duePointCount, 2)
})

test('schedule and speed edits invalidate the mask, but original scheme route edits do not', async () => {
  const { state, scope } = fixture()
  await state.generateFixedMaster()
  scope.assignmentVersions.value[1].result = { tripReordered: true }
  assert.equal(state.fixedMasterFresh.value, true)
  scope.optimizeOptions.normalSpeedKmh = 30
  assert.equal(state.fixedMasterFresh.value, false)
  delete scope.optimizeOptions.normalSpeedKmh
  assert.equal(state.fixedMasterFresh.value, true)
  scope.assignmentVersions.value[0].result.tripReordered = true
  assert.equal(state.fixedMasterFresh.value, true)
  delete scope.assignmentVersions.value[0].result.tripReordered
  scope.assignmentVersions.value[1].selectedIndices.add(1)
  assert.equal(state.fixedMasterFresh.value, false)
  assert.deepEqual(state.fixedMasterDays.value, [])
})
test('repair applies only to independent full template and refreshes all daily masks', async () => {
  const { state, scope } = fixture()
  await state.generateFixedMaster()
  const original = JSON.stringify(scope.assignmentVersions.value)
  const result = JSON.parse(JSON.stringify(state.fixedMaster.value.template))
  const points = result.routes[0].points
  ;[points[1], points[2]] = [points[2], points[1]]
  await state.updateFixedMasterTemplate(result, state.fixedMaster.value)
  assert.equal(JSON.stringify(scope.assignmentVersions.value), original)
  assert.deepEqual(state.fixedMaster.value.points.map(point => point.facilityId), [2, 3, 1])
  assert.equal(state.fixedMasterDays.value[1].result.routes[0].points[1].scheduleSkipped, true)
})
test('standalone export has full template detail plus parallel schedules; combined export includes both', async () => {
  const { state } = fixture()
  await state.generateFixedMaster()
  const single = state.assignmentWorkbookRequest(new Map(), true)
  assert.equal(single.schemes.length, 1)
  assert.equal(single.schemes[0].result.routes.length, 2)
  assert.equal(single.scheduleSchemes.length, 2)
  assert.equal(single.fixedMaster.schemes.length, 2)
  const combined = state.assignmentWorkbookRequest(new Map())
  assert.equal(combined.schemes.length, 3)
  assert.equal(combined.scheduleSchemes.length, 2)
})
test('time-window repair always opens full collection template, never the selected daily mask', async () => {
  const { state, scope } = fixture()
  await state.generateFixedMaster()
  assert.equal(state.fixedMasterDays.value[1].result.routes[0].points[2].scheduleSkipped, true)
  state.openTimeWindowRepair('FIXED')
  assert.equal(scope.timeRepairScope.value, 'FIXED')
  assert.equal(scope.timeRepairOriginal.value.routes.some(route => route.points.some(point => point.scheduleSkipped)), false)
  assert.equal(scope.timeRepairFixedOwner.value, state.fixedMaster.value)
})
