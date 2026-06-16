<template>
  <main class="app-shell">
    <header class="topbar">
      <div>
        <h1>路线分析</h1>
        <p>查看公司路线与岗位，分析规划点位、流水点位、评分结果与优化预览</p>
      </div>
      <div class="date-filter">
        <label>
          开始日期
          <input v-model="filters.startDate" type="date" />
        </label>
        <label>
          结束日期
          <input v-model="filters.endDate" type="date" />
        </label>
        <button @click="reloadCurrent" :disabled="loading">刷新</button>
      </div>
    </header>

    <nav class="view-tabs" aria-label="功能视图">
      <button :class="{ active: currentView === 'workbench' }" @click="currentView = 'workbench'">
        路线详情
      </button>
      <button :class="{ active: currentView === 'multi' }" @click="currentView = 'multi'">
        多路线生成
      </button>
      <button :class="{ active: currentView === 'score' }" @click="currentView = 'score'">
        路线评分
      </button>
    </nav>

    <template v-if="currentView === 'workbench'">
      <section class="summary-strip">
        <div>
          <span>公司</span>
          <strong>{{ companies.length }}</strong>
        </div>
        <div>
          <span>{{ currentTypeName }}</span>
          <strong>{{ routes.length }}</strong>
        </div>
        <div>
          <span>流水</span>
          <strong>{{ records.length }}</strong>
        </div>
        <div>
          <span>规划点位</span>
          <strong>{{ planPoints.length }}</strong>
        </div>
      </section>

      <section class="workspace">
        <aside class="panel company-panel">
          <div class="panel-head">
            <h2>项目公司</h2>
            <input v-model="companyKeyword" placeholder="搜索公司" />
          </div>
          <div class="list">
            <button
              v-for="company in filteredCompanies"
              :key="company.id"
              class="list-item"
              :class="{ active: selectedCompany?.id === company.id }"
              @click="selectCompany(company)"
            >
              <span>{{ company.depName || company.id }}</span>
              <small>{{ company.depCode || '-' }}</small>
            </button>
          </div>
        </aside>

        <section class="panel route-panel">
          <div class="route-panel-head">
            <div class="panel-head">
              <h2>{{ currentTypeName }}列表</h2>
              <span class="muted">{{ selectedCompany?.depName || '请选择公司' }}</span>
            </div>
            <div class="type-segment" aria-label="数据类型">
              <button
                v-for="option in dataTypeOptions"
                :key="option.value"
                :class="{ active: dataType === option.value }"
                :disabled="loading"
                @click="changeDataType(option.value)"
              >
                {{ option.label }}
              </button>
            </div>
          </div>
          <div class="list route-list">
            <button
              v-for="route in routes"
              :key="route.id"
              class="list-item"
              :class="{ active: selectedRoute?.id === route.id }"
              @click="selectRoute(route)"
            >
              <span>{{ route.routeName || route.id }}</span>
              <small>{{ route.dataTypeName || currentTypeName }} ID {{ route.id }}</small>
            </button>
            <div v-if="selectedCompany && routes.length === 0" class="empty">
              暂无{{ currentTypeName }}
            </div>
          </div>
        </section>

        <section class="detail-stack">
          <section class="panel">
            <div class="panel-head">
              <h2>{{ currentTypeName }}概览</h2>
              <button @click="previewOptimize" :disabled="!selectedRoute || loading">优化预览</button>
            </div>
            <div v-if="selectedRoute" class="route-overview">
              <div>
                <span>{{ currentTypeName }}名称</span>
                <strong>{{ selectedRoute.routeName || selectedRoute.id }}</strong>
              </div>
              <div>
                <span>{{ currentTypeName }}ID</span>
                <strong>{{ selectedRoute.id }}</strong>
              </div>
              <div>
                <span>流水数</span>
                <strong>{{ records.length }}</strong>
              </div>
              <div>
                <span>规划点位数</span>
                <strong>{{ planPoints.length }}</strong>
              </div>
            </div>
            <div v-else class="empty">选择一家公司和{{ currentTypeName }}后查看详情</div>
          </section>

          <section class="panel split-panel">
            <div>
              <div class="panel-head compact">
                <h2>规划点位</h2>
              </div>
              <ol class="point-list">
                <li v-for="point in planPoints" :key="point.facilityId">
                  <span>{{ point.facilityName || point.facilityId }}</span>
                  <small>
                    {{ point.facilityTypeName || '-' }} · {{ formatWeight(point.estimatedWeightKg) }}
                    <template v-if="point.containerInfo"> · 桶 {{ point.containerInfo }}</template>
                  </small>
                </li>
              </ol>
              <div v-if="selectedRoute && planPoints.length === 0" class="empty">
                该{{ currentTypeName }}暂无规划点位
              </div>
            </div>
            <div>
              <div class="panel-head compact">
                <h2>流水记录</h2>
              </div>
              <div class="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>流水ID</th>
                      <th>车辆</th>
                      <th>开始时间</th>
                      <th>终端频次</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr
                      v-for="record in records"
                      :key="record.id"
                      :class="{ active: selectedRecord?.id === record.id }"
                      @click="selectRecord(record)"
                    >
                      <td>{{ record.id }}</td>
                      <td>{{ record.carCode || '-' }}</td>
                      <td>{{ formatDate(record.carStartTime) }}</td>
                      <td>{{ record.terminalFrequency ?? '-' }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <div v-if="selectedRoute && records.length === 0" class="empty">
                该{{ currentTypeName }}暂无流水记录
              </div>
            </div>
          </section>

          <section class="panel split-panel lower">
            <div>
              <div class="panel-head compact">
                <h2>流水点位</h2>
                <span class="muted">{{ selectedRecord ? `流水 ${selectedRecord.id}` : '未选择流水' }}</span>
              </div>
              <ol class="point-list">
                <li v-for="point in recordPoints" :key="point.id">
                  <span>{{ point.facilityName || point.facilityId }}</span>
                  <small>{{ formatDate(point.entryPointTime || point.createTime) }}</small>
                </li>
              </ol>
            </div>
            <div>
              <div class="panel-head compact">
                <h2>优化预览</h2>
              </div>
              <div v-if="optimization" class="optimization-box">
                <strong>{{ optimization.status }}</strong>
                <p>{{ optimization.message }}</p>
                <div class="optimization-metrics">
                  <span>点位 {{ optimization.pointCount || 0 }}</span>
                  <span>原距离 {{ formatDistance(optimization.originalDistance) }}</span>
                  <span>优化后 {{ formatDistance(optimization.optimizedDistance) }}</span>
                  <span>节省 {{ formatDistance(optimization.savedDistance) }}</span>
                  <span>预计重量 {{ formatWeight(optimization.estimatedWeightKg) }}</span>
                  <span>预计体积 {{ formatVolume(optimization.estimatedVolumeLiter) }}</span>
                  <span>装载率 {{ formatLoadRate(optimization.loadRate) }}</span>
                </div>
                <div class="sequence">
                  <span v-for="point in optimization.optimizedSequence" :key="point">{{ point }}</span>
                </div>
                <div v-if="routePlot" class="route-plot">
                  <div class="route-plot-head">
                    <strong>坐标模拟</strong>
                    <div>
                      <span class="legend original">原路线</span>
                      <span class="legend optimized">优化后</span>
                      <label class="plot-toggle">
                        <input v-model="showPlotLabels" type="checkbox" />
                        序号
                      </label>
                    </div>
                  </div>
                  <div class="plot-card wide">
                    <div class="plot-title">叠加对比</div>
                    <svg viewBox="0 0 100 100" role="img" aria-label="路线坐标模拟叠加对比">
                      <polyline
                        v-if="routePlot.compare.originalLine"
                        :points="routePlot.compare.originalLine"
                        class="plot-line original"
                      />
                      <polyline
                        v-if="routePlot.compare.optimizedLine"
                        :points="routePlot.compare.optimizedLine"
                        class="plot-line optimized"
                      />
                      <g v-for="point in routePlot.compare.points" :key="point.facilityId">
                        <circle :cx="point.x" :cy="point.y" r="2.2">
                          <title>{{ plotPointTitle(point) }}</title>
                        </circle>
                        <text v-if="point.labelAlways || showPlotLabels" :x="point.x + 2.8" :y="point.y - 2">
                          {{ point.label }}
                        </text>
                      </g>
                    </svg>
                  </div>
                  <div class="plot-grid">
                    <div class="plot-card">
                      <div class="plot-title">原路线顺序</div>
                      <svg viewBox="0 0 100 100" role="img" aria-label="原路线坐标模拟">
                        <polyline :points="routePlot.original.line" class="plot-line original" />
                        <g v-for="point in routePlot.original.points" :key="point.facilityId">
                          <circle :cx="point.x" :cy="point.y" r="2.2">
                            <title>{{ plotPointTitle(point) }}</title>
                          </circle>
                          <text v-if="point.labelAlways || showPlotLabels" :x="point.x + 2.8" :y="point.y - 2">
                            {{ point.label }}
                          </text>
                        </g>
                      </svg>
                    </div>
                    <div class="plot-card">
                      <div class="plot-title">优化后顺序</div>
                      <svg viewBox="0 0 100 100" role="img" aria-label="优化后路线坐标模拟">
                        <polyline :points="routePlot.optimized.line" class="plot-line optimized" />
                        <g v-for="point in routePlot.optimized.points" :key="point.facilityId">
                          <circle :cx="point.x" :cy="point.y" r="2.2">
                            <title>{{ plotPointTitle(point) }}</title>
                          </circle>
                          <text v-if="point.labelAlways || showPlotLabels" :x="point.x + 2.8" :y="point.y - 2">
                            {{ point.label }}
                          </text>
                        </g>
                      </svg>
                    </div>
                  </div>
                </div>
                <ol v-if="optimization.points?.length" class="optimized-points">
                  <li v-for="point in optimization.points" :key="point.facilityId">
                    <span>{{ point.facilityName || point.facilityId }}</span>
                    <small>
                      {{ point.role }} · {{ formatWeight(point.estimatedWeightKg) }}
                      <template v-if="point.containerInfo"> · 桶 {{ point.containerInfo }}</template>
                    </small>
                  </li>
                </ol>
                <div v-if="optimization.segments?.length" class="segment-list">
                  <div v-for="segment in optimization.segments" :key="segment.order" class="segment-row">
                    <span>{{ segment.fromFacilityName || segment.fromFacilityId }}</span>
                    <span>{{ segment.toFacilityName || segment.toFacilityId }}</span>
                    <strong>{{ formatDistance(segment.distance) }}</strong>
                  </div>
                </div>
              </div>
              <div v-else class="empty">点击“优化预览”生成单路线优化结果</div>
            </div>
          </section>
        </section>
      </section>
    </template>

    <template v-else-if="currentView === 'multi'">
      <section class="multi-shell">
        <aside class="panel company-panel">
          <div class="panel-head">
            <h2>项目公司</h2>
            <input v-model="companyKeyword" placeholder="搜索公司" />
          </div>
          <div class="list">
            <button
              v-for="company in filteredCompanies"
              :key="company.id"
              class="list-item"
              :class="{ active: selectedMultiCompany?.id === company.id }"
              @click="selectMultiCompany(company)"
            >
              <span>{{ company.depName || company.id }}</span>
              <small>{{ company.depCode || '-' }}</small>
            </button>
          </div>
        </aside>

        <section class="multi-main">
          <section class="panel">
            <div class="panel-head">
              <h2>多路线生成</h2>
              <button
                @click="generateCompanyRoutes"
                :disabled="!selectedMultiCompany || selectedCompanyPointCount === 0 || loading"
              >
                生成路线
              </button>
            </div>
            <div class="optimizer-controls">
              <label>
                额定载重 kg
                <input v-model.number="optimizeOptions.ratedCapacityKg" type="number" min="1" step="100" />
              </label>
              <label>
                目标装载率
                <input v-model.number="optimizeOptions.targetLoadRate" type="number" min="0.1" max="1" step="0.05" />
              </label>
              <label>
                最大趟数
                <input v-model.number="optimizeOptions.maxRoutes" type="number" min="1" step="1" />
              </label>
              <label>
                起点场站
                <select v-model="selectedStartAnchorKey" @change="applySelectedStartAnchor">
                  <option value="">自动/手填</option>
                  <option v-for="anchor in startAnchorOptions" :key="anchor.key" :value="anchor.key">
                    {{ anchor.label }}
                  </option>
                </select>
              </label>
              <label>
                终点场站
                <select v-model="selectedEndAnchorKey" @change="applySelectedEndAnchor">
                  <option value="">自动/手填</option>
                  <option v-for="anchor in endAnchorOptions" :key="anchor.key" :value="anchor.key">
                    {{ anchor.label }}
                  </option>
                </select>
              </label>
              <label>
                起点经度
                <input v-model.number="optimizeOptions.startLongitude" type="number" step="0.000001" placeholder="自动" />
              </label>
              <label>
                起点纬度
                <input v-model.number="optimizeOptions.startLatitude" type="number" step="0.000001" placeholder="自动" />
              </label>
              <label>
                终点经度
                <input v-model.number="optimizeOptions.endLongitude" type="number" step="0.000001" placeholder="自动" />
              </label>
              <label>
                终点纬度
                <input v-model.number="optimizeOptions.endLatitude" type="number" step="0.000001" placeholder="自动" />
              </label>
            </div>
            <div v-if="selectedMultiCompany" class="route-overview">
              <div>
                <span>公司</span>
                <strong>{{ selectedMultiCompany.depName || selectedMultiCompany.id }}</strong>
              </div>
              <div>
                <span>候选点位</span>
                <strong>{{ selectedCompanyPointCount }} / {{ companyPoints.length }}</strong>
              </div>
              <div>
                <span>预计总量</span>
                <strong>{{ formatWeight(selectedCompanyPointWeight) }}</strong>
              </div>
              <div>
                <span>目标单趟</span>
                <strong>{{ formatWeight(optimizeOptions.ratedCapacityKg * optimizeOptions.targetLoadRate) }}</strong>
              </div>
              <div>
                <span>场站候选</span>
                <strong>{{ routeAnchorCount }}</strong>
              </div>
            </div>
            <div v-else class="empty">选择公司后读取点位池</div>
          </section>

          <section class="panel split-panel multi-layout">
            <div>
              <div class="panel-head compact">
                <h2>公司点位池</h2>
                <span class="muted">{{ selectedCompanyPointCount }} / {{ companyPoints.length }} 个点</span>
              </div>
              <div class="point-toolbar">
                <input v-model="pointKeyword" placeholder="搜索点位、桶信息" />
                <label class="inline-check">
                  <input v-model="showSelectedOnly" type="checkbox" />
                  只看已选
                </label>
              </div>
              <div class="point-actions">
                <button @click="selectVisibleCompanyPoints" :disabled="companyPointVisibleList.length === 0">
                  选中当前结果
                </button>
                <button @click="unselectVisibleCompanyPoints" :disabled="companyPointVisibleList.length === 0">
                  排除当前结果
                </button>
                <button @click="selectAllCompanyPoints" :disabled="companyPoints.length === 0">全选</button>
                <button @click="clearCompanyPointSelection" :disabled="selectedCompanyPointCount === 0">清空</button>
              </div>
              <ol class="point-list selectable">
                <li v-for="point in companyPointVisibleList" :key="point.facilityId">
                  <label class="point-select-row">
                    <input
                      type="checkbox"
                      :checked="isCompanyPointSelected(point.facilityId)"
                      @change="toggleCompanyPoint(point.facilityId)"
                    />
                    <span>
                      <strong>{{ point.facilityName || point.facilityId }}</strong>
                      <small>
                        {{ point.facilityTypeName || '-' }} · {{ formatWeight(point.estimatedWeightKg) }}
                        <template v-if="point.containerInfo"> · 桶 {{ point.containerInfo }}</template>
                      </small>
                    </span>
                  </label>
                </li>
              </ol>
            </div>
            <div>
              <div class="panel-head compact">
                <h2>生成结果</h2>
                <span class="muted">公司级多路线</span>
              </div>
              <div v-if="multiOptimization" class="optimization-box">
                <strong>{{ multiOptimization.status }}</strong>
                <p>{{ multiOptimization.message }}</p>
                <div class="optimization-metrics">
                  <span>路线 {{ multiOptimization.routeCount || 0 }}</span>
                  <span>已分配 {{ multiOptimization.assignedPointCount || 0 }}</span>
                  <span>未分配 {{ multiOptimization.unassignedPointCount || 0 }}</span>
                  <span>已分配量 {{ formatWeight(multiOptimization.assignedWeightKg) }}</span>
                  <span>未分配量 {{ formatWeight(multiOptimization.unassignedWeightKg) }}</span>
                  <span>目标载重 {{ formatWeight(multiOptimization.targetLoadWeightKg) }}</span>
                </div>
                <div class="multi-routes">
                  <article v-for="route in multiOptimization.routes" :key="route.routeNo" class="multi-route-card">
                    <div>
                      <strong>第 {{ route.routeNo }} 趟</strong>
                      <small>
                        {{ route.pointCount }} 点 · {{ formatWeight(route.estimatedWeightKg) }} ·
                        {{ formatDistance(route.distance) }} · 装载率 {{ formatLoadRate(route.loadRate) }}
                      </small>
                    </div>
                    <div class="sequence route-point-sequence">
                      <span v-for="point in route.points" :key="`${route.routeNo}-${point.order}-${point.facilityId}`">
                        {{ point.facilityName || point.facilityId }}
                      </span>
                    </div>
                  </article>
                </div>
                <div v-if="multiOptimization.unassignedPoints?.length" class="unassigned-points">
                  <strong>未分配点位</strong>
                  <span v-for="point in multiOptimization.unassignedPoints" :key="point.facilityId">
                    {{ point.facilityName || point.facilityId }}
                  </span>
                </div>
              </div>
              <div v-else class="empty">设置载重参数后点击“生成路线”</div>
            </div>
          </section>
        </section>
      </section>
    </template>

    <template v-else>
      <section class="score-shell">
        <aside class="panel score-company-panel">
          <div class="panel-head">
            <h2>评分公司</h2>
            <input v-model="companyKeyword" placeholder="搜索公司" />
          </div>
          <div class="score-actions">
            <button @click="selectAllScoreCompanies" :disabled="loading">全选当前</button>
            <button @click="clearScoreCompanies" :disabled="loading">清空</button>
          </div>
          <div class="list">
            <button
              v-for="company in filteredCompanies"
              :key="company.id"
              class="list-item with-check"
              :class="{ active: scoreCompanyIds.includes(String(company.id)) }"
              @click="toggleScoreCompany(company)"
            >
              <span>{{ company.depName || company.id }}</span>
              <small>{{ company.depCode || '-' }}</small>
            </button>
          </div>
        </aside>

        <section class="score-main">
          <section class="summary-strip score-summary">
            <div>
              <span>已选公司</span>
              <strong>{{ scoreCompanyIds.length }}</strong>
            </div>
            <div>
              <span>已评分公司</span>
              <strong>{{ companyScores.length }}</strong>
            </div>
            <div>
              <span>选中公司路线</span>
              <strong>{{ routeScores.length }}</strong>
            </div>
            <div>
              <span>选中路线流水</span>
              <strong>{{ tripScores.length }}</strong>
            </div>
          </section>

          <section class="panel">
            <div class="panel-head">
              <h2>公司综合评分</h2>
              <button @click="loadCompanyScores" :disabled="loading || scoreCompanyIds.length === 0">
                打分
              </button>
            </div>
            <div class="score-hint">
              默认口径：F1 权重 0.6，LCS 权重 0.4；实际点位过滤 facility_match_type=0 且 is_route_facility=1。
            </div>
            <div class="table-wrap score-table">
              <table>
                <thead>
                  <tr>
                    <th>公司</th>
                    <th>路线数</th>
                    <th>有流水路线</th>
                    <th>流水数</th>
                    <th>不同指纹</th>
                    <th>平均F1</th>
                    <th>平均LCS</th>
                    <th>综合得分</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="row in companyScores"
                    :key="row.unitId"
                    :class="{ active: selectedScoreCompany?.unitId === row.unitId }"
                    @click="selectScoreCompany(row)"
                  >
                    <td>
                      <strong>{{ row.unitName || row.unitId }}</strong>
                      <small>{{ row.depCode || row.unitId }}</small>
                    </td>
                    <td>{{ row.routeCount }}</td>
                    <td>{{ row.routeWithTripCount }}</td>
                    <td>{{ row.tripCount }}</td>
                    <td>{{ row.distinctFingerprintCount }}</td>
                    <td>{{ pct(row.avgF1) }}</td>
                    <td>{{ pct(row.avgLcsRatio) }}</td>
                    <td><strong>{{ pct(row.companyOverallScore) }}</strong></td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div v-if="companyScores.length === 0" class="empty">选择公司后点击“打分”</div>
          </section>

          <section class="panel score-drilldown">
            <div>
              <div class="panel-head compact">
                <h2>路线评分</h2>
                <span class="muted">{{ selectedScoreCompany?.unitName || '未选择公司' }}</span>
              </div>
              <div class="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>路线</th>
                      <th>规划点</th>
                      <th>流水</th>
                      <th>指纹</th>
                      <th>平均得分</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr
                      v-for="row in routeScores"
                      :key="row.routeId"
                      :class="{ active: selectedScoreRoute?.routeId === row.routeId }"
                      @click="selectScoreRoute(row)"
                    >
                      <td>
                        <strong>{{ row.routeName || row.routeId }}</strong>
                        <small>ID {{ row.routeId }}</small>
                      </td>
                      <td>{{ row.planPointCount }}</td>
                      <td>{{ row.tripCount }}</td>
                      <td>{{ row.distinctFingerprintCount }}</td>
                      <td><strong>{{ pct(row.avgOverall) }}</strong></td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>
            <div>
              <div class="panel-head compact">
                <h2>流水明细</h2>
                <span class="muted">{{ selectedScoreRoute ? `路线 ${selectedScoreRoute.routeId}` : '未选择路线' }}</span>
              </div>
              <div class="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>流水</th>
                      <th>车辆</th>
                      <th>开始时间</th>
                      <th>规划/实际</th>
                      <th>F1</th>
                      <th>LCS</th>
                      <th>得分</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr
                      v-for="row in tripScores"
                      :key="row.routeRecordId"
                      :class="{ active: selectedScoreTrip?.routeRecordId === row.routeRecordId }"
                      @click="selectScoreTrip(row)"
                    >
                      <td>{{ row.routeRecordId }}</td>
                      <td>{{ row.carCode || '-' }}</td>
                      <td>{{ formatDate(row.carStartTime) }}</td>
                      <td>{{ row.planPointCount }} / {{ row.actualPointCount }}</td>
                      <td>{{ pct(row.f1) }}</td>
                      <td>{{ pct(row.lcsRatio) }}</td>
                      <td><strong>{{ pct(row.overall) }}</strong></td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>
          </section>

          <section class="panel score-explain-panel">
            <div class="panel-head compact">
              <h2>评分解释</h2>
              <span class="muted">
                {{ scoreExplanation ? `流水 ${scoreExplanation.routeRecordId}` : '选择一条流水后查看' }}
              </span>
            </div>
            <div v-if="scoreExplanation" class="score-explain">
              <div class="explain-metrics">
                <span>准确率 {{ pct(scoreExplanation.precision) }}</span>
                <span>召回率 {{ pct(scoreExplanation.recall) }}</span>
                <span>F1 {{ pct(scoreExplanation.f1) }}</span>
                <span>LCS {{ scoreExplanation.lcsLen }} / {{ pct(scoreExplanation.lcsRatio) }}</span>
                <span>综合 {{ pct(scoreExplanation.overall) }}</span>
              </div>
              <div class="explain-legend">
                <span class="explain-dot lcs"></span>最长顺序重合
                <span class="explain-dot hit"></span>点位命中但顺序偏离
                <span class="explain-dot miss"></span>未命中
              </div>
              <div class="score-sequence-grid">
                <div>
                  <h3>规划路线</h3>
                  <ol class="score-sequence-list">
                    <li
                      v-for="point in scoreExplanation.planPoints"
                      :key="`plan-${point.order}-${point.facilityId}`"
                      :class="scorePointClass(point)"
                    >
                      <span>{{ point.order }}</span>
                      <strong>{{ point.facilityName || point.facilityId }}</strong>
                      <small>ID {{ point.facilityId }}</small>
                    </li>
                  </ol>
                </div>
                <div>
                  <h3>实际流水</h3>
                  <ol class="score-sequence-list">
                    <li
                      v-for="point in scoreExplanation.actualPoints"
                      :key="`actual-${point.order}-${point.facilityId}`"
                      :class="scorePointClass(point)"
                    >
                      <span>{{ point.order }}</span>
                      <strong>{{ point.facilityName || point.facilityId }}</strong>
                      <small>ID {{ point.facilityId }}</small>
                    </li>
                  </ol>
                </div>
              </div>
            </div>
            <div v-else class="empty">先选择公司并打分，再点击某条路线下的一条流水。</div>
          </section>
        </section>
      </section>
    </template>

    <div v-if="error" class="toast error">{{ error }}</div>
    <div v-if="loading" class="toast">加载中...</div>
  </main>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'

const currentView = ref('workbench')
const companies = ref([])
const routes = ref([])
const records = ref([])
const planPoints = ref([])
const recordPoints = ref([])
const companyPoints = ref([])
const companyAnchors = ref({
  parkingLots: [],
  transferStations: [],
  disposalSites: [],
  configuredPairs: [],
  defaultStart: null,
  defaultEnd: null
})
const optimization = ref(null)
const multiOptimization = ref(null)

const companyScores = ref([])
const routeScores = ref([])
const tripScores = ref([])
const scoreExplanation = ref(null)
const scoreCompanyIds = ref([])
const selectedScoreCompany = ref(null)
const selectedScoreRoute = ref(null)
const selectedScoreTrip = ref(null)

const selectedCompany = ref(null)
const selectedMultiCompany = ref(null)
const selectedRoute = ref(null)
const selectedRecord = ref(null)
const selectedStartAnchorKey = ref('')
const selectedEndAnchorKey = ref('')
const selectedCompanyPointIds = ref(new Set())

const companyKeyword = ref('')
const pointKeyword = ref('')
const dataType = ref(0)
const loading = ref(false)
const error = ref('')
const showPlotLabels = ref(false)
const showSelectedOnly = ref(false)
const dataTypeOptions = [
  { label: '路线', value: 0 },
  { label: '岗位', value: 1 }
]

const today = new Date()
const start = new Date()
start.setDate(today.getDate() - 29)

const filters = reactive({
  startDate: toDateInput(start),
  endDate: toDateInput(today)
})
const optimizeOptions = reactive({
  ratedCapacityKg: 5000,
  targetLoadRate: 0.9,
  maxRoutes: 10,
  startLongitude: null,
  startLatitude: null,
  endLongitude: null,
  endLatitude: null
})

const filteredCompanies = computed(() => {
  const keyword = companyKeyword.value.trim().toLowerCase()
  if (!keyword) return companies.value
  return companies.value.filter((item) => {
    return [item.depName, item.depCode, item.id].some((value) =>
      String(value || '').toLowerCase().includes(keyword)
    )
  })
})
const currentTypeName = computed(() => (dataType.value === 1 ? '岗位' : '路线'))
const routePlot = computed(() => buildRoutePlot(planPoints.value, optimization.value?.points || []))
const selectedCompanyPoints = computed(() =>
  companyPoints.value.filter((point) => isCompanyPointSelected(point.facilityId))
)
const selectedCompanyPointCount = computed(() => selectedCompanyPointIds.value.size)
const selectedCompanyPointWeight = computed(() =>
  selectedCompanyPoints.value.reduce((sum, point) => sum + Number(point.estimatedWeightKg || 0), 0)
)
const companyPointVisibleList = computed(() =>
  companyPoints.value.filter((point) => {
    if (showSelectedOnly.value && !isCompanyPointSelected(point.facilityId)) {
      return false
    }
    const keyword = pointKeyword.value.trim().toLowerCase()
    if (!keyword) {
      return true
    }
    return [point.facilityName, point.facilityId, point.facilityTypeName, point.containerInfo].some((value) =>
      String(value || '').toLowerCase().includes(keyword)
    )
  })
)
const startAnchorOptions = computed(() => [
  ...anchorOptions(companyAnchors.value.parkingLots, '停车场', 'parking'),
  ...anchorOptions(companyAnchors.value.transferStations, '中转站', 'transfer')
])
const endAnchorOptions = computed(() => [
  ...anchorOptions(companyAnchors.value.disposalSites, '处置场', 'disposal'),
  ...anchorOptions(companyAnchors.value.transferStations, '中转站', 'transfer')
])
const routeAnchorCount = computed(
  () =>
    (companyAnchors.value.parkingLots?.length || 0) +
    (companyAnchors.value.transferStations?.length || 0) +
    (companyAnchors.value.disposalSites?.length || 0)
)

onMounted(loadCompanies)

async function api(path, options) {
  const response = await fetch(path, options)
  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText}`)
  }
  return response.json()
}

async function loadCompanies() {
  await withLoading(async () => {
    companies.value = await api('/api/companies')
  })
}

async function selectCompany(company) {
  selectedCompany.value = company
  clearSelection()
  routes.value = []
  await loadRoutes()
}

async function changeDataType(value) {
  if (dataType.value === value) return
  dataType.value = value
  clearSelection()
  routes.value = []
  if (selectedCompany.value) {
    await loadRoutes()
  }
}

async function loadRoutes() {
  if (!selectedCompany.value) return
  await withLoading(async () => {
    routes.value = await api(
      `/api/companies/${selectedCompany.value.id}/routes?dataType=${dataType.value}`
    )
  })
}

async function selectRoute(route) {
  selectedRoute.value = route
  selectedRecord.value = null
  recordPoints.value = []
  optimization.value = null
  multiOptimization.value = null
  await withLoading(async () => {
    const params = new URLSearchParams({
      unitId: selectedCompany.value.id,
      startDate: filters.startDate,
      endDate: filters.endDate
    })
    const [points, routeRecords] = await Promise.all([
      api(`/api/routes/${route.id}/plan-points`),
      api(`/api/routes/${route.id}/records?${params}`)
    ])
    planPoints.value = points
    records.value = routeRecords
  })
}

async function selectRecord(record) {
  selectedRecord.value = record
  await withLoading(async () => {
    recordPoints.value = await api(`/api/records/${record.id}/points`)
  })
}

async function selectMultiCompany(company) {
  selectedMultiCompany.value = company
  companyPoints.value = []
  pointKeyword.value = ''
  showSelectedOnly.value = false
  selectedCompanyPointIds.value = new Set()
  resetAnchors()
  multiOptimization.value = null
  await withLoading(async () => {
    const [points, anchors] = await Promise.all([
      api(`/api/companies/${company.id}/facilities`),
      api(`/api/companies/${company.id}/route-anchors`)
    ])
    companyPoints.value = points
    selectAllCompanyPoints()
    companyAnchors.value = normalizeAnchors(anchors)
    applyDefaultAnchors()
  })
}

function isCompanyPointSelected(facilityId) {
  return selectedCompanyPointIds.value.has(String(facilityId))
}

function toggleCompanyPoint(facilityId) {
  const next = new Set(selectedCompanyPointIds.value)
  const id = String(facilityId)
  if (next.has(id)) {
    next.delete(id)
  } else {
    next.add(id)
  }
  selectedCompanyPointIds.value = next
  multiOptimization.value = null
}

function selectAllCompanyPoints() {
  selectedCompanyPointIds.value = new Set(companyPoints.value.map((point) => String(point.facilityId)))
  multiOptimization.value = null
}

function clearCompanyPointSelection() {
  selectedCompanyPointIds.value = new Set()
  multiOptimization.value = null
}

function selectVisibleCompanyPoints() {
  const next = new Set(selectedCompanyPointIds.value)
  companyPointVisibleList.value.forEach((point) => next.add(String(point.facilityId)))
  selectedCompanyPointIds.value = next
  multiOptimization.value = null
}

function unselectVisibleCompanyPoints() {
  const next = new Set(selectedCompanyPointIds.value)
  companyPointVisibleList.value.forEach((point) => next.delete(String(point.facilityId)))
  selectedCompanyPointIds.value = next
  multiOptimization.value = null
}

function resetAnchors() {
  companyAnchors.value = {
    parkingLots: [],
    transferStations: [],
    disposalSites: [],
    configuredPairs: [],
    defaultStart: null,
    defaultEnd: null
  }
  selectedStartAnchorKey.value = ''
  selectedEndAnchorKey.value = ''
  optimizeOptions.startLongitude = null
  optimizeOptions.startLatitude = null
  optimizeOptions.endLongitude = null
  optimizeOptions.endLatitude = null
}

function normalizeAnchors(anchors) {
  return {
    parkingLots: anchors?.parkingLots || [],
    transferStations: anchors?.transferStations || [],
    disposalSites: anchors?.disposalSites || [],
    configuredPairs: anchors?.configuredPairs || [],
    defaultStart: anchors?.defaultStart || null,
    defaultEnd: anchors?.defaultEnd || null
  }
}

function anchorOptions(items, typeName, typeKey) {
  return (items || []).map((item) => ({
    ...item,
    key: `${typeKey}:${item.facilityId}`,
    label: `${typeName} · ${item.facilityName || item.facilityId}`
  }))
}

function findAnchor(key) {
  return [...startAnchorOptions.value, ...endAnchorOptions.value].find((item) => item.key === key)
}

function keyForAnchor(anchor, typeKey) {
  return anchor?.facilityId ? `${typeKey}:${anchor.facilityId}` : ''
}

function applyDefaultAnchors() {
  if (companyAnchors.value.defaultStart) {
    const typeKey = Number(companyAnchors.value.defaultStart.facilityType) === 2 ? 'transfer' : 'parking'
    selectedStartAnchorKey.value = keyForAnchor(companyAnchors.value.defaultStart, typeKey)
    applyAnchorToOptions(companyAnchors.value.defaultStart, 'start')
  }
  if (companyAnchors.value.defaultEnd) {
    const typeKey = Number(companyAnchors.value.defaultEnd.facilityType) === 2 ? 'transfer' : 'disposal'
    selectedEndAnchorKey.value = keyForAnchor(companyAnchors.value.defaultEnd, typeKey)
    applyAnchorToOptions(companyAnchors.value.defaultEnd, 'end')
  }
}

function applySelectedStartAnchor() {
  applyAnchorToOptions(findAnchor(selectedStartAnchorKey.value), 'start')
}

function applySelectedEndAnchor() {
  applyAnchorToOptions(findAnchor(selectedEndAnchorKey.value), 'end')
}

function applyAnchorToOptions(anchor, prefix) {
  const longitudeKey = `${prefix}Longitude`
  const latitudeKey = `${prefix}Latitude`
  if (!anchor) {
    optimizeOptions[longitudeKey] = null
    optimizeOptions[latitudeKey] = null
    return
  }
  optimizeOptions[longitudeKey] = Number(anchor.longitude)
  optimizeOptions[latitudeKey] = Number(anchor.latitude)
}

async function previewOptimize() {
  if (!selectedRoute.value) return
  await withLoading(async () => {
    optimization.value = await api('/api/optimize/preview', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        routeId: selectedRoute.value.id,
        unitId: selectedCompany.value.id,
        ...optimizeOptions
      })
    })
  })
}

async function generateCompanyRoutes() {
  if (!selectedMultiCompany.value) return
  await withLoading(async () => {
    multiOptimization.value = await api('/api/optimize/multi-preview', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        unitId: selectedMultiCompany.value.id,
        facilityIds: Array.from(selectedCompanyPointIds.value),
        ...optimizeOptions
      })
    })
  })
}

function toggleScoreCompany(company) {
  const id = String(company.id)
  if (scoreCompanyIds.value.includes(id)) {
    scoreCompanyIds.value = scoreCompanyIds.value.filter((item) => item !== id)
  } else {
    scoreCompanyIds.value = [...scoreCompanyIds.value, id]
  }
}

function selectAllScoreCompanies() {
  scoreCompanyIds.value = filteredCompanies.value.map((company) => String(company.id))
}

function clearScoreCompanies() {
  scoreCompanyIds.value = []
  companyScores.value = []
  routeScores.value = []
  tripScores.value = []
  scoreExplanation.value = null
  selectedScoreCompany.value = null
  selectedScoreRoute.value = null
  selectedScoreTrip.value = null
}

async function loadCompanyScores() {
  await withLoading(async () => {
    const params = new URLSearchParams({
      unitIds: scoreCompanyIds.value.join(','),
      startDate: filters.startDate,
      endDate: filters.endDate
    })
    companyScores.value = await api(`/api/conformance/companies/score?${params}`)
    routeScores.value = []
    tripScores.value = []
    scoreExplanation.value = null
    selectedScoreCompany.value = null
    selectedScoreRoute.value = null
    selectedScoreTrip.value = null
  })
}

async function selectScoreCompany(row) {
  selectedScoreCompany.value = row
  selectedScoreRoute.value = null
  selectedScoreTrip.value = null
  scoreExplanation.value = null
  tripScores.value = []
  await withLoading(async () => {
    const params = new URLSearchParams({
      startDate: filters.startDate,
      endDate: filters.endDate
    })
    routeScores.value = await api(`/api/conformance/companies/${row.unitId}/routes?${params}`)
  })
}

async function selectScoreRoute(row) {
  selectedScoreRoute.value = row
  selectedScoreTrip.value = null
  scoreExplanation.value = null
  await withLoading(async () => {
    const params = new URLSearchParams({
      unitId: row.unitId,
      startDate: filters.startDate,
      endDate: filters.endDate
    })
    tripScores.value = await api(`/api/conformance/routes/${row.routeId}/trips?${params}`)
  })
}

async function selectScoreTrip(row) {
  selectedScoreTrip.value = row
  await withLoading(async () => {
    const params = new URLSearchParams({
      unitId: row.unitId,
      startDate: filters.startDate,
      endDate: filters.endDate
    })
    scoreExplanation.value = await api(
      `/api/conformance/routes/${row.routeId}/trips/${row.routeRecordId}/explain?${params}`
    )
  })
}

function scorePointClass(point) {
  return {
    'is-lcs': point.inLcs,
    'is-hit': point.matched && !point.inLcs,
    'is-miss': !point.matched
  }
}

async function reloadCurrent() {
  if (currentView.value === 'score') {
    if (scoreCompanyIds.value.length > 0) {
      await loadCompanyScores()
    } else {
      await loadCompanies()
    }
    return
  }
  if (currentView.value === 'multi') {
    if (selectedMultiCompany.value) {
      await selectMultiCompany(selectedMultiCompany.value)
    } else {
      await loadCompanies()
    }
    return
  }
  if (selectedRoute.value) {
    await selectRoute(selectedRoute.value)
  } else if (selectedCompany.value) {
    await loadRoutes()
  } else {
    await loadCompanies()
  }
}

function clearSelection() {
  selectedRoute.value = null
  selectedRecord.value = null
  records.value = []
  planPoints.value = []
  recordPoints.value = []
  optimization.value = null
  multiOptimization.value = null
}

async function withLoading(fn) {
  loading.value = true
  error.value = ''
  try {
    await fn()
  } catch (err) {
    error.value = err.message || String(err)
  } finally {
    loading.value = false
  }
}

function toDateInput(value) {
  return value.toISOString().slice(0, 10)
}

function formatDate(value) {
  if (!value) return '-'
  return String(value).replace('T', ' ').slice(0, 19)
}

function pct(value) {
  const n = Number(value || 0)
  return `${(n * 100).toFixed(1)}%`
}

function formatDistance(value) {
  const n = Number(value || 0)
  if (n >= 1000) {
    return `${(n / 1000).toFixed(2)} km`
  }
  return `${n.toFixed(0)} m`
}

function formatWeight(value) {
  const n = Number(value || 0)
  if (!n) return '0 kg'
  if (n >= 1000) {
    return `${(n / 1000).toFixed(2)} t`
  }
  return `${n.toFixed(0)} kg`
}

function formatVolume(value) {
  const n = Number(value || 0)
  if (!n) return '0 L'
  if (n >= 1000) {
    return `${(n / 1000).toFixed(2)} m3`
  }
  return `${n.toFixed(0)} L`
}

function formatLoadRate(value) {
  const n = Number(value || 0)
  if (!n) return '-'
  return `${(n * 100).toFixed(1)}%`
}

function buildRoutePlot(originalPoints, optimizedPoints) {
  if (!originalPoints.length || !optimizedPoints.length) return null
  const originalRoute = normalizePlotPoints(originalPoints)
  const optimizedRoute = normalizePlotPoints(optimizedPoints)
  if (!originalRoute.length || !optimizedRoute.length) return null
  const compare = projectRoutes([originalRoute, optimizedRoute])
  const original = projectRoute(originalRoute)
  const optimized = projectRoute(optimizedRoute)

  return {
    compare: {
      originalLine: toLine(compare[0]),
      optimizedLine: toLine(compare[1]),
      points: labelPlotPoints(compare[1])
    },
    original: {
      line: toLine(original),
      points: labelPlotPoints(original)
    },
    optimized: {
      line: toLine(optimized),
      points: labelPlotPoints(optimized)
    }
  }
}

function normalizePlotPoints(points) {
  return points
    .map((point) => ({
      facilityId: point.facilityId,
      facilityName: point.facilityName,
      longitude: Number(point.longitude),
      latitude: Number(point.latitude)
    }))
    .filter((point) => Number.isFinite(point.longitude) && Number.isFinite(point.latitude))
}

function projectRoute(points) {
  return projectRoutes([points])[0]
}

function projectRoutes(routes) {
  const all = routes.flat()
  const centerLat = all.reduce((sum, point) => sum + point.latitude, 0) / all.length
  const cosLat = Math.cos((centerLat * Math.PI) / 180)
  const projected = all.map((point) => ({
    ...point,
    px: point.longitude * cosLat,
    py: point.latitude
  }))
  const minX = Math.min(...projected.map((point) => point.px))
  const maxX = Math.max(...projected.map((point) => point.px))
  const minY = Math.min(...projected.map((point) => point.py))
  const maxY = Math.max(...projected.map((point) => point.py))
  const width = maxX - minX || 1
  const height = maxY - minY || 1
  let offset = 0
  return routes.map((route) => {
    const mapped = projected.slice(offset, offset + route.length).map((point) => ({
      ...point,
      x: 8 + ((point.px - minX) / width) * 84,
      y: 92 - ((point.py - minY) / height) * 84
    }))
    offset += route.length
    return mapped
  })
}

function labelPlotPoints(points) {
  return points.map((point, index) => ({
    ...point,
    label: index + 1,
    labelAlways: index === 0 || index === points.length - 1
  }))
}

function toLine(points) {
  return points.map((point) => `${point.x.toFixed(2)},${point.y.toFixed(2)}`).join(' ')
}

function plotPointTitle(point) {
  return `${point.label}. ${point.facilityName || point.facilityId}\n经度 ${point.longitude}\n纬度 ${point.latitude}`
}
</script>
