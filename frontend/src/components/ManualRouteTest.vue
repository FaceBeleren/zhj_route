<template>
  <div class="manual-test-overlay" role="dialog" aria-modal="true" aria-label="手动调整测试">
    <section class="manual-test-dialog">
      <header><div><h2>手动调整测试 · {{ title }}</h2><p>只调整趟内点位顺序，不删除点位、不改变起终点和趟次归属；仅计算不会覆盖原方案，点击“保存并替换”才应用。</p></div><button :disabled="busy" @click="$emit('close')">关闭</button></header>
      <p>比较规则：总道路里程优先，差异不足1米时比较总耗时。{{ scheduleDays.length ? '编辑完整模板，同时检查周期内每天的跳点结果；排名依据完整模板总里程。' : '重新计算两边实际道路；多趟按同一车辆连续计时。' }} 这只能检验当前人工候选，不能证明全局最优。</p>
      <div class="manual-test-controls">
        <label>调整模式 <select v-model="adjustmentMode" :disabled="busy" @change="clearDrag"><option value="manual">手动拖动</option><option value="selected">选点自动调整</option></select></label>
        <label>选择编辑趟次 <select v-model.number="tripIndex" :disabled="busy" @change="clearDrag"><option v-for="(route,index) in draft.routes" :key="index" :value="index">{{ route.vehicleName || '车辆' }} 第{{ route.tripNo || route.routeNo }}趟</option></select></label>
        <button :disabled="busy" @click="reset">恢复系统顺序</button>
        <button :disabled="busy" @click="calculate">{{ busy ? '正在计算…' : '确定并计算对比' }}</button>
        <button :disabled="busy" @click="applyManual">保存并替换当前方案（全部趟次）</button>
        <button :disabled="busy" @click="saveManual">另存到方案库</button>
      </div>
      <div v-if="adjustmentMode === 'selected'" class="manual-test-selection">
        <p>已选{{ selectedTokens.size }}点：{{ selectedNames.join('、') || '请勾选疑似不合理的收运点' }}。只做趟内调整，不改变趟次归属；未选点保持相对顺序，不能跨越固定锚点。</p>
        <button :disabled="busy" @click="selectTripPoints">全选当前趟收运点</button>
        <button :disabled="busy" @click="selectedTokens = new Set()">清空选择</button>
        <button :disabled="busy || !selectedTokens.size" @click="optimizeSelection">开始优化所选点</button>
        <button v-if="autoRunning" @click="cancelSelection">取消计算</button>
        <button v-if="autoCandidate" :disabled="busy" @click="adoptSelectedCandidate">采用候选到编辑列表</button>
        <p v-if="autoMessage">{{ autoMessage }}</p>
      </div>
      <div v-if="autoRunning || autoProgress" class="manual-auto-progress" role="status">
        <strong>{{ autoRunning ? '选点调整搜索中（无固定计算上限，可取消）' : '本次选点调整已结束' }}</strong>
        <p>{{ autoProgress?.activeCandidate ? `第${autoProgress.round}轮 · ${autoProgress.phase === 'CANDIDATE_DONE' ? '刚完成' : '正在校验'}第${autoProgress.activeCandidate}个候选` : '正在复算开始前的编辑基准' }} · 已完成{{ autoProgress?.evaluations || 0 }}个候选</p>
        <p>{{ autoProgress?.message }}</p>
        <p v-if="autoProgress?.totalDays">本{{ autoProgress.activeCandidate ? '候选' : '基准' }}排班校验：已完成{{ autoProgress.completedDays || 0 }} / {{ autoProgress.totalDays }}个排班日<template v-if="autoProgress.phase === 'DAY'">，当前第{{ autoProgress.dayIndex }}个（第{{ autoProgress.day }}天）</template>。</p>
        <p v-if="scheduleDays.length">每个候选都要检查整个排班周期；换到下一个候选后，日期校验从第1个排班日重新开始，不是重新计算同一个候选。</p>
        <p v-if="autoProgress?.best">当前最佳：{{ distance(autoProgress.best.distance) }} · {{ minutes(autoProgress.best.duration) }}；完整模板及所校验日期全部约束通过。</p>
        <p>已运行{{ Math.floor(autoElapsed / 1000) }}秒。当前最佳尚未完成全部比较，取消不会采用部分结果。</p>
        <progress v-if="autoRunning" aria-label="正在搜索选点调整"></progress>
      </div>
      <p>{{ adjustmentMode === 'selected' ? '勾选一个或多个疑似不合理的点，可跨多个趟次选择；自动尝试单点搬移、所选点交换、连续所选点整体搬移及反转。搜索只接受可行改善，不允许临时绕远，因此不保证全局最优。' : '拖动点位到目标行的上方或下方调整顺序，也可使用前移／后移按钮。固定锚点不能移动或跨越；拖动后需点击“确定并计算对比”。' }}</p>
      <p>“保存并替换”会将手动路线应用到当前方案的地图、统计与导出；“另存到方案库”独立持久保存。不论是否更优、是否超窗都允许操作，超窗提示仍保留。尚未计算时会先计算，失败则保留编辑内容。</p>
      <div class="manual-test-editor">
        <div v-for="(point,index) in draft.routes[tripIndex].points" :key="point.manualToken" class="manual-test-point" :class="{ 'is-dragging': dragging?.index === index, 'drop-before': dropTarget?.index === index && !dropTarget.after, 'drop-after': dropTarget?.index === index && dropTarget.after }" :draggable="adjustmentMode === 'manual' && !busy && movable(index)" @dragstart="startDrag($event,index)" @dragover="overDrag($event,index)" @drop="dropPoint($event,index)" @dragend="clearDrag">
          <span><input v-if="adjustmentMode === 'selected'" type="checkbox" :checked="selectedTokens.has(point.manualToken)" :disabled="busy || !movable(index)" @change="toggleSelected(point.manualToken)" /><span v-if="movable(index)" class="drag-handle" aria-hidden="true">⠿</span>{{ index + 1 }}. {{ point.facilityName || point.facilityId }} <small>{{ windowLabel(point) }}</small></span>
          <div><span v-if="!movable(index)">固定锚点</span><template v-else><button :disabled="busy || index === 1 || draft.routes[tripIndex].points[index - 1].weightSource === 'ANCHOR'" @click="move(index,-1)">前移</button><button :disabled="busy || index === draft.routes[tripIndex].points.length - 2 || draft.routes[tripIndex].points[index + 1].weightSource === 'ANCHOR'" @click="move(index,1)">后移</button></template></div>
        </div>
      </div>
      <p v-if="busy">{{ progress }}</p><p v-if="error" class="manual-test-error">{{ error }}</p>
      <template v-if="comparison">
        <h3>{{ comparison.verdict.message }}</h3>
        <p>模板及所校验场景合计：{{ selectedComparison ? '基准' : '系统' }}{{ comparison.generatedConflicts }}次冲突，{{ selectedComparison ? '候选' : '手动' }}{{ comparison.manualConflicts }}次冲突。绿色/红色表示相对比较结果，不能代替时间窗校验；红色点位为到达超窗。</p>
        <label>对比场景 <select v-model.number="scenarioIndex"><option v-for="(scenario,index) in comparison.scenarios" :value="index" :key="scenario.day">{{ scenario.label }} · 系统{{ scenario.generated.violations }} / 手动{{ scenario.manual.violations }}处冲突</option></select></label>
        <p>本场景总里程差（手动－系统）：{{ signedDistance(scenario.manual.distance - scenario.generated.distance) }}；总耗时差：{{ signedMinutes(scenario.manual.duration - scenario.generated.duration) }}。</p>
        <div class="manual-test-results">
          <article v-for="side in ['generated','manual']" :key="side" :style="{ borderColor: color(side) }">
            <h3 :style="{ color: color(side) }">{{ selectedComparison ? (side === 'generated' ? '调整前路线（本次编辑基准）' : '选点调整候选') : (side === 'generated' ? '系统生成路线（原序道路复算）' : '手动调整路线') }}</h3>
            <p>全部趟次合计：{{ distance(scenario[side].distance) }} · {{ minutes(scenario[side].duration) }} · 时间窗冲突{{ scenario[side].violations }}处<template v-if="scenario[side].missing"> · 应收未分配{{ scenario[side].missing }}点</template></p>
            <p>当前展示第{{ selectedRoute(side).tripNo || selectedRoute(side).routeNo }}趟：{{ distance(selectedRoute(side).distance) }} · {{ minutes(selectedRoute(side).durationMinutes) }}</p>
            <RouteMapPanel :key="`${calculationId}-${scenarioIndex}-${tripIndex}-${side}`" :original-points="[]" :optimized-points="selectedRoute(side).points" :optimized-segments="selectedRoute(side).roadSegments" :show-original="false" :optimized-label="selectedComparison ? (side === 'generated' ? '调整前基准' : '选点候选') : (side === 'generated' ? '系统路线' : '手动路线')" :optimized-color="color(side)" :marker-color="color(side)" />
            <div class="manual-test-arrivals"><table><thead><tr><th>顺序</th><th>点位</th><th>时间窗</th><th>到达</th><th>校验</th></tr></thead><tbody><tr v-for="point in selectedRoute(side).points" :key="point.manualToken" :class="{ conflict: point.timeWindowViolation }"><td>{{ point.order }}</td><td>{{ point.facilityName || point.facilityId }}</td><td>{{ windowLabel(point) || '—' }}</td><td>{{ point.comparisonArrivalTime }}</td><td>{{ point.timeWindowViolation ? '超窗' : point.role === 'MIDDLE' ? '通过' : '起终点' }}</td></tr></tbody></table></div>
          </article>
        </div>
      </template>
    </section>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import RouteMapPanel from './RouteMapPanel.vue'
