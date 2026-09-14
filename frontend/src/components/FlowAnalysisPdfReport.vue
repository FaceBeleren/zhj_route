<template>
  <article ref="reportRoot" class="flow-pdf-report" aria-hidden="true">
    <header class="pdf-cover pdf-page-break-after">
      <div class="pdf-brand">ZHJ ROUTE ANALYSIS</div>
      <div class="pdf-cover-main">
        <p>项目公司岗位作业分析</p>
        <h1>岗位全流程分析报告</h1>
        <div class="pdf-cover-rule"></div>
        <h2>{{ companyName }}</h2>
        <h3>{{ jobName }}</h3>
      </div>
      <dl class="pdf-cover-meta">
        <div><dt>统计期间</dt><dd>{{ startDate }} 至 {{ endDate }}</dd></div>
        <div><dt>执行车辆</dt><dd>{{ vehicleCodes.length ? vehicleCodes.join('、') : '-' }}</dd></div>
        <div><dt>报告生成</dt><dd>{{ generatedAt }}</dd></div>
      </dl>
      <p class="pdf-cover-note">本报告依据所选岗位、日期和车辆的页面分析结果生成，不保存新的路线方案。</p>
    </header>

    <main>
      <section class="pdf-section">
        <div class="pdf-section-title"><span>01</span><div><h2>岗位分析概览</h2><p>岗位实际作业与候选路线核心指标</p></div></div>
        <div class="pdf-summary-grid">
          <div><small>实际作业天数</small><strong>{{ analysis.activeDays || 0 }} 天</strong></div>
          <div><small>实际趟次数</small><strong>{{ analysis.tripCount || 0 }} 趟</strong></div>
          <div><small>完整趟次</small><strong>{{ analysis.completeTripCount || 0 }} 趟</strong></div>
          <div><small>日均趟次</small><strong>{{ analysis.avgTripsPerActiveDay || 0 }} 趟</strong></div>
          <div><small>候选趟数</small><strong>{{ candidateRoutes.length }} 趟</strong></div>
          <div><small>统计点位</small><strong>{{ periodPoints.length }} 个</strong></div>
        </div>
        <div v-if="analysis.vehicleSummary?.length" class="pdf-table-wrap pdf-keep-together">
          <h3>车辆执行汇总</h3>
          <table>
            <thead><tr><th>车辆</th><th>流水记录</th><th>实际趟次</th><th>作业天数</th></tr></thead>
            <tbody><tr v-for="vehicle in analysis.vehicleSummary" :key="vehicle.carCode"><td>{{ vehicle.carCode }}</td><td>{{ vehicle.recordCount || 0 }} 条</td><td>{{ vehicle.tripCount || 0 }} 趟</td><td>{{ vehicle.activeDays || 0 }} 天</td></tr></tbody>
          </table>
        </div>
      </section>

      <section v-if="routeComparison" class="pdf-section pdf-page-break-before">
        <div class="pdf-section-title"><span>02</span><div><h2>岗位单路线对比</h2><p>当前配置顺序与道路优化结果</p></div></div>
        <div class="pdf-route-grid">
          <article class="pdf-route-card original">
            <h3>当前岗位路线</h3>
            <div class="pdf-route-metrics">
              <span><small>点位</small><strong>{{ routeComparison.pointCount || currentPoints.length }}</strong></span>
              <span><small>总距离</small><strong>{{ formatDistance(routeComparison.originalPathDistance) }}</strong></span>
              <span><small>预计时间</small><strong>{{ formatDuration(routeComparison.originalPathDurationMinutes) }}</strong></span>
              <span><small>起点</small><strong>{{ pointName(currentPoints[0]) }}</strong></span>
              <span><small>终点</small><strong>{{ pointName(currentPoints[currentPoints.length - 1]) }}</strong></span>
            </div>
            <div class="pdf-sequence"><span v-for="(point, index) in currentPoints" :key="'current-'+index">{{ index + 1 }}. {{ pointName(point) }}</span></div>
          </article>
          <article class="pdf-route-card optimized">
            <h3>优化后的岗位路线</h3>
            <div class="pdf-route-metrics">
              <span><small>点位</small><strong>{{ routeComparison.points?.length || 0 }}</strong></span>
              <span><small>总距离</small><strong>{{ formatDistance(routeComparison.pathDistance) }}</strong></span>
              <span><small>预计时间</small><strong>{{ formatDuration(routeComparison.pathDurationMinutes) }}</strong></span>
              <span><small>起点</small><strong>{{ pointName(routeComparison.points?.[0]) }}</strong></span>
              <span><small>终点</small><strong>{{ pointName(routeComparison.points?.[routeComparison.points.length - 1]) }}</strong></span>
            </div>
            <div class="pdf-sequence"><span v-for="(point, index) in (routeComparison.points || [])" :key="'optimized-'+index">{{ index + 1 }}. {{ pointName(point) }}</span></div>
          </article>
        </div>
      </section>

      <section class="pdf-section pdf-explicit-page-break">
        <div class="pdf-section-title"><span>03</span><div><h2>统计期点位汇总</h2><p>岗位配置点与实际流水点并集</p></div></div>
        <table class="pdf-point-table">
          <thead><tr><th>序号</th><th>点位</th><th>收运次数</th><th>途经次数</th><th>来源</th></tr></thead>
          <tbody><tr v-for="(point, index) in periodPoints" :key="point.facilityId"><td>{{ index + 1 }}</td><td>{{ pointName(point) }}</td><td>{{ point.collectedCount || 0 }}</td><td>{{ point.throughCount || 0 }}</td><td>{{ point.sourceLabel || '-' }}</td></tr></tbody>
        </table>
      </section>

      <section class="pdf-section pdf-page-break-before">
        <div class="pdf-section-title"><span>04</span><div><h2>每日实际趟次</h2><p>历史点位顺序仅用于实际执行过程展示</p></div></div>
        <article v-for="day in (analysis.dailyTrips || [])" :key="day.date" class="pdf-day-card pdf-keep-together">
          <header><h3>{{ day.date }}</h3><span>{{ day.tripCount || day.trips?.length || 0 }} 趟</span></header>
          <div v-for="(trip, index) in (day.trips || [])" :key="'trip-'+day.date+'-'+index" class="pdf-trip">
            <div class="pdf-trip-head"><strong>第 {{ index + 1 }} 趟 · {{ trip.carCode || '车辆未知' }}</strong><span>{{ trip.complete ? '已到场' : '未记录终点' }} · 收运 {{ trip.collectedPointCount || 0 }} · 途经 {{ trip.throughPointCount || 0 }}</span></div>
            <div class="pdf-sequence compact">
              <span v-if="trip.start" class="endpoint">起点：{{ pointName(trip.start) }}</span>
              <span v-for="(point, pointIndex) in (trip.points || [])" :key="'event-'+pointIndex" :class="Number(point.matchType) === 0 ? 'collected' : 'through'">{{ pointName(point) }}（{{ point.matchLabel || (Number(point.matchType) === 0 ? '收运' : '途经') }}）</span>
              <span v-if="trip.end" class="endpoint">终点：{{ pointName(trip.end) }}</span>
            </div>
          </div>
        </article>
        <p v-if="!analysis.dailyTrips?.length" class="pdf-empty">统计期没有找到可展示的实际趟次。</p>
      </section>

      <section v-if="analysis.groups?.length" class="pdf-section pdf-page-break-before">
        <div class="pdf-section-title"><span>05</span><div><h2>历史岗位分堆</h2><p>根据历史流水归纳的原始分堆结果</p></div></div>
        <article v-for="group in analysis.groups" :key="group.groupNo" class="pdf-group-card pdf-keep-together">
          <header><h3>分堆 {{ group.groupNo }}</h3><span>{{ group.tripCount || 0 }} 趟 · 覆盖 {{ group.activeDays || 0 }} 天 · {{ group.stable ? '相对稳定' : '样本较少' }}</span></header>
          <p>典型终点：{{ pointName(group.typicalEnd) }} · 稳定度：{{ group.stability ?? '-' }}</p>
          <div class="pdf-sequence"><span v-for="(point, index) in (group.points || [])" :key="'group-'+group.groupNo+'-'+index">{{ index + 1 }}. {{ pointName(point) }}（{{ percent(point.support) }}）</span></div>
        </article>
      </section>

      <section class="pdf-section pdf-explicit-page-break">
        <div class="pdf-section-title"><span>06</span><div><h2>候选多趟道路路线</h2><p>分堆原顺序与道路优化后顺序对比</p></div></div>
        <div class="pdf-legend"><span class="normal">普通收运</span><span class="shared">共享</span><span class="multi">多次收运</span><span class="low">低频</span></div>
        <article v-for="route in candidateRoutes" :key="route.groupNo" class="pdf-candidate-card pdf-keep-together">
          <header><div><h3>候选第 {{ route.groupNo }} 趟</h3><p>历史样本 {{ route.tripCount || 0 }} 趟 · 覆盖 {{ route.activeDays || 0 }} 天</p></div><span :class="['pdf-status', route.routingStatus === 'DONE' ? 'done' : 'failed']">{{ route.routingStatus === 'DONE' ? '道路规划完成' : '道路规划未完成' }}</span></header>
          <template v-if="route.routingStatus === 'DONE'">
            <div class="pdf-candidate-metrics"><span>收运点 {{ route.displayPoints?.length || 0 }}</span><span>原序 {{ formatDistance(route.optimization?.originalPathDistance) }} / {{ formatDuration(route.optimization?.originalPathDurationMinutes) }}</span><span>优化 {{ formatDistance(route.optimization?.pathDistance) }} / {{ formatDuration(route.optimization?.pathDurationMinutes) }}</span><span :class="['distance-change', distanceChangeClass(route.optimization?.originalPathDistance, route.optimization?.pathDistance)]">{{ distanceChangeText(route.optimization?.originalPathDistance, route.optimization?.pathDistance) }}</span></div>
            <h4>分堆原顺序</h4>
            <div class="pdf-sequence"><span v-for="(point, index) in (route.inputPoints || [])" :key="'candidate-original-'+route.groupNo+'-'+index" :class="candidatePointClass(point)">{{ index + 1 }}. {{ pointName(point) }}<small v-if="candidatePointLabels(point).length">{{ candidatePointLabels(point).join(' / ') }}</small></span></div>
            <h4>道路优化后</h4>
            <div class="pdf-sequence"><span v-for="(point, index) in (route.optimizedPoints || [])" :key="'candidate-optimized-'+route.groupNo+'-'+index" :class="candidatePointClass(point)">{{ index + 1 }}. {{ pointName(point) }}<small v-if="candidatePointLabels(point).length">{{ candidatePointLabels(point).join(' / ') }}</small></span></div>
          </template>
          <p v-else class="pdf-error">{{ route.error || '该候选趟未取得完整道路规划结果。' }}</p>
        </article>
        <p v-if="!candidateRoutes.length" class="pdf-empty">统计期没有生成候选多趟路线。</p>
      </section>

      <section v-if="excludedPoints.length" :class="['pdf-section', 'pdf-page-break-before', { 'pdf-keep-together': excludedPoints.length <= 12 }]">
        <div class="pdf-section-title"><span>07</span><div><h2>低频或未进入候选点位</h2><p>未收运、仅途经或岗位配置但统计期未出现</p></div></div>
        <table>
          <thead><tr><th>点位</th><th>状态</th><th>收运次数</th><th>途经次数</th></tr></thead>
          <tbody><tr v-for="point in excludedPoints" :key="point.facilityId"><td>{{ pointName(point) }}</td><td>{{ point.statusLabel }}</td><td>{{ point.collectedCount || 0 }}</td><td>{{ point.throughCount || 0 }}</td></tr></tbody>
        </table>
      </section>

      <section class="pdf-section pdf-conclusion pdf-page-break-before">
        <div class="pdf-section-title"><span>08</span><div><h2>分析结论与路线对比汇总</h2><p>仅依据本次所选岗位、日期和车辆生成</p></div></div>
        <div class="pdf-conclusion-grid"><div v-for="metric in conclusion.metrics" :key="metric.label"><small>{{ metric.label }}</small><strong>{{ metric.value }}</strong></div></div>
        <ul><li v-for="line in conclusion.lines" :key="line">{{ line }}</li></ul>
        <div class="pdf-method-note"><strong>说明</strong><p>候选分堆沿用系统当前岗位流水分析口径。本报告为分析预览，不代表已发布的调度路线；实际执行前应结合车辆能力、道路通行条件和现场管理要求复核。</p></div>
      </section>
    </main>
  </article>
