import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { ref, computed, reactive } from 'vue'
import { maskFixedTripTemplate, buildFixedDailyRouteResult } from '../src/fixedTripTemplate.js'

// Exercise the actual component state/functions without requiring database or browser access.
const app = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
const code = app.slice(app.indexOf('const fixedMaster = ref(null)'), app.indexOf('const assignmentActiveVersionId = ref(null)'))
const repairCode = app.slice(app.indexOf('async function updateFixedMasterTemplate('), app.indexOf('function openTimeWindowRepair('))
const modalCode = app.slice(app.indexOf('function openTimeWindowRepair('), app.indexOf('function showAssignmentVersionResult('))
const compactCode = app.slice(app.indexOf('function assignmentReorderRoutePayload('), app.indexOf('async function requestAssignmentTripReorder('))
const compactAssignmentReorderResult = new Function('planningSegmentsForSchedule', `${compactCode}; return compactAssignmentReorderResult`)(route => route.roadSegments || route.segments || [])
const exportCode = app.slice(app.indexOf('function fixedMasterExportSchemes('), app.indexOf('async function exportFixedMaster('))
function fixture() {
  const requests = []
  const scope = {
    ref, computed, maskFixedTripTemplate, buildFixedDailyRouteResult, compactAssignmentReorderResult,
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
  const state = new Function('scope', `with (scope) { ${code}; ${repairCode}; ${modalCode}; ${exportCode}; return { generateFixedMaster, generateSingleTripPlan, fixedMaster, fixedMasterDay, fixedMasterFresh, fixedMasterDays, fixedMasterMessage, fixedMasterCycleTimeWindowStats, singleTripPlan, singleTripDay, singleTripFresh, updateFixedMasterTemplate, assignmentWorkbookRequest, openTimeWindowRepair, runTimeWindowRepair, applyTimeWindowRepair }; }`)(scope)
  return { scope, state, requests }
}

test('plans all points, rebuilds bypass roads and caches repeated daily sequences', async () => {
  const { state, scope, requests } = fixture()
  scope.assignmentVersions.value[0].selectedIndices = new Set([0, 2])
  await state.generateFixedMaster()
  assert.deepEqual(state.fixedMaster.value.points.map(point => point.facilityId), [3, 2, 1])
  assert.deepEqual(requests.map(request => request.points.map(point => point.facilityId)), [[1, 2, 3], [-1, 3, 2, -2], [-2, 1, -2], [-1, 3, -2]])
  assert.deepEqual(state.fixedMaster.value.days[1].result.routes.map(route => route.tripNo), [1, 2])
  assert.deepEqual(state.fixedMaster.value.days[0].result.routes[0].points.map(point => point.facilityId), [-1, 3, -2])
  assert.equal(state.fixedMaster.value.days[0].result.routes[0].fullTemplatePoints[2].scheduleSkipped, true)
  assert.equal(state.fixedMasterFresh.value, true)
})

test('without a frequency cycle, still generates the full multi-trip template once', async () => {
  const { state, scope, requests } = fixture()
  scope.assignmentScheduleExportReady.value = false
  scope.assignmentScheduleCycleDays.value = 0
  scope.assignmentVersions.value = [{ id: 1, selectedIndices: new Set([0, 1, 2]), applicableDays: [], result: scope.assignmentVersions.value[0].result }]
  await state.generateFixedMaster()
  assert.equal(state.fixedMasterFresh.value, true)
  assert.equal(state.fixedMasterDay.value, 0)
  assert.equal(state.fixedMaster.value.template.routes.length, 2)
  assert.deepEqual(state.fixedMaster.value.days, [])
  assert.equal(requests.length, 3)
  assert.match(state.fixedMasterMessage.value, /尚无按日排班/)
  assert.match(state.fixedMasterCycleTimeWindowStats.value.message, /未检查每天跳点/)
})

test('without a frequency cycle, still generates one uncapped full-point trip', async () => {
  const { state, scope } = fixture()
  scope.assignmentScheduleExportReady.value = false
  scope.assignmentScheduleCycleDays.value = 0
  scope.assignmentVersions.value = [{ id: 1, selectedIndices: new Set([0, 1, 2]), applicableDays: [] }]
  scope.normalizedAssignmentVehicles = () => [{ ratedCapacityKg: 3000, tripCount: 3 }]
  scope.buildSingleTripDetours = async () => ({ breaks: [], thresholdKg: 2700 })
  scope.api = async (url, options) => {
    if (url === '/api/optimize/multi-preview/tasks') return { taskId: 'single-job', status: 'DONE', result: { routeCount: 1, assignedPointCount: 3, unassignedPointCount: 0, routes: [{ routeNo: 1, tripNo: 1, operationDurationMinutes: 0, points: [
      { facilityId: -1, role: 'START', longitude: 118, latitude: 36.8 }, ...scope.splitPlanPoints.value.map(point => ({ ...point, role: 'MIDDLE' })), { facilityId: -2, role: 'END', longitude: 118.1, latitude: 36.8 }
    ] }] } }
    assert.equal(url, '/api/optimize/route-segments')
    const points = JSON.parse(options.body).points
    return { segments: points.slice(1).map(() => ({ distance: 100, durationMinutes: 1, pathSource: 'OD_CACHE', path: [{ longitude: 118, latitude: 36.8 }] })), distance: 100 * (points.length - 1), durationMinutes: points.length - 1 }
  }
  await state.generateSingleTripPlan()
  assert.equal(state.singleTripFresh.value, true)
  assert.equal(state.singleTripDay.value, 0)
  assert.equal(state.singleTripPlan.value.complete.route.points.length, 5)
  assert.deepEqual(state.singleTripPlan.value.days, [])
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
  assert.deepEqual(state.fixedMasterDays.value[1].result.routes[0].points.map(point => point.facilityId), [-1, 3, -2])
  assert.equal(state.fixedMasterDays.value[1].result.routes[0].fullTemplatePoints[1].scheduleSkipped, true)
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
  assert.equal(state.fixedMasterDays.value[1].result.routes[0].fullTemplatePoints[2].scheduleSkipped, true)
  state.openTimeWindowRepair('FIXED')
  assert.equal(scope.timeRepairScope.value, 'FIXED')
  assert.equal(scope.timeRepairOriginal.value.routes.some(route => route.points.some(point => point.scheduleSkipped)), false)
  assert.equal(scope.timeRepairFixedOwner.value, state.fixedMaster.value)
  assert.deepEqual(scope.timeRepairVersion.value.scheduleDays, [{ day: 1, facilityIds: [1, 2, 3] }, { day: 2, facilityIds: [1, 3] }])
})

test('fixed repair submits all scheduled days and shows daily conflicts even when template passes', async () => {
  const { state, scope } = fixture()
  await state.generateFixedMaster()
  scope.timeWindowStatsForResult = result => ({ violationCount: result === state.fixedMaster.value.template ? 0 : 1, reorderable: true })
  assert.equal(state.fixedMasterCycleTimeWindowStats.value.violationCount, 2)
  assert.match(state.fixedMasterCycleTimeWindowStats.value.message, /完整模板：0处冲突/)
  assert.match(state.fixedMasterCycleTimeWindowStats.value.message, /第2天1处冲突/)
  assert.match(app, /fixedMasterCycleTimeWindowStats.violationCount === 0/)
  state.openTimeWindowRepair('FIXED')
  let request
  scope.api = async (url, options) => {
    assert.equal(url, '/api/optimize/repair-time-windows')
    request = JSON.parse(options.body)
    return { jointScenarioValidation: true, improved: false, templateAfterViolationCount: 0, dailyAfter: [{ day: 1, violationCount: 0 }, { day: 2, violationCount: 1 }] }
  }
  await state.runTimeWindowRepair()
  assert.deepEqual(request.scheduleDays, [{ day: 1, facilityIds: [1, 2, 3] }, { day: 2, facilityIds: [1, 3] }])
  assert.equal(request.result.distanceMode, 'ROAD')
  assert.equal(request.result.routes[0].points.length, 4)
  assert.equal(scope.timeRepairPreview.value.dailyAfter[1].violationCount, 1)
  assert.equal(scope.timeRepairError.value, '')
})

test('ordinary assignment repair does not opt into joint daily validation', async () => {
  const { state, scope } = fixture()
  await state.generateFixedMaster()
  scope.timeRepairOriginal.value = state.fixedMaster.value.template
  let request
  scope.api = async (_, options) => { request = JSON.parse(options.body); return { improved: false } }
  await state.runTimeWindowRepair()
  assert.equal('scheduleDays' in request, false)
  assert.equal(scope.timeRepairError.value, '')
})

test('rejects old backend and stale schedule previews without changing template', async () => {
  const { state, scope } = fixture()
  await state.generateFixedMaster()
  const original = JSON.stringify(state.fixedMaster.value.template)
  state.openTimeWindowRepair('FIXED')
  scope.api = async () => ({ improved: true })
  await state.runTimeWindowRepair()
  assert.equal(scope.timeRepairPreview.value, null)
  assert.match(scope.timeRepairError.value, /重启/)
  scope.api = async () => {
    scope.optimizeOptions.normalSpeedKmh = 30
    return { improved: true, jointScenarioValidation: true }
  }
  await state.runTimeWindowRepair()
  assert.equal(scope.timeRepairPreview.value, null)
  assert.match(scope.timeRepairError.value, /配置已变化/)
  assert.equal(JSON.stringify(state.fixedMaster.value.template), original)
})

test('joint repair applies only to fixed template and rebuilds every day before export', async () => {
  const { state, scope } = fixture()
  await state.generateFixedMaster()
  const schemes = JSON.stringify(scope.assignmentVersions.value)
  const previousApi = scope.api
  state.openTimeWindowRepair('FIXED')
  scope.api = async (url, options) => url === '/api/optimize/repair-time-windows'
    ? { improved: true, jointScenarioValidation: true, message: '完整模板和每日均通过', result: JSON.parse(JSON.stringify(state.fixedMaster.value.template)) }
    : previousApi(url, options)
  await state.runTimeWindowRepair()
  await state.applyTimeWindowRepair()
  assert.equal(scope.timeRepairVisible.value, false)
  assert.equal(state.fixedMasterMessage.value, '完整模板和每日均通过')
  assert.equal(state.fixedMasterDays.value.length, 2)
  assert.equal(state.fixedMasterDays.value.every(day => day.result.dailyRoadRebuilt), true)
  assert.equal(JSON.stringify(scope.assignmentVersions.value), schemes)
  assert.equal(state.assignmentWorkbookRequest(new Map(), true).fixedMaster.schemes.length, 2)
})

test('repair dialog describes uncapped search and its natural stopping conditions', () => {
  const dialog = app.slice(app.indexOf('<div v-if="timeRepairVisible"'), app.indexOf('<script setup>'))
  assert.match(dialog, /不设置候选数、轮数或计算时长上限/)
  assert.match(dialog, /全部通过或未找到进一步改善则停止/)
  assert.match(dialog, /跳过重复顺序/)
  assert.doesNotMatch(dialog, /最多评估500|200轮|预算60秒|达到计算上限/)
})
