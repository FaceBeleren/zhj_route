<template>
  <div class="manual-test-overlay" role="dialog" aria-modal="true" aria-label="手动调整测试">
    <section class="manual-test-dialog">
      <header><div><h2>手动调整测试 · {{ title }}</h2><p>只调整趟内点位顺序，不删除点位、不改变起终点和趟次归属；测试不会覆盖原方案。</p></div><button @click="$emit('close')">关闭</button></header>
      <p>比较规则：总道路里程优先，差异不足1米时比较总耗时。{{ scheduleDays.length ? '编辑完整模板，同时检查周期内每天的跳点结果；排名依据完整模板总里程。' : '重新计算两边实际道路；多趟按同一车辆连续计时。' }} 这只能检验当前人工候选，不能证明全局最优。</p>
      <div class="manual-test-controls">
        <label>调整趟次 <select v-model.number="tripIndex" :disabled="busy"><option v-for="(route,index) in draft.routes" :key="index" :value="index">{{ route.vehicleName || '车辆' }} 第{{ route.tripNo || route.routeNo }}趟</option></select></label>
        <button :disabled="busy" @click="reset">恢复系统顺序</button>
        <button :disabled="busy" @click="calculate">{{ busy ? '正在计算…' : '确定并计算对比' }}</button>
      </div>
      <div class="manual-test-editor">
        <div v-for="(point,index) in draft.routes[tripIndex].points" :key="point.manualToken" class="manual-test-point">
          <span>{{ index + 1 }}. {{ point.facilityName || point.facilityId }} <small>{{ windowLabel(point) }}</small></span>
          <div><span v-if="index === 0 || index === draft.routes[tripIndex].points.length - 1 || point.weightSource === 'ANCHOR'">固定锚点</span><template v-else><button :disabled="busy || index === 1 || draft.routes[tripIndex].points[index - 1].weightSource === 'ANCHOR'" @click="move(index,-1)">前移</button><button :disabled="busy || index === draft.routes[tripIndex].points.length - 2 || draft.routes[tripIndex].points[index + 1].weightSource === 'ANCHOR'" @click="move(index,1)">后移</button></template></div>
        </div>
      </div>
      <p v-if="busy">{{ progress }}</p><p v-if="error" class="manual-test-error">{{ error }}</p>
      <template v-if="comparison">
        <h3>{{ comparison.verdict.message }}</h3>
        <p>模板及所校验场景合计：系统{{ comparison.generatedConflicts }}次冲突，手动{{ comparison.manualConflicts }}次冲突。绿色/红色表示相对比较结果，不能代替时间窗校验；红色点位为到达超窗。</p>
        <label>对比场景 <select v-model.number="scenarioIndex"><option v-for="(scenario,index) in comparison.scenarios" :value="index" :key="scenario.day">{{ scenario.label }} · 系统{{ scenario.generated.violations }} / 手动{{ scenario.manual.violations }}处冲突</option></select></label>
        <p>本场景总里程差（手动－系统）：{{ signedDistance(scenario.manual.distance - scenario.generated.distance) }}；总耗时差：{{ signedMinutes(scenario.manual.duration - scenario.generated.duration) }}。</p>
        <div class="manual-test-results">
          <article v-for="side in ['generated','manual']" :key="side" :style="{ borderColor: color(side) }">
            <h3 :style="{ color: color(side) }">{{ side === 'generated' ? '系统生成路线（原序道路复算）' : '手动调整路线' }}</h3>
            <p>全部趟次合计：{{ distance(scenario[side].distance) }} · {{ minutes(scenario[side].duration) }} · 时间窗冲突{{ scenario[side].violations }}处<template v-if="scenario[side].missing"> · 应收未分配{{ scenario[side].missing }}点</template></p>
            <p>当前展示第{{ selectedRoute(side).tripNo || selectedRoute(side).routeNo }}趟：{{ distance(selectedRoute(side).distance) }} · {{ minutes(selectedRoute(side).durationMinutes) }}</p>
            <RouteMapPanel :key="`${calculationId}-${scenarioIndex}-${tripIndex}-${side}`" :original-points="[]" :optimized-points="selectedRoute(side).points" :optimized-segments="selectedRoute(side).roadSegments" :show-original="false" :optimized-label="side === 'generated' ? '系统路线' : '手动路线'" :optimized-color="color(side)" :marker-color="color(side)" />
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
import { buildManualComparison, COMPARISON_COLORS, createManualDraft, moveManualPoint } from '../manualRouteTest.js'

const props = defineProps({ result: Object, options: Object, scheduleDays: { type: Array, default: () => [] }, title: String, initialRouteNo: Number, resolveRoad: Function, buildSchedules: Function, isViolation: Function })
defineEmits(['close'])
const original = createManualDraft(props.result)
const draft = ref(createManualDraft(props.result))
const tripIndex = ref(Math.max(0, draft.value.routes.findIndex(route => Number(route.routeNo) === Number(props.initialRouteNo))))
const busy = ref(false), progress = ref(''), error = ref(''), comparison = ref(null), scenarioIndex = ref(0), calculationId = ref(0)
let requestId = 0
onBeforeUnmount(() => { requestId++ })
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
  if (moveManualPoint(draft.value, tripIndex.value, index, direction)) { comparison.value = null; error.value = '' }
}
function reset() { if (!busy.value) { draft.value = createManualDraft(props.result); comparison.value = null; error.value = '' } }
async function calculate() {
  if (busy.value) return
  const id = ++requestId
  busy.value = true; comparison.value = null; error.value = ''
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
.manual-test-point { display:flex; justify-content:space-between; align-items:center; padding:8px 12px; gap:10px; border-bottom:1px solid #edf2f7; }
.manual-test-point small { color:#64748b; margin-left:10px; } .manual-test-point button { margin-left:6px; }
.manual-test-results { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:16px; margin-top:12px; }
.manual-test-results article { min-width:0; padding:12px; border:2px solid; border-radius:8px; }
.manual-test-arrivals { overflow:auto; max-height:350px; } table { width:100%; border-collapse:collapse; } td,th { padding:8px; text-align:left; border-bottom:1px solid #e2e8f0; }
.conflict,.manual-test-error { color:#dc2626; } .conflict { background:#fff1f2; }
@media(max-width:1000px) { .manual-test-results { grid-template-columns:1fr; } }
</style>