</template>

<script setup>
import { ref } from 'vue'

defineProps({
  companyName: { type: String, default: '未命名项目公司' },
  jobName: { type: String, default: '未命名岗位' },
  startDate: { type: String, default: '-' },
  endDate: { type: String, default: '-' },
  generatedAt: { type: String, default: '-' },
  vehicleCodes: { type: Array, default: () => [] },
  currentPoints: { type: Array, default: () => [] },
  routeComparison: { type: Object, default: null },
  analysis: { type: Object, default: () => ({}) },
  periodPoints: { type: Array, default: () => [] },
  candidateRoutes: { type: Array, default: () => [] },
  excludedPoints: { type: Array, default: () => [] },
  conclusion: { type: Object, default: () => ({ metrics: [], lines: [] }) }
})

const reportRoot = ref(null)
defineExpose({ getElement: () => reportRoot.value })

function pointName(point) { return point?.facilityName || point?.facilityId || '-' }
function percent(value) { return `${Math.round(Number(value || 0) * 100)}%` }
function formatDistance(value) { const number = Number(value || 0); return number >= 1000 ? `${(number / 1000).toFixed(2)} km` : `${number.toFixed(0)} m` }
function formatDuration(value) { const number = Number(value || 0); if (!number) return '0 min'; if (number > 0 && number < 1) return `${Math.max(1, Math.round(number * 60))} s`; const minutes = Math.max(0, Math.round(number)); return minutes >= 60 ? `${Math.floor(minutes / 60)} h ${minutes % 60} min` : `${minutes} min` }
function distanceChange(before, after) { const original = Number(before || 0); const delta = original - Number(after || 0); const rate = original > 0 ? Math.abs(delta) / original * 100 : 0; const direction = Math.abs(delta) < .5 ? 'same' : delta > 0 ? 'saving' : 'increase'; return { direction, text: direction === 'same' ? '距离基本持平' : `距离${direction === 'saving' ? '减少' : '增加'} ${formatDistance(Math.abs(delta))}（${rate.toFixed(1)}%）` } }
function distanceChangeText(before, after) { return distanceChange(before, after).text }
function distanceChangeClass(before, after) { return distanceChange(before, after).direction }
function candidatePointClass(point) { return { endpoint: point.role === 'START' || point.role === 'END', multi: point.candidateMultiCollection, shared: point.candidateShared && !point.candidateMultiCollection, low: point.candidateLowFrequency } }
function candidatePointLabels(point) { const labels = []; if (point.candidateMultiCollection) labels.push('多次收运'); if (point.candidateShared) labels.push('共享'); if (point.candidateLowFrequency) labels.push('低频'); if (point.candidateOutsideConfig) labels.push('流水新增'); return labels }
</script>