import { buildManualComparison, buildManualSavePayload, COMPARISON_COLORS, createManualDraft, moveManualPoint, reorderManualPoint } from '../manualRouteTest.js'
import { adjustablePoint, optimizeSelectedPoints } from '../selectedPointAdjustment.js'

const props = defineProps({ result: Object, options: Object, saveContext: Object, applyResult: Function, scheduleDays: { type: Array, default: () => [] }, title: String, initialRouteNo: Number, resolveRoad: Function, buildSchedules: Function, isViolation: Function })
const emit = defineEmits(['close', 'save'])
const original = createManualDraft(props.result)
const draft = ref(createManualDraft(props.result))
const tripIndex = ref(Math.max(0, draft.value.routes.findIndex(route => Number(route.routeNo) === Number(props.initialRouteNo))))
const busy = ref(false), progress = ref(''), error = ref(''), comparison = ref(null), scenarioIndex = ref(0), calculationId = ref(0)
let requestId = 0
const adjustmentMode = ref('manual'), selectedTokens = ref(new Set()), autoRunning = ref(false), autoCandidate = ref(null), autoProgress = ref(null), autoMessage = ref(''), selectedComparison = ref(false), autoElapsed = ref(0)
let autoController = null, autoTimer = null
const selectedNames = computed(() => draft.value.routes.flatMap(route => route.points).filter(point => selectedTokens.value.has(point.manualToken)).map(point => point.facilityName || point.facilityId))
function toggleSelected(token) { const next = new Set(selectedTokens.value); next.has(token) ? next.delete(token) : next.add(token); selectedTokens.value = next }
function selectTripPoints() { selectedTokens.value = new Set([...selectedTokens.value, ...draft.value.routes[tripIndex.value].points.slice(1,-1).filter(adjustablePoint).map(point => point.manualToken)]) }
function clearAutoResult() { autoCandidate.value = null; autoMessage.value = ''; autoProgress.value = null; selectedComparison.value = false }
function cancelSelection() { autoController?.abort() }
function adoptSelectedCandidate() { if (!busy.value && autoCandidate.value) { draft.value = JSON.parse(JSON.stringify(autoCandidate.value)); autoCandidate.value = null; autoMessage.value = '候选已放入编辑列表；尚未替换当前方案，可继续调整。' } }
async function optimizeSelection() {
  if (busy.value || !selectedTokens.value.size) return
  const id = ++requestId, started = Date.now()
  const before = JSON.parse(JSON.stringify(draft.value))
  busy.value = autoRunning.value = true; error.value = ''; comparison.value = null; clearAutoResult()
  autoController = new AbortController(); autoElapsed.value = 0
  autoTimer = setInterval(() => { autoElapsed.value = Date.now() - started }, 1000)
  try {
    const result = await optimizeSelectedPoints({ draft: before, selectedTokens: [...selectedTokens.value], options: props.options, scheduleDays: props.scheduleDays,
      resolveRoad: props.resolveRoad, buildSchedules: props.buildSchedules, isViolation: props.isViolation, signal: autoController.signal,
      onProgress: value => { if (id === requestId) { autoProgress.value = value; progress.value = value.message } } })
    if (id !== requestId) return
    comparison.value = result.comparison; selectedComparison.value = true; scenarioIndex.value = 0; calculationId.value++
    autoMessage.value = result.message
    comparison.value.verdict.message = result.message
    if (result.improved || result.conflictOnly) autoCandidate.value = result.candidate
  } catch (err) { if (id === requestId) { error.value = err.name === 'AbortError' ? '已取消，开始前的编辑内容保留；未采用部分结果。' : `选点调整失败，原方案未改变：${err.message || String(err)}`; clearAutoResult() } }
  finally { clearInterval(autoTimer); autoElapsed.value = Date.now() - started; if (id === requestId) busy.value = autoRunning.value = false }
}
const dragging = ref(null), dropTarget = ref(null)
const movable = index => {
  const points = draft.value.routes[tripIndex.value].points
  return index > 0 && index < points.length - 1 && !['START', 'END'].includes(points[index].role) && points[index].weightSource !== 'ANCHOR'
}
function clearDrag() { dragging.value = null; dropTarget.value = null }
function startDrag(event, index) {
  if (busy.value || adjustmentMode.value !== 'manual' || !movable(index)) { event.preventDefault(); return }
  dragging.value = { route: tripIndex.value, index }
  event.dataTransfer.effectAllowed = 'move'
  event.dataTransfer.setData('text/plain', draft.value.routes[tripIndex.value].points[index].manualToken)
}
function dragDestination(event, index) {
  if (busy.value || !dragging.value || dragging.value.route !== tripIndex.value || !movable(index)) return null
  const rect = event.currentTarget.getBoundingClientRect()
  const after = event.clientY >= rect.top + rect.height / 2
  const from = dragging.value.index
  const to = index + (after ? 1 : 0) - (from < index + (after ? 1 : 0) ? 1 : 0)
  const points = draft.value.routes[tripIndex.value].points
  if (from === to || points.slice(Math.min(from,to), Math.max(from,to) + 1).some(point => ['START','END'].includes(point.role) || point.weightSource === 'ANCHOR')) return null
  return { index, after, to }
}
function overDrag(event, index) {
  dropTarget.value = dragDestination(event,index)
  if (dropTarget.value) { event.preventDefault(); event.dataTransfer.dropEffect = 'move' }
  // Scroll the editor when dragging near its edges.
  const editor = event.currentTarget.parentElement
  const rect = editor.getBoundingClientRect()
  if (dragging.value && event.clientY < rect.top + 35) editor.scrollTop -= 12
  else if (dragging.value && event.clientY > rect.bottom - 35) editor.scrollTop += 12
}
function dropPoint(event,index) {
  event.preventDefault()
  const target = dragDestination(event,index)
  if (target && reorderManualPoint(draft.value,tripIndex.value,dragging.value.index,target.to)) { comparison.value = null; error.value = ''; clearAutoResult() }
  clearDrag()
}
onBeforeUnmount(() => { requestId++; autoController?.abort(); clearInterval(autoTimer) })
const scenario = computed(() => comparison.value?.scenarios[scenarioIndex.value])
const selectedRoute = side => scenario.value[side].result.routes[tripIndex.value]
const color = side => COMPARISON_COLORS[comparison.value.verdict[side]]
const distance = meters => `${(meters / 1000).toFixed(2)} km`
const minutes = value => `${Math.floor(Math.round(value) / 60)} h ${Math.round(value) % 60} min`
const signedDistance = value => `${value > 0 ? '+' : ''}${value.toFixed(2)} m`
const signedMinutes = value => `${value > 0 ? '+' : ''}${value.toFixed(2)} min`
const windowLabel = point => [point.allowTimeBegin || point.allowTimeEnd ? `允许 ${point.allowTimeBegin || '不限'}—${point.allowTimeEnd || '不限'}` : '', point.barredTimeBegin && point.barredTimeEnd ? `禁止 ${point.barredTimeBegin}—${point.barredTimeEnd}` : ''].filter(Boolean).join('；')
function move(index, direction) {
  if (busy.value) return
  if (moveManualPoint(draft.value, tripIndex.value, index, direction)) { comparison.value = null; error.value = ''; clearAutoResult() }
}
function reset() { if (!busy.value) { clearDrag(); draft.value = createManualDraft(props.result); comparison.value = null; error.value = ''; clearAutoResult(); selectedTokens.value = new Set() } }
async function saveManual() {
  if (busy.value) return
  if (!comparison.value) await calculate()
  if (!comparison.value) return
  emit('save', buildManualSavePayload(comparison.value, props.saveContext || { title: props.title }, props.options, props.scheduleDays))
}
async function applyManual() {
  if (busy.value) return
  if (!comparison.value) await calculate()
  if (!comparison.value) return
  if (comparison.value.manualConflicts && !window.confirm(`当前候选在所校验场景有${comparison.value.manualConflicts}次时间窗冲突，仍要替换当前方案吗？`)) return
  busy.value = true; error.value = ''; progress.value = '正在替换当前方案并刷新每日路线…'
  try {
    // Always apply the full manual result, never the currently selected daily mask.
    const full = comparison.value.scenarios.find(item => item.day === 0)
    await props.applyResult(full.manual.result, comparison.value.manualConflicts)
    emit('close')
  } catch (err) { error.value = `应用失败，编辑内容保留：${err.message || String(err)}` }
  finally { busy.value = false }
}
async function calculate() {
  if (busy.value) return
  const id = ++requestId
  busy.value = true; comparison.value = null; error.value = ''; clearAutoResult()
  try {
    const result = await buildManualComparison({ original, draft: draft.value, scheduleDays: props.scheduleDays, options: props.options, resolveRoad: props.resolveRoad, buildSchedules: props.buildSchedules, isViolation: props.isViolation, onProgress: message => { if (id === requestId) progress.value = message } })
    if (id !== requestId) return
    comparison.value = result; scenarioIndex.value = 0; calculationId.value++
  } catch (err) { if (id === requestId) error.value = `对比失败，原方案未改变：${err.message || String(err)}` }
  finally { if (id === requestId) busy.value = false }
}
</script>

