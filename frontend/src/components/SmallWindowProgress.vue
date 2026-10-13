<template>
  <div class="small-window-progress" role="status" aria-live="polite">
    <strong>{{ phaseLabel }}</strong>
    <p>{{ task.message || '正在提交任务…' }}</p>
    <p v-if="view.phase === 'BASE_PLANNING' && task.progress?.backbone?.currentTripNo">当前第{{ task.progress.backbone.currentTripNo }}趟 · 已完成 {{ task.progress.backbone.completedRoutes || 0 }}趟 · 剩余 {{ task.progress.backbone.remainingPoints || 0 }}点。</p>
    <template v-if="['BASE_ROADS', 'MATRIX'].includes(view.phase)">
      <p>道路点对：{{ view.roads.completed || 0 }} / {{ view.roads.total ?? '正在统计' }}
        <template v-if="view.roadPercent !== null">（{{ view.roadPercent }}%）</template>
        · 缓存命中 {{ view.roads.cached || 0 }} · 百度补算 {{ view.roads.online || 0 }}</p>
      <progress v-if="view.roadPercent !== null" :value="view.roadPercent" max="100" aria-label="道路数据准备进度"></progress>
      <progress v-else aria-label="正在读取道路数据"></progress>
      <p v-if="view.roads.currentPair">当前点对：{{ view.roads.currentPair }}</p>
      <p v-if="view.roads.state === 'PRELOAD'">正在批量读取OD缓存。</p>
      <p v-if="view.roads.state === 'RESOLVING'">等待道路数据返回（缓存读取或百度补算）；完成后更新计数。</p>
    </template>
    <template v-else-if="view.phase === 'SEARCH'">
      <progress v-if="task.running" aria-label="正在搜索插入方案"></progress>
      <p>已检查 {{ task.evaluations || 0 }} 个完整方案 · 可行 {{ task.feasibleCandidates || 0 }} 个 · 超载剪枝 {{ view.capacityPruned }} 个分支</p>
      <p v-if="task.candidateUpperBound">插入组合上界 {{ task.candidateUpperBound }}；剪枝会跳过部分组合，不作为百分比。</p>
    </template>
    <progress v-else-if="task.running && !task.applying" aria-label="正在规划"></progress>
    <p v-if="view.best">当前最佳（未最终确认）：{{ (view.best.distance / 1000).toFixed(2) }} km · {{ view.best.durationMinutes.toFixed(2) }}分钟 · {{ view.best.routeCount }}趟；完整模板及每日约束均通过。</p>
    <p v-else-if="view.phase === 'SEARCH'">尚未找到通过全部约束的方案。</p>
    <small>本阶段已运行 {{ view.elapsed }} · 最近工作进度更新 {{ view.updatedSeconds }}秒前</small>
    <p v-if="view.stale">暂未收到新的工作进度；任务状态仍在查询中，可能正在等待道路返回或校验候选，不代表已经卡死。</p>
    <p v-if="task.running && !task.applying">可取消计算，原方案保留；当前最佳尚未完成全部比较，不作为最终结果。</p>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { planningProgressView } from '../smallWindowProgress.js'
const props = defineProps({ task: { type: Object, required: true } })
const now = ref(Date.now())
let timer
onMounted(() => { timer = setInterval(() => { now.value = Date.now() }, 1000) })
onBeforeUnmount(() => clearInterval(timer))
const view = computed(() => planningProgressView(props.task, now.value))
const phaseLabel = computed(() => props.task.applying ? '正在刷新每日结果并应用' : ({
  QUEUED: '任务已提交，等待执行', BASE_PLANNING: '第一阶段 · 生成基础路线',
  BASE_ROADS: '第一阶段 · 准备基础道路', WAITING_INSERTION: '第一阶段完成 · 等待开始插入',
  MATRIX: '第二阶段 · 准备道路数据', SEARCH: '第二阶段 · 搜索插入方案',
  DONE: '计算完成', FAILED: '计算失败，原方案保留', CANCELLED: '已取消，原方案保留'
}[view.value.phase] || '正在处理'))
</script>

<style scoped>
.small-window-progress { position: sticky; top: 0; z-index: 2; background: #eff6ff; border: 1px solid #bfdbfe; border-radius: 8px; padding: 12px 16px; margin: 12px 0; color: #1e3a5f; }
.small-window-progress p { margin: 6px 0; }
.small-window-progress progress { width: 100%; height: 12px; accent-color: #2563eb; }
.small-window-progress small { color: #52657d; }
</style>