<style scoped>
.flow-pdf-report { position: fixed; left: -10000px; top: 0; z-index: -1000; width: 794px; padding: 0 58px; box-sizing: border-box; color: #263d50; background: #fff; font-family: "Microsoft YaHei", "PingFang SC", Arial, sans-serif; font-size: 12px; line-height: 1.55; }
.flow-pdf-report.flow-pdf-capturing { position: relative; left: 0; top: 0; z-index: auto; }
.flow-pdf-report * { box-sizing: border-box; }
.pdf-cover { display: flex; min-height: 1010px; flex-direction: column; padding: 40px 34px; border: 1px solid #dbe5ed; background: linear-gradient(145deg, #f3f8fc 0%, #fff 50%, #edf7f3 100%); }
.pdf-brand { color: #2d76ad; font-size: 12px; font-weight: 700; letter-spacing: 2.5px; }
.pdf-cover-main { margin: 205px 0 auto; }
.pdf-cover-main p { margin: 0 0 12px; color: #668095; font-size: 16px; letter-spacing: 3px; }
.pdf-cover-main h1 { margin: 0; color: #173f5f; font-size: 38px; line-height: 1.25; }
.pdf-cover-main h2 { margin: 32px 0 6px; color: #285a78; font-size: 23px; }
.pdf-cover-main h3 { margin: 0; color: #476b83; font-size: 18px; font-weight: 500; }
.pdf-cover-rule { width: 86px; height: 5px; margin-top: 22px; border-radius: 3px; background: #2f80c9; }
.pdf-cover-meta { display: grid; gap: 8px; margin: 0; padding: 18px 20px; border-left: 4px solid #2a9d68; background: rgba(255,255,255,.78); }
.pdf-cover-meta div { display: grid; grid-template-columns: 84px 1fr; gap: 12px; }
.pdf-cover-meta dt { color: #6c8293; }
.pdf-cover-meta dd { margin: 0; color: #294b63; font-weight: 600; }
.pdf-cover-note { margin: 18px 0 0; color: #8797a3; font-size: 10px; }
.pdf-section { margin: 0 0 28px; }
.pdf-section-title { display: flex; align-items: center; gap: 13px; margin: 0 0 16px; padding: 18px 0 9px; border-bottom: 2px solid #dce8f1; break-after: avoid; page-break-after: avoid; }
.pdf-section-title > span { display: grid; width: 36px; height: 36px; place-items: center; border-radius: 50%; color: #fff; background: #2f80c9; font-size: 13px; font-weight: 700; }
.pdf-section-title h2 { margin: 0; color: #1f4f72; font-size: 20px; }
.pdf-section-title p { margin: 2px 0 0; color: #778b9b; font-size: 10px; }
.pdf-summary-grid, .pdf-conclusion-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 9px; }
.pdf-summary-grid > div, .pdf-conclusion-grid > div { display: grid; gap: 4px; padding: 12px; border: 1px solid #d8e5ee; border-radius: 7px; background: #f7fbfe; }
.pdf-summary-grid small, .pdf-conclusion-grid small { color: #718596; }
.pdf-summary-grid strong, .pdf-conclusion-grid strong { color: #1e567d; font-size: 17px; }
.pdf-table-wrap { margin-top: 20px; }
.pdf-table-wrap h3 { margin: 0 0 8px; color: #365c75; font-size: 14px; }
table { width: 100%; border-collapse: collapse; }
th, td { padding: 7px 8px; border: 1px solid #dce5eb; text-align: left; vertical-align: top; }
th { color: #365b74; background: #eef5fa; font-weight: 600; }
tbody tr:nth-child(even) { background: #fafcfd; }
.pdf-route-grid { display: grid; gap: 14px; }
.pdf-route-card { padding: 14px; border: 1px solid #d5e1e9; border-left: 5px solid #d48b35; border-radius: 8px; background: #fffdf9; break-inside: avoid; page-break-inside: avoid; }
.pdf-route-card.optimized { border-left-color: #2f80c9; background: #f8fbfe; }
.pdf-route-card h3 { margin: 0 0 10px; color: #294f68; font-size: 16px; }
.pdf-route-metrics { display: grid; grid-template-columns: repeat(5, 1fr); gap: 6px; }
.pdf-route-metrics span { display: grid; gap: 2px; min-width: 0; padding: 7px; border-radius: 5px; background: rgba(232,240,246,.72); }
.pdf-route-metrics small { color: #7890a1; font-size: 9px; }
.pdf-route-metrics strong { overflow-wrap: anywhere; color: #294b62; font-size: 11px; }
.pdf-sequence { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 10px; }
.pdf-sequence > span { position: relative; padding: 4px 7px; border: 1px solid #b8d9c7; border-radius: 12px; color: #266d4e; background: #eef8f3; font-size: 10px; }
.pdf-sequence > span:not(:last-child)::after { margin-left: 8px; color: #92a3af; content: '→'; }
.pdf-sequence.compact > span { border-radius: 5px; }
.pdf-sequence > span.endpoint { border-color: #bbc9d5; color: #445d70; background: #edf2f6; }
.pdf-sequence > span.through { border-style: dashed; border-color: #aebbc5; color: #6e7d89; background: #f6f7f8; }
.pdf-sequence > span.multi { border-color: #9272bd; color: #644092; background: #f1e9fc; }
.pdf-sequence > span.shared { border-color: #d7aa3e; color: #845a07; background: #fff4d4; }
.pdf-sequence > span.low { border: 2px solid #879aaa; color: #526779; background: #fff; }
.pdf-sequence small { display: block; margin-top: 1px; font-size: 8px; opacity: .8; }
.pdf-day-card, .pdf-group-card, .pdf-candidate-card { margin: 0 0 12px; padding: 12px; border: 1px solid #d5e1e9; border-radius: 7px; background: #fbfdfe; }
.pdf-day-card > header, .pdf-group-card > header, .pdf-candidate-card > header { display: flex; align-items: flex-start; justify-content: space-between; gap: 15px; padding-bottom: 7px; border-bottom: 1px solid #e3eaf0; }
.pdf-day-card h3, .pdf-group-card h3, .pdf-candidate-card h3 { margin: 0; color: #265b80; font-size: 14px; }
.pdf-day-card header span, .pdf-group-card header span { color: #667f92; }
.pdf-trip { padding: 9px 0 3px; }
.pdf-trip + .pdf-trip { border-top: 1px dashed #d6e0e7; }
.pdf-trip-head { display: flex; justify-content: space-between; gap: 12px; color: #62798a; font-size: 10px; }
.pdf-trip-head strong { color: #355a72; }
.pdf-group-card > p { margin: 8px 0 0; color: #688092; }
.pdf-legend { display: flex; gap: 7px; margin: -4px 0 13px; }
.pdf-legend span { padding: 3px 8px; border: 1px solid #b8d9c7; border-radius: 10px; color: #266d4e; background: #eef8f3; font-size: 9px; }
.pdf-legend .shared { border-color: #d7aa3e; color: #845a07; background: #fff4d4; }
.pdf-legend .multi { border-color: #9272bd; color: #644092; background: #f1e9fc; }
.pdf-legend .low { border: 2px solid #879aaa; color: #526779; background: #fff; }
.pdf-candidate-card header p { margin: 2px 0 0; color: #73899a; font-size: 10px; }
.pdf-status { padding: 3px 7px; border-radius: 9px; font-size: 9px; }
.pdf-status.done { color: #267151; background: #e6f5ed; }
.pdf-status.failed { color: #a34747; background: #faeaea; }
.pdf-candidate-metrics { display: flex; flex-wrap: wrap; gap: 6px; margin: 10px 0; }
.pdf-candidate-metrics span { padding: 4px 7px; border-radius: 4px; color: #48667d; background: #edf4f9; font-size: 9px; }
.pdf-candidate-metrics span.distance-change.saving { color: #20734e; background: #e7f6ee; font-weight: 700; }
.pdf-candidate-metrics span.distance-change.increase { color: #a34b42; background: #fff0ed; font-weight: 700; }
.pdf-candidate-metrics span.distance-change.same { color: #607586; background: #eef2f5; font-weight: 700; }
.pdf-candidate-card h4 { margin: 10px 0 0; color: #4a667a; font-size: 10px; }
.pdf-empty, .pdf-error { padding: 12px; border-radius: 6px; color: #718493; background: #f4f6f8; }
.pdf-error { color: #a24747; background: #fff1f1; }
.pdf-conclusion ul { margin: 17px 0 0; padding-left: 19px; color: #415f75; }
.pdf-conclusion li { margin: 6px 0; }
.pdf-method-note { margin-top: 20px; padding: 12px 14px; border-left: 4px solid #88a8bf; color: #657b8c; background: #f4f7f9; }
.pdf-method-note strong { color: #3b6078; }
.pdf-method-note p { margin: 4px 0 0; }
.pdf-page-break-before { break-before: auto; page-break-before: auto; }
.pdf-explicit-page-break { break-before: page; page-break-before: always; }
.pdf-page-break-after { break-after: page; page-break-after: always; }
.pdf-keep-together { break-inside: avoid; page-break-inside: avoid; }
</style>