<style scoped>
.manual-test-overlay { position:fixed; inset:0; background:#0008; z-index:1100; display:flex; align-items:center; justify-content:center; }
.manual-test-dialog { background:white; padding:24px; border-radius:12px; width:min(1500px,96vw); max-height:94vh; overflow:auto; }
header { display:flex; justify-content:space-between; align-items:flex-start; gap:16px; }
h2,h3 { margin:8px 0; } p { margin:10px 0; line-height:1.6; }
.manual-test-controls { display:flex; align-items:center; flex-wrap:wrap; gap:12px; margin:12px 0; }
.manual-test-editor { max-height:280px; overflow:auto; border:1px solid #dbe3ee; border-radius:6px; }
.manual-auto-progress { position:sticky; top:0; z-index:2; padding:10px 14px; border:1px solid #bfdbfe; border-radius:8px; background:#eff6ff; }
.manual-auto-progress progress { width:100%; } .manual-test-selection button { margin-right:8px; }
.manual-test-point { display:flex; justify-content:space-between; align-items:center; padding:8px 12px; gap:10px; border-bottom:1px solid #edf2f7; }
.manual-test-point small { color:#64748b; margin-left:10px; } .manual-test-point button { margin-left:6px; }
.manual-test-point[draggable="true"] { cursor:grab; } .drag-handle { color:#64748b; margin-right:10px; }
.manual-test-point.is-dragging { opacity:.45; } .manual-test-point.drop-before { box-shadow:inset 0 3px #2563eb; } .manual-test-point.drop-after { box-shadow:inset 0 -3px #2563eb; }
.manual-test-results { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:16px; margin-top:12px; }
.manual-test-results article { min-width:0; padding:12px; border:2px solid; border-radius:8px; }
.manual-test-arrivals { overflow:auto; max-height:350px; } table { width:100%; border-collapse:collapse; } td,th { padding:8px; text-align:left; border-bottom:1px solid #e2e8f0; }
.conflict,.manual-test-error { color:#dc2626; } .conflict { background:#fff1f2; }
@media(max-width:1000px) { .manual-test-results { grid-template-columns:1fr; } }
</style>
