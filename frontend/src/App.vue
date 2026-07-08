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
      <button :class="{ active: currentView === 'score' }" @click="currentView = 'score'">
        路线评分
      </button>
      <button :class="{ active: currentView === 'workbench' }" @click="currentView = 'workbench'">
        路线详情
      </button>
      <button :class="{ active: currentView === 'split' }" @click="currentView = 'split'">
        路线拆分
      </button>
      <button :class="{ active: currentView === 'cluster' }" @click="currentView = 'cluster'">
        区域划分
      </button>
      <button :class="{ active: currentView === 'multi' }" @click="currentView = 'multi'">
        多路线生成
      </button>
    </nav>

    <section class="map-status-strip">
      <span :class="{ ok: frontendMapAkConfigured, warn: !frontendMapAkConfigured }">
        地图底图 {{ frontendMapAkConfigured ? '已配置' : '未配置' }}
      </span>
      <span :class="{ ok: routeMapStatus?.onlineRouteEnabled, warn: !routeMapStatus?.onlineRouteEnabled }">
        百度补算 {{ routeMapStatus?.onlineRouteEnabled ? '已开启' : '未开启' }}
      </span>
      <span :class="{ ok: routeMapStatus?.baiduAkConfigured, warn: !routeMapStatus?.baiduAkConfigured }">
        后端AK {{ routeMapStatus?.baiduAkConfigured ? '已配置' : '未配置' }}
      </span>
      <span :class="{ ok: routeMapStatus?.baiduSkConfigured, neutral: !routeMapStatus?.baiduSkConfigured }">
        SN {{ routeMapStatus?.baiduSkConfigured ? '已配置' : '未配置' }}
      </span>
      <span :class="{ ok: routeMapStatus?.onlineReady, warn: routeMapStatus && !routeMapStatus.onlineReady, neutral: !routeMapStatus }">
        后端算路 {{ routeMapStatus?.onlineReady ? '就绪' : '未就绪' }}
      </span>
      <span :class="{ ok: routeMapStatus?.cacheAvailable, warn: routeMapStatus && !routeMapStatus.cacheAvailable, neutral: !routeMapStatus }">
        OD缓存 {{ routeMapStatus?.cacheAvailable ? '可用' : '不可用' }}
      </span>
      <span :class="{ ok: routeMapStatus?.cacheHasRows, warn: routeMapStatus && routeMapStatus.cacheAvailable && !routeMapStatus.cacheHasRows, neutral: !routeMapStatus || !routeMapStatus.cacheAvailable }">
        缓存数据 {{ routeMapStatus?.cacheHasRows ? `最近 ${routeMapStatus.latestCacheTime || '-'}` : '无' }}
      </span>
      <span class="neutral">
        超时 {{ routeMapStatus?.connectTimeoutMs || '-' }}/{{ routeMapStatus?.readTimeoutMs || '-' }} ms
      </span>
    </section>

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
              <div class="panel-actions route-preview-actions">
                <div class="route-mode-card compact single-preview-mode-card">
                  <label class="strategy-select">
                    <span>算路策略</span>
                    <select :value="optimizeOptions.useRoadPath ? 'ROAD_GLOBAL' : 'DIRECT_GROUP'" @change="changeSingleRouteStrategy($event.target.value)">
                      <option value="DIRECT_GROUP">直线距离</option>
                      <option value="ROAD_GLOBAL">实际距离</option>
                    </select>
                  </label>
                </div>
                <button @click="previewOptimize" :disabled="!selectedRoute || loading">优化预览</button>
              </div>
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
                  <span>{{ optimization.distanceMode === 'ROAD' ? '原道路距离' : '原直线距离' }} {{ formatDistance(optimization.originalDistance) }}</span>
                  <span>{{ optimization.distanceMode === 'ROAD' ? '优化后道路' : '优化后直线' }} {{ formatDistance(optimization.optimizedDistance) }}</span>
                  <span>{{ optimization.distanceDelta < 0 ? '增加' : '节省' }} {{ formatDistanceAbs(optimization.distanceDelta ?? optimization.savedDistance) }}</span>
                  <template v-if="optimization.distanceMode === 'ROAD'">
                    <span>原直线参考 {{ formatDistance(optimization.directOriginalDistance) }}</span>
                    <span>优化直线参考 {{ formatDistance(optimization.directOptimizedDistance) }}</span>
                  </template>
                  <span>展示 {{ optimization.displayMode === 'ROAD' ? '实际道路折线' : '点位直线' }}</span>
                  <span>{{ optimization.displayMode === 'ROAD' ? '道路耗时' : '预计耗时' }} {{ formatDuration(optimization.pathDurationMinutes) }}</span>
                  <span>预计重量 {{ formatWeight(optimization.estimatedWeightKg) }}</span>
                  <span>预计体积 {{ formatVolume(optimization.estimatedVolumeLiter) }}</span>
                  <span>装载率 {{ formatLoadRate(optimization.loadRate) }}</span>
                  <span>路径 {{ pathSourceSummary(optimization.segments) }}</span>
                </div>
                <div class="sequence">
                  <span v-for="point in optimization.optimizedSequence" :key="point">{{ point }}</span>
                </div>
                <div class="route-display-toolbar">
                  <span>地图展示</span>
                  <button :class="{ active: !optimizeOptions.displayRoadPath }" @click.stop="setSingleRouteDisplay(false)">点位直线</button>
                  <button :class="{ active: optimizeOptions.displayRoadPath }" :disabled="loading" @click.stop="setSingleRouteDisplay(true)">
                    {{ loading && optimizeOptions.displayRoadPath ? '加载道路...' : '道路折线' }}
                  </button>
                  <small>{{ optimization.displayMode === 'ROAD' ? '当前展示道路折线' : '当前展示点位直线' }}</small>
                </div>
                <RouteMapPanel
                  :original-points="planPoints"
                  :optimized-points="optimization.points || []"
                  :original-segments="optimization.originalSegments || []"
                  :optimized-segments="optimization.segments || []"
                />
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
                    <span class="path-source" :class="pathSourceClass(segment.pathSource)">
                      {{ pathSourceLabel(segment.pathSource) }}
                    </span>
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

    <template v-else-if="currentView === 'split'">
      <section class="multi-shell split-route-shell">
        <aside class="panel company-panel">
          <div class="panel-head">
            <h2>项目公司</h2>
            <input v-model="companyKeyword" placeholder="搜索公司" />
          </div>
          <div class="list">
            <button v-for="company in filteredCompanies" :key="company.id" class="list-item" :class="{ active: selectedSplitCompany?.id === company.id }" @click="selectSplitCompany(company)">
              <span>{{ company.depName || company.id }}</span>
              <small>{{ company.depCode || '-' }}</small>
            </button>
          </div>
        </aside>

        <section class="panel route-panel">
          <div class="route-panel-head">
            <div class="panel-head">
              <h2>待拆分路线</h2>
              <span class="muted">{{ selectedSplitCompany?.depName || '请选择公司' }}</span>
            </div>
            <div class="type-segment split-type-segment">
              <button
                v-for="option in dataTypeOptions"
                :key="option.value"
                :class="{ active: dataType === option.value }"
                :disabled="loading"
                @click="changeSplitDataType(option.value)"
              >
                {{ option.label }}
              </button>
            </div>
            <p class="split-source-note">来源与“路线详情”一致，当前展示 {{ currentTypeName }}。</p>
          </div>
          <div class="list route-list">
            <button v-for="route in splitRoutes" :key="route.id" class="list-item" :class="{ active: selectedSplitRoute?.id === route.id }" @click="selectSplitRoute(route)">
              <span>{{ route.routeName || route.id }}</span>
              <small>{{ route.dataTypeName || currentTypeName }} ID {{ route.id }}</small>
            </button>
            <div v-if="selectedSplitCompany && splitRoutes.length === 0" class="empty">当前 {{ currentTypeName }} 暂无可拆分数据，可切换来源类型。</div>
          </div>
        </section>

        <section class="multi-main split-route-main">
          <section class="panel">
            <div class="panel-head">
              <div>
                <h2>单路线拆分</h2>
                <span class="muted">把一条已有路线或岗位的规划点位拆成多趟实际收运路线</span>
              </div>
              <div class="panel-actions">
                <button @click="generateSplitRoutes" :disabled="!selectedSplitRoute || splitPlanPoints.length === 0 || loading">拆分生成</button>
                <button v-if="routeProgress.visible && !routeProgress.done && !routeProgress.failed" class="secondary" @click="stopRouteGeneration">停止</button>
              </div>
            </div>
            <div class="optimizer-controls">
              <label>额定载重 kg<input v-model.number="optimizeOptions.ratedCapacityKg" type="number" min="1" step="100" /></label>
              <label>目标装载率<input v-model.number="optimizeOptions.targetLoadRate" type="number" min="0.1" max="1" step="0.05" /></label>
              <label>最大趟数<input v-model.number="optimizeOptions.maxRoutes" type="number" min="1" step="1" /></label>
              <label>每桶秒<input v-model.number="optimizeOptions.secondsPerContainer" type="number" min="1" step="1" /></label>
              <label>每点分钟<input v-model.number="optimizeOptions.minutesPerPoint" type="number" min="0" step="0.5" /></label>
              <div class="route-mode-card optimizer-mode-card strategy-mode-card">
                <label class="strategy-select"><span>规划策略</span><select v-model="optimizeOptions.multiRouteStrategy"><option value="DIRECT_GROUP">直线快速分组</option><option value="DIRECT_GROUP_ROAD_REFINE">直线分组 + 道路精排</option><option value="ROAD_GLOBAL">全程实际距离</option></select></label>
              </div>
              <label>起点类型<select v-model="startAnchorMode" @change="onAnchorModeChange('start')"><option value="facility">设施点</option><option value="parking">停车场</option><option value="manual">手填</option></select></label>
              <label>起点筛选<select v-model="selectedStartAnchorKey" :disabled="startAnchorMode === 'manual'" @change="applySelectedStartAnchor"><option value="">请选择</option><option v-for="anchor in startAnchorOptions" :key="anchor.key" :value="anchor.key">{{ anchor.label }}</option></select></label>
              <label>终点类型<select v-model="endAnchorMode" @change="onAnchorModeChange('end')"><option value="facility">设施点</option><option value="parking">停车场</option><option value="manual">手填</option><option value="disposalAny">处置场任选</option><option value="terminalAny">处置场及中转站任选</option></select></label>
              <label v-if="!isEndCandidateMode">终点筛选<select v-model="selectedEndAnchorKey" :disabled="endAnchorMode === 'manual'" @change="applySelectedEndAnchor"><option value="">请选择</option><option v-for="anchor in endAnchorOptions" :key="anchor.key" :value="anchor.key">{{ anchor.label }}</option></select></label>
              <div v-else class="wide-control candidate-select-field">
                <span>候选终点（本趟自动择优）</span>
                <div class="candidate-select">
                  <button type="button" class="candidate-select-trigger" @click="endCandidateDropdownOpen = !endCandidateDropdownOpen"><span>{{ endCandidateSummary }}</span><strong>{{ endCandidateDropdownOpen ? '收起' : '展开' }}</strong></button>
                  <div v-if="endCandidateDropdownOpen" class="candidate-select-menu">
                    <button v-for="anchor in endAnchorOptions" :key="anchor.key" type="button" class="candidate-select-option" :class="{ active: isEndCandidateSelected(anchor.key) }" @click="toggleEndCandidate(anchor.key)"><span>{{ anchor.label }}</span><strong v-if="isEndCandidateSelected(anchor.key)">✓</strong></button>
                    <div v-if="endAnchorOptions.length === 0" class="candidate-select-empty">暂无可选终点</div>
                  </div>
                </div>
              </div>
              <label>起点经度<input v-model.number="optimizeOptions.startLongitude" :disabled="startAnchorMode !== 'manual'" type="number" step="0.000001" placeholder="手填" /></label>
              <label>起点纬度<input v-model.number="optimizeOptions.startLatitude" :disabled="startAnchorMode !== 'manual'" type="number" step="0.000001" placeholder="手填" /></label>
              <label>终点经度<input v-model.number="optimizeOptions.endLongitude" :disabled="endAnchorMode !== 'manual'" type="number" step="0.000001" placeholder="手填" /></label>
              <label>终点纬度<input v-model.number="optimizeOptions.endLatitude" :disabled="endAnchorMode !== 'manual'" type="number" step="0.000001" placeholder="手填" /></label>
            </div>
            <div v-if="selectedSplitRoute" class="route-overview">
              <div><span>原路线</span><strong>{{ selectedSplitRoute.routeName || selectedSplitRoute.id }}</strong></div>
              <div><span>规划点位</span><strong>{{ splitPlanPoints.length }}</strong></div>
              <div><span>预计重量</span><strong>{{ formatWeight(splitPlanWeight) }}</strong></div>
              <div><span>预计体积</span><strong>{{ formatVolume(splitPlanVolume) }}</strong></div>
            </div>
          </section>

          <section v-if="selectedSplitRoute" class="panel split-panel multi-layout">
            <div>
              <div class="panel-head compact"><h2>原路线点位</h2></div>
              <ol class="point-list split-point-list">
                <li v-for="point in splitPlanPoints" :key="point.facilityId"><span>{{ point.facilityName || point.facilityId }}</span><small>{{ point.facilityTypeName || '-' }} · {{ formatWeight(point.estimatedWeightKg) }}<template v-if="point.containerInfo"> · 桶 {{ point.containerInfo }}</template></small></li>
              </ol>
            </div>
            <div>
              <div class="panel-head compact"><h2>拆分结果</h2><button @click="exportCompanyRoutes" :disabled="!multiOptimization || loading">导出Excel</button></div>
              <div v-if="routeProgress.visible" class="planning-progress">
                <div class="planning-progress-head">
                  <div>
                    <strong>路线规划进度</strong>
                    <small>{{ routeProgress.message }}</small>
                    <small v-if="routeProgressDetail">{{ routeProgressDetail }}</small>
                  </div>
                  <span>{{ routeProgressPercent }}%</span>
                </div>
                <div class="planning-progress-bar">
                  <i :style="{ width: routeProgressPercent + '%' }"></i>
                </div>
                <ol>
                  <li
                    v-for="(step, index) in routeProgress.steps"
                    :key="step.key"
                    :class="progressStepClass(index)"
                  >
                    <span>{{ progressStepMark(index) }}</span>
                    <div>
                      <strong>{{ step.title }}</strong>
                      <small>{{ step.detail }}</small>
                    </div>
                  </li>
                </ol>
              </div>
              <div v-if="multiOptimization" class="optimization-box">
                <strong>{{ multiOptimization.status }}</strong>
                <p>{{ multiOptimization.message }}</p>
                <div class="optimization-metrics"><span>拆分路线 {{ multiOptimization.routeCount || 0 }}</span><span>已分配 {{ multiOptimization.assignedPointCount || 0 }}</span><span>未分配 {{ multiOptimization.unassignedPointCount || 0 }}</span><span>已分配量 {{ formatWeight(multiOptimization.assignedWeightKg) }}</span></div>
                <div class="multi-routes split-routes-result">
                  <article v-for="route in multiOptimization.routes" :key="route.routeNo" class="multi-route-card" :class="{ active: selectedMultiRouteNo === route.routeNo }" @click="selectedMultiRouteNo = route.routeNo"><div><strong>第 {{ route.routeNo }} 趟 · {{ route.vehicleName || '默认车辆' }} 第{{ route.tripNo || route.routeNo }}趟</strong><small>{{ route.pointCount }} 点 · {{ formatWeight(route.estimatedWeightKg) }} · {{ formatDistance(routeDisplayDistance(route)) }} · 合计 {{ formatDuration(routeDisplayTotalDuration(route)) }}</small></div><div class="sequence route-point-sequence"><span v-for="point in route.points" :key="`${route.routeNo}-${point.order}-${point.facilityId}`">{{ point.facilityName || point.facilityId }}</span></div></article>
                </div>
                <div v-if="selectedMultiRoute" class="route-display-toolbar">
                  <span>地图展示</span>
                  <button :class="{ active: !selectedMultiRouteRoadDisplay }" @click.stop="setSelectedMultiRouteDisplay(false)">点位直线</button>
                  <button :class="{ active: selectedMultiRouteRoadDisplay }" :disabled="routeSegmentLoading" @click.stop="setSelectedMultiRouteDisplay(true)">
                    {{ routeSegmentLoading ? '加载道路...' : '道路折线' }}
                  </button>
                  <small>{{ selectedMultiRouteDisplaySummary }}</small>
                </div>
                <RouteMapPanel v-if="selectedMultiRoute" :original-points="[]" :optimized-points="selectedMultiRoute.points || []" :optimized-segments="selectedMultiRouteDisplaySegments" :show-original="false" optimized-label="拆分路线" />
              </div>
              <div v-else class="empty">点击“拆分生成”后，将把该路线点位拆成多趟路线。</div>
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
              <div class="panel-actions">
                <button
                  @click="generateCompanyRoutes"
                  :disabled="!selectedMultiCompany || currentOptimizationPointCount === 0 || loading"
                >
                  生成路线
                </button>
                <button v-if="routeProgress.visible && !routeProgress.done && !routeProgress.failed" class="secondary" @click="stopRouteGeneration">
                  停止
                </button>
              </div>
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
                每桶秒
                <input v-model.number="optimizeOptions.secondsPerContainer" type="number" min="1" step="1" />
              </label>
              <label>
                每点分钟
                <input v-model.number="optimizeOptions.minutesPerPoint" type="number" min="0" step="0.5" />
              </label>
              <div class="route-mode-card optimizer-mode-card strategy-mode-card">
                <label class="strategy-select">
                  <span>规划策略</span>
                  <select v-model="optimizeOptions.multiRouteStrategy">
                    <option value="DIRECT_GROUP">直线快速分组</option>
                    <option value="DIRECT_GROUP_ROAD_REFINE">直线分组 + 道路精排</option>
                    <option value="ROAD_GLOBAL">全程实际距离</option>
                  </select>
                </label>
              </div>
              <label>
                起点类型
                <select v-model="startAnchorMode" @change="onAnchorModeChange('start')">
                  <option value="facility">设施点</option>
                  <option value="parking">停车场</option>
                  <option value="manual">手填</option>
                </select>
              </label>
              <label>
                起点筛选
                <select v-model="selectedStartAnchorKey" :disabled="startAnchorMode === 'manual'" @change="applySelectedStartAnchor">
                  <option value="">请选择</option>
                  <option v-for="anchor in startAnchorOptions" :key="anchor.key" :value="anchor.key">
                    {{ anchor.label }}
                  </option>
                </select>
              </label>
              <label>
                终点类型
                <select v-model="endAnchorMode" @change="onAnchorModeChange('end')">
                  <option value="facility">设施点</option>
                  <option value="parking">停车场</option>
                  <option value="manual">手填</option>
                  <option value="disposalAny">处置场任选</option>
                  <option value="terminalAny">处置场及中转站任选</option>
                </select>
              </label>
              <label v-if="!isEndCandidateMode">
                终点筛选
                <select v-model="selectedEndAnchorKey" :disabled="endAnchorMode === 'manual'" @change="applySelectedEndAnchor">
                  <option value="">请选择</option>
                  <option v-for="anchor in endAnchorOptions" :key="anchor.key" :value="anchor.key">
                    {{ anchor.label }}
                  </option>
                </select>
              </label>
              <div v-else class="wide-control candidate-select-field">
                <span>候选终点（本趟自动择优）</span>
                <div class="candidate-select">
                  <button type="button" class="candidate-select-trigger" @click="endCandidateDropdownOpen = !endCandidateDropdownOpen">
                    <span>{{ endCandidateSummary }}</span>
                    <strong>{{ endCandidateDropdownOpen ? '收起' : '展开' }}</strong>
                  </button>
                  <div v-if="endCandidateDropdownOpen" class="candidate-select-menu">
                    <button
                      v-for="anchor in endAnchorOptions"
                      :key="anchor.key"
                      type="button"
                      class="candidate-select-option"
                      :class="{ active: isEndCandidateSelected(anchor.key) }"
                      @click="toggleEndCandidate(anchor.key)"
                    >
                      <span>{{ anchor.label }}</span>
                      <strong v-if="isEndCandidateSelected(anchor.key)">✓</strong>
                    </button>
                    <div v-if="endAnchorOptions.length === 0" class="candidate-select-empty">暂无可选终点</div>
                  </div>
                </div>
              </div>
              <label>
                起点经度
                <input v-model.number="optimizeOptions.startLongitude" :disabled="startAnchorMode !== 'manual'" type="number" step="0.000001" placeholder="手填" />
              </label>
              <label>
                起点纬度
                <input v-model.number="optimizeOptions.startLatitude" :disabled="startAnchorMode !== 'manual'" type="number" step="0.000001" placeholder="手填" />
              </label>
              <label>
                终点经度
                <input v-model.number="optimizeOptions.endLongitude" :disabled="endAnchorMode !== 'manual'" type="number" step="0.000001" placeholder="手填" />
              </label>
              <label>
                终点纬度
                <input v-model.number="optimizeOptions.endLatitude" :disabled="endAnchorMode !== 'manual'" type="number" step="0.000001" placeholder="手填" />
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
                <span>车辆排班</span>
                <strong>{{ dispatchEnabled ? dispatchTripCount + ' 趟' : '未启用' }}</strong>
              </div>
              <div>
                <span>排班额定总量</span>
                <strong>{{ dispatchEnabled ? formatWeight(plannedCapacityKg) : '-' }}</strong>
              </div>
              <div>
                <span>场站候选</span>
                <strong>{{ routeAnchorCount }}</strong>
              </div>
              <div>
                <span>当前模式</span>
                <strong>{{ routeModeSummary }}</strong>
              </div>
            </div>
            <section v-if="selectedMultiCompany" class="dispatch-panel">
              <div class="dispatch-head">
                <div>
                  <h3>车辆排班</h3>
                  <small>未添加车辆时沿用上方额定载重和最大趟数；添加后按下方顺序逐趟生成</small>
                </div>
                <div class="dispatch-actions">
                  <button @click="syncVehiclesWithDefaultCapacity" :disabled="!dispatchEnabled">套用上方载重</button>
                  <button @click="addDispatchVehicle">{{ dispatchEnabled ? '添加车辆' : '启用排班' }}</button>
                </div>
              </div>
              <div v-if="!dispatchEnabled" class="dispatch-empty">
                当前未启用车辆排班，多路线生成会使用上方额定载重、目标装载率和最大趟数。
              </div>
              <div v-else class="dispatch-mode-row">
                <label>
                  排班方式
                  <select v-model="dispatchMode" @change="clearMultiOptimization">
                    <option value="USER_ORDER">按车辆顺序跑完</option>
                    <option value="ROUND_ROBIN">车辆轮询排班</option>
                    <option value="USER_ORDER_THEN_ROUND_ROBIN">顺序跑完后轮询兜底</option>
                  </select>
                </label>
                <small>{{ dispatchModeHint }}</small>
              </div>
              <div v-if="dispatchEnabled" class="dispatch-table">
                <div class="dispatch-row dispatch-row-head">
                  <span>顺序</span>
                  <span>车辆</span>
                  <span>车型</span>
                  <span>额定kg</span>
                  <span>最大kg</span>
                  <span>趟数</span>
                  <span>操作</span>
                </div>
                <div v-for="(vehicle, index) in dispatchVehicles" :key="vehicle.localId" class="dispatch-row">
                  <strong>{{ index + 1 }}</strong>
                  <input v-model="vehicle.vehicleName" @input="clearMultiOptimization" placeholder="车牌/车辆名" />
                  <input v-model="vehicle.vehicleType" @input="clearMultiOptimization" placeholder="车型" />
                  <input v-model.number="vehicle.ratedCapacityKg" @input="clearMultiOptimization" type="number" min="1" step="100" />
                  <input v-model.number="vehicle.maxCapacityKg" @input="clearMultiOptimization" type="number" min="1" step="100" />
                  <input v-model.number="vehicle.tripCount" @input="clearMultiOptimization" :disabled="dispatchMode === 'ROUND_ROBIN'" :title="dispatchMode === 'ROUND_ROBIN' ? '轮询模式按最大趟数生成，不读取单车趟数' : ''" type="number" min="1" step="1" />
                  <div class="dispatch-row-actions">
                    <button @click="moveDispatchVehicle(index, -1)" :disabled="index === 0">上移</button>
                    <button @click="moveDispatchVehicle(index, 1)" :disabled="index === dispatchVehicles.length - 1">下移</button>
                    <button @click="removeDispatchVehicle(index)">删除</button>
                  </div>
                </div>
              </div>
            </section>
            <div v-else class="empty">选择公司后读取点位池</div>
          </section>

          <section class="panel split-panel multi-layout">
            <div>
              <div class="panel-head compact">
                <h2>公司点位池</h2>
                <span class="muted">{{ companyPointVisibleList.length }} / {{ companyPoints.length }} 个点</span>
              </div>
              <p v-if="selectedClusterDisplayGroup" class="cluster-filter-note">
                当前显示：{{ selectedClusterDisplayStage === 'before' ? '聚类前' : '聚类后' }} · {{ selectedClusterDisplayGroup.groupName }}
              </p>
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
                <button @click="triggerImportFacilityNames" :disabled="companyPoints.length === 0 || loading">
                  导入选中
                </button>
                <input
                  ref="facilityImportInput"
                  class="hidden-file-input"
                  type="file"
                  accept=".xls,.xlsx"
                  @change="importFacilityNames"
                />
              </div>
              <p v-if="facilityImportSummary" class="import-summary">{{ facilityImportSummary }}</p>
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
                <div class="panel-actions">
                  <span class="muted">公司级多路线</span>
                  <button @click="exportCompanyRoutes" :disabled="!multiOptimization || loading">导出Excel</button>
                </div>
              </div>
              <div v-if="routeProgress.visible" class="planning-progress">
                <div class="planning-progress-head">
                  <div>
                    <strong>路线规划进度</strong>
                    <small>{{ routeProgress.message }}</small>
                    <small v-if="routeProgressDetail">{{ routeProgressDetail }}</small>
                  </div>
                  <span>{{ routeProgressPercent }}%</span>
                </div>
                <div class="planning-progress-bar">
                  <i :style="{ width: routeProgressPercent + '%' }"></i>
                </div>
                <ol>
                  <li
                    v-for="(step, index) in routeProgress.steps"
                    :key="step.key"
                    :class="progressStepClass(index)"
                  >
                    <span>{{ progressStepMark(index) }}</span>
                    <div>
                      <strong>{{ step.title }}</strong>
                      <small>{{ step.detail }}</small>
                    </div>
                  </li>
                </ol>
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
                  <span>计划趟次 {{ multiOptimization.dispatchTripCount || 0 }}</span>
                  <span>计划容量 {{ formatWeight(multiOptimization.totalPlannedCapacityKg) }}</span>
                  <span>策略 {{ planningStrategyLabel(multiOptimization.planningStrategy) }}</span>
                  <span>分组 {{ multiOptimization.distanceMode === 'ROAD' ? '实际路线距离' : '直线距离' }}</span>
                  <span>精排 {{ multiOptimization.refineMode === 'ROAD' ? '道路距离' : '无' }}</span>
                </div>
                <div class="multi-routes">
                  <article
                    v-for="route in multiOptimization.routes"
                    :key="route.routeNo"
                    class="multi-route-card"
                    :class="{ active: selectedMultiRouteNo === route.routeNo }"
                    @click="selectedMultiRouteNo = route.routeNo"
                  >
                    <div>
                      <strong>第 {{ route.routeNo }} 趟 · {{ route.vehicleName || '默认车辆' }} 第{{ route.tripNo || route.routeNo }}趟</strong>
                      <small>
                        {{ route.vehicleType || '未填车型' }} · 额定 {{ formatWeight(route.ratedCapacityKg) }} ·
                        {{ route.pointCount }} 点 · {{ formatWeight(route.estimatedWeightKg) }} ·
                        {{ formatDistance(routeDisplayDistance(route)) }} · 行驶 {{ formatDuration(routeDisplayTravelDuration(route)) }} ·
                        作业 {{ formatDuration(route.operationDurationMinutes) }} · 合计 {{ formatDuration(routeDisplayTotalDuration(route)) }} ·
                        装载率 {{ formatLoadRate(route.loadRate) }} ·
                        {{ pathSourceSummary(routeDisplaySegments(route)) }}
                      </small>
                    </div>
                    <div class="sequence route-point-sequence">
                      <span v-for="point in route.points" :key="`${route.routeNo}-${point.order}-${point.facilityId}`">
                        {{ point.facilityName || point.facilityId }}
                      </span>
                    </div>
                  </article>
                </div>
                <div v-if="selectedMultiRoute" class="route-display-toolbar">
                  <span>地图展示</span>
                  <button
                    :class="{ active: !selectedMultiRouteRoadDisplay }"
                    @click.stop="setSelectedMultiRouteDisplay(false)"
                  >
                    点位直线
                  </button>
                  <button
                    :class="{ active: selectedMultiRouteRoadDisplay }"
                    :disabled="routeSegmentLoading"
                    @click.stop="setSelectedMultiRouteDisplay(true)"
                  >
                    {{ routeSegmentLoading ? '加载道路...' : '道路折线' }}
                  </button>
                  <small>{{ selectedMultiRouteDisplaySummary }}</small>
                </div>
                <RouteMapPanel
                  v-if="selectedMultiRoute"
                  :original-points="[]"
                  :optimized-points="selectedMultiRoute.points || []"
                  :optimized-segments="selectedMultiRouteDisplaySegments"
                  :show-original="false"
                  optimized-label="生成路线"
                />
                <div v-if="selectedMultiRoute" class="route-time-detail">
                  <div class="route-time-summary">
                    <span>行驶 {{ formatDuration(selectedMultiRouteDisplayTravelDuration) }}</span>
                    <span>作业 {{ formatDuration(selectedMultiRoute.operationDurationMinutes) }}</span>
                    <strong>合计 {{ formatDuration(selectedMultiRouteDisplayTotalDuration) }}</strong>
                  </div>
                  <div class="route-time-table">
                    <div class="route-time-row head">
                      <span>顺序</span>
                      <span>点位</span>
                      <span>上一段行驶</span>
                      <span>桶信息</span>
                      <span>总桶数</span>
                      <span>点位作业</span>
                    </div>
                    <div v-for="point in selectedMultiRoute.points" :key="`time-${selectedMultiRoute.routeNo}-${point.order}-${point.facilityId}`" class="route-time-row">
                      <span>{{ point.order }}</span>
                      <span>{{ point.facilityName || point.facilityId }} <small>{{ routePointRoleLabel(point) }}</small></span>
                      <span v-if="segmentBeforePoint(selectedMultiRouteDisplaySegments, point.order)">
                        {{ formatDistance(segmentBeforePoint(selectedMultiRouteDisplaySegments, point.order).distance) }} ·
                        {{ formatDuration(segmentBeforePoint(selectedMultiRouteDisplaySegments, point.order).durationMinutes) }}
                      </span>
                      <span v-else>-</span>
                      <span :title="point.containerInfo || '-'">{{ point.containerInfo || '-' }}</span>
                      <span>{{ formatNumber(point.containerCount) }}</span>
                      <span>{{ formatDuration(point.operationDurationMinutes) }}</span>
                    </div>
                  </div>
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


    <template v-else-if="currentView === 'cluster'">
      <section class="multi-shell cluster-shell">
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

        <section class="multi-main cluster-main">
          <section class="panel cluster-panel standalone">
            <div class="cluster-head">
              <div>
                <h2>区域划分</h2>
                <small>先从当前公司点位池选择点位，或导入 Excel 名称匹配点位；多 sheet 会作为聚类前原始区域。</small>
              </div>
              <div class="cluster-actions">
                <label>
                  聚类模式
                  <select v-model="clusterMode">
                    <option value="balanced">地理+点数紧凑优化</option>
                    <option value="geo">纯地理聚类</option>
                  </select>
                </label>
                <label>
                  目标分堆数
                  <input v-model.number="clusterTargetGroupCount" type="number" min="1" step="1" placeholder="自动" />
                </label>
                <button @click="triggerImportFacilityNames" :disabled="companyPoints.length === 0 || loading">上传Excel</button>
                <button @click="generateClusterPreview" :disabled="selectedCompanyPointCount === 0 || loading">生成聚类信息</button>
                <button @click="exportClusterPreview" :disabled="!clusterPreview || loading">导出全部分区</button>
              </div>
            </div>
            <div v-if="selectedMultiCompany" class="route-overview">
              <div>
                <span>公司</span>
                <strong>{{ selectedMultiCompany.depName || selectedMultiCompany.id }}</strong>
              </div>
              <div>
                <span>已选点位</span>
                <strong>{{ selectedCompanyPointCount }} / {{ companyPoints.length }}</strong>
              </div>
              <div>
                <span>预计总量</span>
                <strong>{{ formatWeight(selectedCompanyPointWeight) }}</strong>
              </div>
              <div>
                <span>目标分堆</span>
                <strong>{{ clusterPreview?.targetGroupCount || clusterTargetGroupCount || '自动' }}</strong>
              </div>
            </div>
            <div class="cluster-time-config">
              <span class="muted">作业统计口径（仅用于展示，不参与聚类）</span>
              <label>每桶秒<input v-model.number="clusterTimeConfig.secondsPerContainer" type="number" min="1" step="1" /></label>
              <label>每点分钟<input v-model.number="clusterTimeConfig.minutesPerPoint" type="number" min="0" step="0.5" /></label>
              <span v-if="selectedClusterGroup" class="cluster-current">当前优化对象：{{ selectedClusterGroup.groupName }} · {{ selectedClusterGroup.pointCount }} 点</span>
              <span v-else class="muted">选择聚类后某一堆后，多路线生成会优先使用该堆点位。</span>
            </div>
            <input
              ref="facilityImportInput"
              class="hidden-file-input"
              type="file"
              accept=".xls,.xlsx"
              @change="importFacilityNames"
            />
          </section>

          <section v-if="selectedMultiCompany" class="panel split-panel multi-layout cluster-workspace">
            <div>
              <div class="panel-head compact">
                <h2>公司点位池</h2>
                <span class="muted">{{ companyPointVisibleList.length }} / {{ companyPoints.length }} 个点</span>
              </div>
              <p v-if="selectedClusterDisplayGroup" class="cluster-filter-note">
                当前显示：{{ selectedClusterDisplayStage === 'before' ? '聚类前' : '聚类后' }} · {{ selectedClusterDisplayGroup.groupName }}
              </p>
              <div class="point-toolbar">
                <input v-model="pointKeyword" placeholder="搜索点位、桶信息" />
                <label class="inline-check">
                  <input v-model="showSelectedOnly" type="checkbox" />
                  只看已选
                </label>
              </div>
              <div class="point-actions">
                <button @click="selectVisibleCompanyPoints" :disabled="companyPointVisibleList.length === 0">选中当前结果</button>
                <button @click="unselectVisibleCompanyPoints" :disabled="companyPointVisibleList.length === 0">排除当前结果</button>
                <button @click="selectAllCompanyPoints" :disabled="companyPoints.length === 0">全选</button>
                <button @click="clearCompanyPointSelection" :disabled="selectedCompanyPointCount === 0">清空</button>
              </div>
              <p v-if="facilityImportSummary" class="import-summary">{{ facilityImportSummary }}</p>
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
                <h2>聚类信息</h2>
                <span class="muted">聚类前 / 聚类后对比</span>
              </div>
              <div v-if="clusterPreview" class="cluster-preview-layout vertical">
                <ClusterMapPanel
                  :groups="clusterPlotGroups"
                  :selected-group-id="selectedClusterDisplayGroupId"
                  :stage-label="selectedClusterDisplayStage === 'before' ? '聚类前' : '聚类后'"
                  @select-group="selectClusterMapGroup"
                />
                <div class="cluster-groups">
                  <div class="cluster-column">
                    <h4>聚类前分堆</h4>
                    <article
                      v-for="group in clusterBeforeGroups"
                      :key="group.groupId"
                      class="cluster-card selectable-cluster"
                      :class="{ active: isClusterGroupHighlighted(group, 'before') }"
                      @click="selectClusterDisplayGroup(group, 'before')"
                    >
                      <span class="cluster-color" :style="{ backgroundColor: group.color }"></span>
                      <div>
                        <strong>{{ group.groupName }}</strong>
                        <small>{{ clusterStatsText(group) }}</small>
                      </div>
                      <div class="cluster-card-actions">
                        <button class="cluster-card-action" @click.stop="exportClusterGroup(group, 'before')">导出</button>
                        <button class="cluster-card-action primary" @click.stop="routeFromClusterGroup(group, 'before')">生成路线</button>
                      </div>
                    </article>
                  </div>
                  <div class="cluster-column">
                    <h4>聚类后分堆</h4>
                    <article
                      v-for="group in clusterAfterGroups"
                      :key="group.groupId"
                      class="cluster-card selectable-cluster"
                      :class="{ active: isClusterGroupHighlighted(group, 'after') }"
                      @click="selectClusterGroup(group.groupId)"
                    >
                      <span class="cluster-color" :style="{ backgroundColor: group.color }"></span>
                      <div>
                        <input v-model="group.groupName" @click.stop />
                        <small>{{ clusterStatsText(group) }}</small>
                      </div>
                      <div class="cluster-card-actions">
                        <button class="cluster-card-action" @click.stop="exportClusterGroup(group, 'after')">导出</button>
                        <button class="cluster-card-action primary" @click.stop="routeFromClusterGroup(group, 'after')">生成路线</button>
                      </div>
                    </article>
                  </div>
                </div>
                <section v-if="clusterIntersectionMatrix.rows.length" class="cluster-intersection-panel">
                  <div class="panel-head compact">
                    <h3>分区交集统计</h3>
                    <span class="muted">聚类前 × 聚类后，格子越深表示重合点位越多</span>
                  </div>
                  <div class="cluster-heatmap-scroll">
                    <table class="cluster-heatmap">
                      <thead>
                        <tr>
                          <th>聚类前 \ 聚类后</th>
                          <th v-for="column in clusterIntersectionMatrix.columns" :key="column.groupId">
                            <span class="heatmap-head-dot" :style="{ backgroundColor: column.color }"></span>
                            {{ column.groupName }}
                          </th>
                        </tr>
                      </thead>
                      <tbody>
                        <tr v-for="row in clusterIntersectionMatrix.rows" :key="row.groupId">
                          <th>
                            <span class="heatmap-head-dot" :style="{ backgroundColor: row.color }"></span>
                            {{ row.groupName }}
                          </th>
                          <td
                            v-for="cell in row.cells"
                            :key="cell.columnId"
                            :class="{ dominant: cell.isRowDominant }"
                            :style="heatmapCellStyle(cell)"
                            :title="`${row.groupName} 与 ${cell.columnName} 交集 ${cell.count} 点，占原分区 ${cell.rowRatioText}`"
                          >
                            <strong>{{ cell.count }}</strong>
                            <small>{{ cell.rowRatioText }}</small>
                          </td>
                        </tr>
                      </tbody>
                    </table>
                  </div>
                  <ul class="cluster-intersection-insights">
                    <li v-for="item in clusterIntersectionInsights" :key="item">{{ item }}</li>
                  </ul>
                </section>
              </div>
              <div v-else class="cluster-empty">选择公司后，可直接用已选点位生成聚类；上传 Excel 后会先按 sheet 形成原始区域，再生成聚类后区域。</div>
            </div>
          </section>
          <div v-else class="panel empty">选择公司后读取点位池</div>
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
import RouteMapPanel from './components/RouteMapPanel.vue'
import ClusterMapPanel from './components/ClusterMapPanel.vue'

const currentView = ref('score')
const companies = ref([])
const routes = ref([])
const splitRoutes = ref([])
const records = ref([])
const planPoints = ref([])
const splitPlanPoints = ref([])
const recordPoints = ref([])
const companyPoints = ref([])
const facilityImportInput = ref(null)
const facilityImportSummary = ref('')
const importedOriginalGroups = ref([])
const clusterPreview = ref(null)
const clusterTargetGroupCount = ref(null)
const clusterMode = ref('balanced')
const selectedClusterGroupId = ref('')
const selectedClusterDisplayStage = ref('after')
const selectedClusterDisplayGroupId = ref('')
const groupOptimizationResults = ref({})
const activeOptimizationGroupId = ref('')
const clusterTimeConfig = reactive({
  secondsPerContainer: 35,
  minutesPerPoint: 3,
  workHours: 8
})
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
const routeMapStatus = ref(null)
const routeProgressTimer = ref(null)
const routeAbortController = ref(null)
const routeSegmentLoading = ref(false)
const multiRouteDisplayModes = ref({})
const routeProgress = reactive({
  visible: false,
  activeIndex: -1,
  done: false,
  failed: false,
  message: '',
  steps: [],
  taskId: '',
  status: '',
  phase: '',
  currentRouteNo: 0,
  totalRoutes: 0,
  completedRoutes: 0,
  currentRoutePoints: 0,
  assignedPoints: 0,
  remainingPoints: 0,
  currentVehicleName: '',
  currentTripNo: 0
})

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
const selectedSplitCompany = ref(null)
const selectedSplitRoute = ref(null)
const selectedRoute = ref(null)
const selectedRecord = ref(null)
const selectedMultiRouteNo = ref(null)
const startAnchorMode = ref('parking')
const endAnchorMode = ref('facility')
const selectedStartAnchorKey = ref('')
const selectedEndAnchorKey = ref('')
const selectedEndCandidateKeys = ref([])
const endCandidateDropdownOpen = ref(false)
const selectedCompanyPointIds = ref(new Set())
const dispatchVehicles = ref([])
const dispatchMode = ref('USER_ORDER')

const companyKeyword = ref('')
const pointKeyword = ref('')
const dataType = ref(0)
const loading = ref(false)
const error = ref('')
const showSelectedOnly = ref(false)
const frontendMapAkConfigured = Boolean(import.meta.env.VITE_BAIDU_MAP_AK)
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
  secondsPerContainer: 35,
  minutesPerPoint: 3,
  useRoadPath: false,
  multiRouteStrategy: 'DIRECT_GROUP',
  displayRoadPath: false,
  startLongitude: null,
  startLatitude: null,
  startFacilityName: null,
  endLongitude: null,
  endLatitude: null,
  endFacilityName: null
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
const selectedCompanyPoints = computed(() =>
  companyPoints.value.filter((point) => isCompanyPointSelected(point.facilityId))
)
const selectedCompanyPointCount = computed(() => selectedCompanyPointIds.value.size)
const selectedCompanyPointWeight = computed(() =>
  selectedCompanyPoints.value.reduce((sum, point) => sum + Number(point.estimatedWeightKg || 0), 0)
)
const clusterBeforeGroups = computed(() => clusterPreview.value?.beforeGroups || [])
const clusterAfterGroups = computed(() => clusterPreview.value?.afterGroups || [])
const selectedClusterGroup = computed(() =>
  clusterAfterGroups.value.find((group) => group.groupId === selectedClusterGroupId.value) || null
)
const currentOptimizationFacilityIds = computed(() => {
  if (selectedClusterDisplayGroup.value?.facilityIds?.length) {
    return selectedClusterDisplayGroup.value.facilityIds.map((id) => String(id))
  }
  if (selectedClusterGroup.value?.facilityIds?.length) {
    return selectedClusterGroup.value.facilityIds.map((id) => String(id))
  }
  return Array.from(selectedCompanyPointIds.value)
})
const currentClusterRouteKey = computed(() => {
  if (selectedClusterDisplayGroup.value?.groupId) {
    return `${selectedClusterDisplayStage.value}:${selectedClusterDisplayGroup.value.groupId}`
  }
  return '__all__'
})
const currentOptimizationPointCount = computed(() => currentOptimizationFacilityIds.value.length)
const clusterPlotGroups = computed(() => {
  if (selectedClusterDisplayStage.value === 'before') return clusterBeforeGroups.value
  return clusterAfterGroups.value.length ? clusterAfterGroups.value : clusterBeforeGroups.value
})
const selectedClusterDisplayGroup = computed(() => {
  const groups = selectedClusterDisplayStage.value === 'before' ? clusterBeforeGroups.value : clusterAfterGroups.value
  return groups.find((group) => group.groupId === selectedClusterDisplayGroupId.value) || null
})
const selectedClusterDisplayFacilityIds = computed(() => {
  const ids = selectedClusterDisplayGroup.value?.facilityIds || []
  return ids.length ? new Set(ids.map((id) => String(id))) : null
})
const clusterIntersectionMatrix = computed(() => {
  const columns = clusterAfterGroups.value
  const maxCountHolder = { value: 0 }
  const rows = clusterBeforeGroups.value.map((before) => {
    const beforeIds = new Set((before.facilityIds || []).map((id) => String(id)))
    const rowTotal = beforeIds.size
    let rowMax = 0
    const cells = columns.map((after) => {
      const afterIds = new Set((after.facilityIds || []).map((id) => String(id)))
      let count = 0
      beforeIds.forEach((id) => {
        if (afterIds.has(id)) count += 1
      })
      rowMax = Math.max(rowMax, count)
      maxCountHolder.value = Math.max(maxCountHolder.value, count)
      return {
        columnId: after.groupId,
        columnName: after.groupName,
        count,
        rowRatio: rowTotal ? count / rowTotal : 0,
        rowRatioText: rowTotal ? `${Math.round((count / rowTotal) * 100)}%` : '-'
      }
    })
    cells.forEach((cell) => {
      cell.isRowDominant = rowMax > 0 && cell.count === rowMax
    })
    return {
      groupId: before.groupId,
      groupName: before.groupName,
      color: before.color,
      total: rowTotal,
      cells
    }
  })
  return {
    columns,
    rows,
    maxCount: maxCountHolder.value
  }
})
const clusterIntersectionInsights = computed(() => {
  const matrix = clusterIntersectionMatrix.value
  const insights = []
  matrix.rows.forEach((row) => {
    const best = [...row.cells].sort((a, b) => b.count - a.count)[0]
    if (best && best.count > 0) {
      insights.push(`${row.groupName} 主要进入 ${best.columnName}：${best.count}/${row.total} 点，占 ${best.rowRatioText}`)
    }
  })
  matrix.columns.forEach((column) => {
    let bestRow = null
    let columnTotal = 0
    matrix.rows.forEach((row) => {
      const cell = row.cells.find((item) => item.columnId === column.groupId)
      if (!cell) return
      columnTotal += cell.count
      if (!bestRow || cell.count > bestRow.count) {
        bestRow = { rowName: row.groupName, count: cell.count }
      }
    })
    if (bestRow && bestRow.count > 0 && columnTotal > 0) {
      insights.push(`${column.groupName} 主要由 ${bestRow.rowName} 构成：${bestRow.count}/${columnTotal} 点，占 ${Math.round((bestRow.count / columnTotal) * 100)}%`)
    }
  })
  return insights.slice(0, 8)
})
const clusterPlotBounds = computed(() => {
  const points = clusterPlotGroups.value.flatMap((group) => group.points || [])
    .filter((point) => Number.isFinite(Number(point.longitude)) && Number.isFinite(Number(point.latitude)))
  if (!points.length) return { minLng: 0, maxLng: 1, minLat: 0, maxLat: 1 }
  const lngs = points.map((point) => Number(point.longitude))
  const lats = points.map((point) => Number(point.latitude))
  const minLng = Math.min(...lngs)
  const maxLng = Math.max(...lngs)
  const minLat = Math.min(...lats)
  const maxLat = Math.max(...lats)
  return {
    minLng,
    maxLng: maxLng === minLng ? minLng + 0.01 : maxLng,
    minLat,
    maxLat: maxLat === minLat ? minLat + 0.01 : maxLat
  }
})
const selectedMultiRoute = computed(() => {
  if (!multiOptimization.value?.routes?.length) {
    return null
  }
  return multiOptimization.value.routes.find((route) => route.routeNo === selectedMultiRouteNo.value)
    || multiOptimization.value.routes[0]
})
const selectedMultiRouteRoadDisplay = computed(() => {
  const routeNo = selectedMultiRoute.value?.routeNo
  return !!(routeNo && multiRouteDisplayModes.value[routeNo] === 'ROAD')
})
const selectedMultiRouteDisplaySegments = computed(() => {
  const route = selectedMultiRoute.value
  if (!route) return []
  return routeDisplaySegments(route)
})
function routeRoadDisplay(route) {
  return !!(route?.routeNo && multiRouteDisplayModes.value[route.routeNo] === 'ROAD')
}
function routeDisplaySegments(route) {
  if (!route) return []
  if (routeRoadDisplay(route) && route.roadSegments?.length) {
    return route.roadSegments
  }
  return route.segments || []
}
function routeDisplayDistance(route) {
  if (!route) return 0
  return routeRoadDisplay(route) && route.roadDistance ? route.roadDistance : route.distance
}
function routeDisplayTravelDuration(route) {
  if (!route) return 0
  return routeRoadDisplay(route) && route.roadDurationMinutes ? route.roadDurationMinutes : route.travelDurationMinutes
}
function routeDisplayTotalDuration(route) {
  if (!route) return 0
  return Number(routeDisplayTravelDuration(route) || 0) + Number(route.operationDurationMinutes || 0)
}
const selectedMultiRouteDisplayTravelDuration = computed(() => {
  const route = selectedMultiRoute.value
  if (!route) return 0
  return routeDisplayTravelDuration(route)
})
const selectedMultiRouteDisplayTotalDuration = computed(() => {
  const route = selectedMultiRoute.value
  if (!route) return 0
  return routeDisplayTotalDuration(route)
})
const selectedMultiRouteDisplaySummary = computed(() => {
  const route = selectedMultiRoute.value
  if (!route) return ''
  const segments = selectedMultiRouteDisplaySegments.value
  const distance = routeDisplayDistance(route)
  const duration = selectedMultiRouteDisplayTotalDuration.value || route.durationMinutes
  return formatDistance(distance) + ' · ' + formatDuration(duration) + ' · ' + pathSourceSummary(segments)
})
const companyPointVisibleList = computed(() =>
  companyPoints.value.filter((point) => {
    if (selectedClusterDisplayFacilityIds.value && !selectedClusterDisplayFacilityIds.value.has(String(point.facilityId))) {
      return false
    }
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
const startAnchorOptions = computed(() => anchorOptionsByMode(startAnchorMode.value))
const endAnchorOptions = computed(() => anchorOptionsByMode(endAnchorMode.value))
const isEndCandidateMode = computed(() => endAnchorMode.value === 'disposalAny' || endAnchorMode.value === 'terminalAny')
const endCandidateSummary = computed(() => {
  const selected = selectedEndCandidateAnchors()
  if (selected.length === 0) return '请选择候选终点'
  if (selected.length === endAnchorOptions.value.length) return `已选择全部 ${selected.length} 个终点`
  return `已选择 ${selected.length} 个终点`
})
const routeProgressPercent = computed(() => {
  const total = routeProgress.steps.length
  if (!routeProgress.visible || total === 0) return 0
  if (routeProgress.done) return 100
  const active = Math.max(0, routeProgress.activeIndex)
  return Math.min(95, Math.round((active / total) * 100))
})
const routeAnchorCount = computed(
  () =>
    (companyAnchors.value.facilityAnchors?.length || 0) +
    (companyAnchors.value.parkingLots?.length || 0)
)
const routeModeSummary = computed(() => {
  const routing = planningStrategyLabel(optimizeOptions.multiRouteStrategy)
  return routing
})
const splitPlanWeight = computed(() =>
  splitPlanPoints.value.reduce((sum, point) => sum + Number(point.estimatedWeightKg || 0), 0)
)
const splitPlanVolume = computed(() =>
  splitPlanPoints.value.reduce((sum, point) => sum + Number(point.estimatedVolumeLiter || 0), 0)
)

const dispatchModeHint = computed(() => {
  if (dispatchMode.value === 'ROUND_ROBIN') {
    return '忽略每辆车趟数，按最大趟数大车优先轮询；适合只知道车辆清单、让系统自行派车。'
  }
  if (dispatchMode.value === 'USER_ORDER_THEN_ROUND_ROBIN') {
    return '先按列表顺序跑完每辆车填写的趟数；若仍未达到最大趟数或仍有剩余点位，再按大车优先轮询兜底。'
  }
  return '按列表顺序先跑完一辆车填写的全部趟次，再排下一辆车；适合用户已明确派车表。'
})

const routeProgressDetail = computed(() => {
  if (!routeProgress.taskId) return ''
  const parts = []
  if (routeProgress.currentRouteNo && routeProgress.totalRoutes) {
    parts.push('第 ' + routeProgress.currentRouteNo + ' / ' + routeProgress.totalRoutes + ' 趟')
  } else if (routeProgress.totalRoutes) {
    parts.push('计划最多 ' + routeProgress.totalRoutes + ' 趟')
  }
  if (routeProgress.currentVehicleName) {
    parts.push(routeProgress.currentVehicleName + (routeProgress.currentTripNo ? ' 第' + routeProgress.currentTripNo + '趟' : ''))
  }
  if (routeProgress.currentRoutePoints) parts.push('当前 ' + routeProgress.currentRoutePoints + ' 点')
  if (routeProgress.assignedPoints) parts.push('已分配 ' + routeProgress.assignedPoints + ' 点')
  if (routeProgress.remainingPoints) parts.push('剩余 ' + routeProgress.remainingPoints + ' 点')
  return parts.join(' · ')
})
const dispatchEnabled = computed(() => dispatchVehicles.value.length > 0)
const dispatchTripCount = computed(() =>
  dispatchVehicles.value.reduce((sum, vehicle) => sum + Math.max(1, Number(vehicle.tripCount || 1)), 0)
)
const plannedCapacityKg = computed(() =>
  dispatchVehicles.value.reduce((sum, vehicle) => {
    return sum + Number(vehicle.ratedCapacityKg || 0) * Math.max(1, Number(vehicle.tripCount || 1))
  }, 0)
)

onMounted(async () => {
  await Promise.all([loadCompanies(), loadRouteMapStatus()])
})

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

async function loadRouteMapStatus() {
  try {
    routeMapStatus.value = await api('/api/route-map/status')
  } catch (e) {
    routeMapStatus.value = null
  }
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
  clearMultiOptimization()
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

async function changeSplitDataType(value) {
  if (dataType.value === value) return
  dataType.value = value
  selectedSplitRoute.value = null
  splitRoutes.value = []
  splitPlanPoints.value = []
  clearMultiOptimization()
  if (selectedSplitCompany.value) {
    await selectSplitCompany(selectedSplitCompany.value)
  }
}

async function selectSplitCompany(company) {
  selectedSplitCompany.value = company
  selectedSplitRoute.value = null
  splitRoutes.value = []
  splitPlanPoints.value = []
  resetAnchors()
  clearMultiOptimization()
  await withLoading(async () => {
    const [routeRows, anchors] = await Promise.all([
      api(`/api/companies/${company.id}/routes?dataType=${dataType.value}`),
      api(`/api/companies/${company.id}/route-anchors`)
    ])
    splitRoutes.value = routeRows
    companyAnchors.value = normalizeAnchors(anchors)
    applyDefaultAnchors()
  })
}

async function selectSplitRoute(route) {
  selectedSplitRoute.value = route
  splitPlanPoints.value = []
  clearMultiOptimization()
  await withLoading(async () => {
    splitPlanPoints.value = await api(`/api/routes/${route.id}/plan-points`)
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
  facilityImportSummary.value = ''
  resetClusterState()
  resetAnchors()
  clearMultiOptimization()
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
  resetClusterState()
  clearMultiOptimization()
}

function selectAllCompanyPoints() {
  selectedCompanyPointIds.value = new Set(companyPoints.value.map((point) => String(point.facilityId)))
  resetClusterState()
  clearMultiOptimization()
}

function clearCompanyPointSelection() {
  selectedCompanyPointIds.value = new Set()
  resetClusterState()
  clearMultiOptimization()
}

function selectVisibleCompanyPoints() {
  const next = new Set(selectedCompanyPointIds.value)
  companyPointVisibleList.value.forEach((point) => next.add(String(point.facilityId)))
  selectedCompanyPointIds.value = next
  resetClusterState()
  clearMultiOptimization()
}

function unselectVisibleCompanyPoints() {
  const next = new Set(selectedCompanyPointIds.value)
  companyPointVisibleList.value.forEach((point) => next.delete(String(point.facilityId)))
  selectedCompanyPointIds.value = next
  resetClusterState()
  clearMultiOptimization()
}
function triggerImportFacilityNames() {
  facilityImportInput.value?.click()
}

async function importFacilityNames(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  if (companyPoints.value.length === 0) {
    facilityImportSummary.value = '请先选择公司并加载点位池'
    return
  }
  const formData = new FormData()
  formData.append('file', file)
  await withLoading(async () => {
    const response = await fetch('/api/import/facility-names', {
      method: 'POST',
      body: formData
    })
    if (!response.ok) {
      throw new Error(await response.text())
    }
    const result = await response.json()
    applyImportedFacilityGroups(result)
  })
}

function applyImportedFacilityNames(names) {
  const importedNames = new Set(names.map(normalizeFacilityName).filter(Boolean))
  const next = new Set()
  const matchedNames = new Set()
  companyPoints.value.forEach((point) => {
    const normalized = normalizeFacilityName(point.facilityName)
    if (importedNames.has(normalized)) {
      next.add(String(point.facilityId))
      matchedNames.add(normalized)
    }
  })
  selectedCompanyPointIds.value = next
  clearMultiOptimization()
  showSelectedOnly.value = true
  const unmatched = Math.max(0, importedNames.size - matchedNames.size)
  facilityImportSummary.value = `导入名称 ${importedNames.size} 个，匹配并选中 ${next.size} 个，未匹配 ${unmatched} 个`
}


function applyImportedFacilityGroups(result) {
  const groups = result.groups?.length ? result.groups : [{ groupName: result.sheetName || '导入点位', names: result.names || [] }]
  const pointByName = new Map()
  companyPoints.value.forEach((point) => {
    const key = normalizeFacilityName(point.facilityName)
    if (!key) return
    if (!pointByName.has(key)) pointByName.set(key, [])
    pointByName.get(key).push(point)
  })
  const selectedIds = new Set()
  const originalGroups = []
  let importedNameCount = 0
  let matchedNameCount = 0
  const unmatchedDetails = []
  groups.forEach((group, index) => {
    const names = group.names || []
    importedNameCount += names.length
    const groupIds = []
    const matchedNames = new Set()
    names.forEach((name) => {
      const normalized = normalizeFacilityName(name)
      const matches = pointByName.get(normalized) || []
      if (!matches.length) {
        if (unmatchedDetails.length < 20) unmatchedDetails.push(name)
        return
      }
      matchedNames.add(normalized)
      matches.forEach((point) => {
        const id = String(point.facilityId)
        selectedIds.add(id)
        groupIds.push(id)
      })
    })
    matchedNameCount += matchedNames.size
    if (groupIds.length) {
      originalGroups.push({
        groupId: `import-${index + 1}`,
        groupName: group.groupName || group.sheetName || `原始分堆${index + 1}`,
        sheetName: group.sheetName,
        facilityIds: Array.from(new Set(groupIds))
      })
    }
  })
  selectedCompanyPointIds.value = selectedIds
  importedOriginalGroups.value = originalGroups
  resetClusterPreviewOnly()
  clearMultiOptimization()
  showSelectedOnly.value = true
  const unmatched = Math.max(0, importedNameCount - matchedNameCount)
  const sheetText = result.matchedSheetCount ? `，识别 ${result.matchedSheetCount} 个sheet` : ''
  const detailText = unmatchedDetails.length ? `，未匹配示例：${unmatchedDetails.slice(0, 5).join('、')}` : ''
  facilityImportSummary.value = `导入名称 ${importedNameCount} 个${sheetText}，匹配并选中 ${selectedIds.size} 个点位，未匹配 ${unmatched} 个${detailText}`
}

function resetClusterPreviewOnly() {
  clusterPreview.value = null
  selectedClusterGroupId.value = ''
  selectedClusterDisplayStage.value = 'after'
  selectedClusterDisplayGroupId.value = ''
  groupOptimizationResults.value = {}
}

function resetClusterState() {
  importedOriginalGroups.value = []
  clusterPreview.value = null
  selectedClusterGroupId.value = ''
  selectedClusterDisplayStage.value = 'after'
  selectedClusterDisplayGroupId.value = ''
  groupOptimizationResults.value = {}
  activeOptimizationGroupId.value = ''
}

async function generateClusterPreview() {
  if (!selectedMultiCompany.value || selectedCompanyPointCount.value === 0) return
  await withLoading(async () => {
    clusterPreview.value = await api('/api/optimize/cluster-preview', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        unitId: selectedMultiCompany.value.id,
        facilityIds: Array.from(selectedCompanyPointIds.value),
        originalGroups: importedOriginalGroups.value,
        targetGroupCount: clusterTargetGroupCount.value || null,
        clusterMode: clusterMode.value,
        timeConfig: clusterTimeConfig
      })
    })
    selectedClusterGroupId.value = clusterAfterGroups.value[0]?.groupId || ''
    selectedClusterDisplayStage.value = selectedClusterGroupId.value ? 'after' : 'before'
    selectedClusterDisplayGroupId.value = selectedClusterGroupId.value || clusterBeforeGroups.value[0]?.groupId || ''
    restoreSelectedClusterOptimization()
  })
}

function selectClusterGroup(groupId) {
  selectedClusterGroupId.value = groupId
  selectedClusterDisplayStage.value = 'after'
  selectedClusterDisplayGroupId.value = groupId
  restoreSelectedClusterOptimization()
}


function selectClusterMapGroup(groupId) {
  if (selectedClusterDisplayStage.value === 'after') {
    selectClusterGroup(groupId)
    return
  }
  const group = clusterBeforeGroups.value.find((item) => item.groupId === groupId)
  selectClusterDisplayGroup(group, 'before')
}
function selectClusterDisplayGroup(group, stage) {
  selectedClusterDisplayStage.value = stage
  selectedClusterDisplayGroupId.value = group?.groupId || ''
}

function routeFromClusterGroup(group, stage) {
  if (!group?.facilityIds?.length) return
  if (stage === 'after') {
    selectClusterGroup(group.groupId)
  } else {
    selectClusterDisplayGroup(group, 'before')
  }
  pointKeyword.value = ''
  showSelectedOnly.value = true
  restoreSelectedClusterOptimization()
  currentView.value = 'multi'
}
function restoreSelectedClusterOptimization() {
  const key = currentClusterRouteKey.value
  multiOptimization.value = groupOptimizationResults.value[key] || null
  selectedMultiRouteNo.value = multiOptimization.value?.routes?.[0]?.routeNo || null
  multiRouteDisplayModes.value = {}
  resetRouteProgress()
}

async function exportClusterPreview() {
  if (!clusterPreview.value) return
  await withLoading(async () => {
    const response = await fetch('/api/optimize/cluster-export', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        company: selectedMultiCompany.value,
        generatedAt: new Date().toISOString(),
        ...clusterPreview.value
      })
    })
    if (!response.ok) {
      throw new Error(`${response.status} ${response.statusText}`)
    }
    downloadBlob(await response.blob(), `点位分堆结果-${toDateInput(new Date())}.xlsx`)
  })
}



function isClusterGroupHighlighted(group, stage) {
  return selectedClusterDisplayStage.value === stage && selectedClusterDisplayGroupId.value === group.groupId
}

function clusterGroupOpacity(group) {
  if (!selectedClusterDisplayGroupId.value) return 0.9
  return group.groupId === selectedClusterDisplayGroupId.value ? 1 : 0.22
}

function clusterPointRadius(group) {
  return group.groupId === selectedClusterDisplayGroupId.value ? 2.7 : 1.5
}

function clusterPointStroke(group) {
  return group.groupId === selectedClusterDisplayGroupId.value ? '#111827' : 'transparent'
}
async function exportClusterGroup(group, stage) {
  if (!group) return
  const payload = {
    company: selectedMultiCompany.value,
    generatedAt: new Date().toISOString(),
    status: clusterPreview.value?.status || 'DONE',
    message: `${stage === 'before' ? '聚类前' : '聚类后'}-${group.groupName}`,
    beforeGroups: stage === 'before' ? [group] : [],
    afterGroups: stage === 'after' ? [group] : [],
    timeConfig: clusterPreview.value?.timeConfig || clusterTimeConfig
  }
  await withLoading(async () => {
    const response = await fetch('/api/optimize/cluster-export', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    })
    if (!response.ok) {
      throw new Error(`${response.status} ${response.statusText}`)
    }
    const stageName = stage === 'before' ? '聚类前' : '聚类后'
    downloadBlob(await response.blob(), `${stageName}-${sanitizeFilename(group.groupName || group.groupId)}-${toDateInput(new Date())}.xlsx`)
  })
}

function sanitizeFilename(value) {
  return String(value || '分堆').replace(/[\\/:*?"<>|]/g, '_')
}
function heatmapCellStyle(cell) {
  const maxCount = Math.max(1, clusterIntersectionMatrix.value.maxCount || 0)
  const intensity = cell.count / maxCount
  const alpha = cell.count ? 0.12 + intensity * 0.5 : 0
  return {
    backgroundColor: `rgba(37, 99, 235, ${alpha.toFixed(3)})`,
    borderColor: cell.isRowDominant && cell.count ? 'rgba(37, 99, 235, 0.55)' : 'rgba(203, 213, 225, 0.75)'
  }
}
function clusterStatsText(group) {
  return `${group.pointCount || 0} 点 · 桶 ${formatNumber(group.containerCount)} · 660L ${formatNumber(group.container660Count)} · 240L ${formatNumber(group.container240Count)} · ${formatVolume(group.estimatedVolumeLiter)} · ${formatWeight(group.estimatedWeightKg)} · ${formatDuration(group.operationMinutes)}`
}

function clusterPlotX(longitude) {
  const n = Number(longitude)
  if (!Number.isFinite(n)) return 50
  const bounds = clusterPlotBounds.value
  return 5 + ((n - bounds.minLng) / (bounds.maxLng - bounds.minLng)) * 90
}

function clusterPlotY(latitude) {
  const n = Number(latitude)
  if (!Number.isFinite(n)) return 50
  const bounds = clusterPlotBounds.value
  return 95 - ((n - bounds.minLat) / (bounds.maxLat - bounds.minLat)) * 90
}
function normalizeFacilityName(value) {
  return String(value || '')
    .replace(/[\s　]+/g, '')
    .replace(/[（）]/g, (char) => (char === '（' ? '(' : ')'))
    .trim()
    .toLowerCase()
}


function clearMultiOptimization() {
  multiOptimization.value = null
  selectedMultiRouteNo.value = null
  multiRouteDisplayModes.value = {}
  groupOptimizationResults.value = {}
  activeOptimizationGroupId.value = ''
  if (!loading.value) {
    resetRouteProgress()
  }
}

function createDispatchVehicle(index) {
  const rated = Number(optimizeOptions?.ratedCapacityKg || 5000)
  return {
    localId: `${Date.now()}-${Math.random()}-${index}`,
    vehicleId: `vehicle-${index}`,
    vehicleName: `车辆${index}`,
    vehicleType: '',
    ratedCapacityKg: rated,
    maxCapacityKg: rated,
    tripCount: 1
  }
}

function addDispatchVehicle() {
  dispatchVehicles.value = [...dispatchVehicles.value, createDispatchVehicle(dispatchVehicles.value.length + 1)]
  clearMultiOptimization()
}

function removeDispatchVehicle(index) {
  dispatchVehicles.value = dispatchVehicles.value.filter((_, itemIndex) => itemIndex !== index)
  clearMultiOptimization()
}

function moveDispatchVehicle(index, offset) {
  const target = index + offset
  if (target < 0 || target >= dispatchVehicles.value.length) return
  const next = [...dispatchVehicles.value]
  const current = next[index]
  next[index] = next[target]
  next[target] = current
  dispatchVehicles.value = next
  clearMultiOptimization()
}

function syncVehiclesWithDefaultCapacity() {
  const rated = Number(optimizeOptions.ratedCapacityKg || 5000)
  dispatchVehicles.value = dispatchVehicles.value.map((vehicle) => ({
    ...vehicle,
    ratedCapacityKg: rated,
    maxCapacityKg: rated
  }))
  clearMultiOptimization()
}

function normalizedEndCandidates() {
  if (!isEndCandidateMode.value) {
    return []
  }
  return selectedEndCandidateAnchors().map((anchor) => ({
    facilityId: anchor.facilityId,
    facilityName: anchor.facilityName,
    facilityType: anchor.facilityType,
    facilityTypeName: anchor.facilityTypeName,
    longitude: Number(anchor.longitude),
    latitude: Number(anchor.latitude)
  }))
}
function normalizedDispatchVehicles() {
  if (!dispatchEnabled.value) {
    return []
  }
  return dispatchVehicles.value.map((vehicle, index) => {
    const rated = Math.max(1, Number(vehicle.ratedCapacityKg || optimizeOptions.ratedCapacityKg || 5000))
    const max = Math.max(rated, Number(vehicle.maxCapacityKg || rated))
    return {
      vehicleId: vehicle.vehicleId || `vehicle-${index + 1}`,
      vehicleName: vehicle.vehicleName || `车辆${index + 1}`,
      vehicleType: vehicle.vehicleType || '',
      ratedCapacityKg: rated,
      maxCapacityKg: max,
      tripCount: Math.max(1, Number(vehicle.tripCount || 1)),
      startLongitude: optimizeOptions.startLongitude,
      startLatitude: optimizeOptions.startLatitude,
      startFacilityName: optimizeOptions.startFacilityName,
      endLongitude: optimizeOptions.endLongitude,
      endLatitude: optimizeOptions.endLatitude,
      endFacilityName: optimizeOptions.endFacilityName
    }
  })
}

function resetAnchors() {
  companyAnchors.value = {
    facilityAnchors: [],
    parkingLots: [],
    transferStations: [],
    disposalSites: [],
    configuredPairs: [],
    defaultStart: null,
    defaultEnd: null
  }
  startAnchorMode.value = 'parking'
  endAnchorMode.value = 'facility'
  selectedStartAnchorKey.value = ''
  selectedEndAnchorKey.value = ''
  optimizeOptions.startLongitude = null
  optimizeOptions.startLatitude = null
  optimizeOptions.startFacilityName = null
  optimizeOptions.endLongitude = null
  optimizeOptions.endLatitude = null
  optimizeOptions.endFacilityName = null
}

function normalizeAnchors(anchors) {
  return {
    facilityAnchors: anchors?.facilityAnchors || [],
    parkingLots: anchors?.parkingLots || [],
    transferStations: anchors?.transferStations || [],
    disposalSites: anchors?.disposalSites || [],
    configuredPairs: anchors?.configuredPairs || [],
    defaultStart: anchors?.defaultStart || null,
    defaultEnd: anchors?.defaultEnd || null
  }
}

function anchorOptionsByMode(mode) {
  if (mode === 'parking') {
    return anchorOptions(companyAnchors.value.parkingLots, '停车场', 'parking')
  }
  if (mode === 'facility') {
    return anchorOptions(companyAnchors.value.facilityAnchors, '设施点', 'facility')
  }
  if (mode === 'disposalAny') {
    return anchorOptions(companyAnchors.value.disposalSites, '处置场', 'disposal')
  }
  if (mode === 'terminalAny') {
    return [
      ...anchorOptions(companyAnchors.value.disposalSites, '处置场', 'disposal'),
      ...anchorOptions(companyAnchors.value.transferStations, '中转站', 'transfer')
    ]
  }
  return []
}

function anchorOptions(items, typeName, typeKey) {
  return (items || []).map((item) => ({
    ...item,
    key: `${typeKey}:${item.facilityId}`,
    label: `${typeName} · ${item.facilityTypeName || '-'} · ${item.facilityName || item.facilityId}`
  }))
}

function findAnchor(key, mode) {
  return anchorOptionsByMode(mode).find((item) => item.key === key)
}

function modeForAnchor(anchor, fallback = 'facility') {
  if (!anchor) return fallback
  return anchor.anchorSource === 'PARKING' ? 'parking' : 'facility'
}

function keyForAnchor(anchor) {
  if (!anchor?.facilityId) return ''
  return `${modeForAnchor(anchor)}:${anchor.facilityId}`
}

function applyDefaultAnchors() {
  if (companyAnchors.value.defaultStart) {
    startAnchorMode.value = modeForAnchor(companyAnchors.value.defaultStart, 'parking')
    selectedStartAnchorKey.value = keyForAnchor(companyAnchors.value.defaultStart)
    applyAnchorToOptions(companyAnchors.value.defaultStart, 'start')
  }
  if (companyAnchors.value.defaultEnd) {
    endAnchorMode.value = modeForAnchor(companyAnchors.value.defaultEnd, 'facility')
    selectedEndAnchorKey.value = keyForAnchor(companyAnchors.value.defaultEnd)
    applyAnchorToOptions(companyAnchors.value.defaultEnd, 'end')
  }
}

function onAnchorModeChange(prefix) {
  if (prefix === 'start') {
    selectedStartAnchorKey.value = ''
    applyAnchorToOptions(null, 'start')
    return
  }
  selectedEndAnchorKey.value = ''
  selectedEndCandidateKeys.value = []
  endCandidateDropdownOpen.value = false
  if (isEndCandidateMode.value) {
    selectedEndCandidateKeys.value = endAnchorOptions.value.map((anchor) => anchor.key)
    applySelectedEndCandidates()
    return
  }
  applyAnchorToOptions(null, 'end')
}

function applySelectedStartAnchor() {
  applyAnchorToOptions(findAnchor(selectedStartAnchorKey.value, startAnchorMode.value), 'start')
}

function applySelectedEndAnchor() {
  applyAnchorToOptions(findAnchor(selectedEndAnchorKey.value, endAnchorMode.value), 'end')
}

function isEndCandidateSelected(key) {
  return selectedEndCandidateKeys.value.includes(key)
}

function toggleEndCandidate(key) {
  const selected = new Set(selectedEndCandidateKeys.value)
  if (selected.has(key)) {
    selected.delete(key)
  } else {
    selected.add(key)
  }
  selectedEndCandidateKeys.value = Array.from(selected)
  applySelectedEndCandidates()
}

function applySelectedEndCandidates() {
  const first = selectedEndCandidateAnchors()[0]
  applyAnchorToOptions(first || null, 'end')
}

function selectedEndCandidateAnchors() {
  const selected = new Set(selectedEndCandidateKeys.value)
  return endAnchorOptions.value.filter((anchor) => selected.has(anchor.key))
}

function applyAnchorToOptions(anchor, prefix) {
  const longitudeKey = `${prefix}Longitude`
  const latitudeKey = `${prefix}Latitude`
  const nameKey = `${prefix}FacilityName`
  if (!anchor) {
    optimizeOptions[longitudeKey] = null
    optimizeOptions[latitudeKey] = null
    optimizeOptions[nameKey] = null
    return
  }
  optimizeOptions[longitudeKey] = Number(anchor.longitude)
  optimizeOptions[latitudeKey] = Number(anchor.latitude)
  optimizeOptions[nameKey] = anchor.facilityName || anchor.facilityId
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

function changeSingleRouteStrategy(value) {
  optimizeOptions.useRoadPath = value === 'ROAD_GLOBAL'
}

async function setSingleRouteDisplay(useRoad) {
  if (optimizeOptions.displayRoadPath === useRoad) return
  optimizeOptions.displayRoadPath = useRoad
  if (optimization.value) {
    await previewOptimize()
  }
}

async function generateSplitRoutes() {
  if (!selectedSplitRoute.value || !selectedSplitCompany.value) return
  activeOptimizationGroupId.value = ''
  startRouteProgress()
  routeAbortController.value = new AbortController()
  await withLoading(async () => {
    try {
      const task = await api('/api/optimize/multi-preview/tasks', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        signal: routeAbortController.value.signal,
        body: JSON.stringify({
          routeId: selectedSplitRoute.value.id,
          unitId: selectedSplitCompany.value.id,
          dispatchMode: dispatchEnabled.value ? dispatchMode.value : 'USER_ORDER',
          vehicles: normalizedDispatchVehicles(),
          endSelectionMode: endAnchorMode.value,
          endCandidates: normalizedEndCandidates(),
          ...optimizeOptions,
          displayRoadPath: false,
          useRoadPath: optimizeOptions.multiRouteStrategy === 'ROAD_GLOBAL'
        })
      })
      applyRouteTask(task)
      startRouteTaskPolling(task.taskId)
    } catch (err) {
      if (err?.name === 'AbortError') {
        cancelRouteProgress()
      } else {
        failRouteProgress(err)
        throw err
      }
    } finally {
      routeAbortController.value = null
    }
  })
}

async function generateCompanyRoutes() {
  if (!selectedMultiCompany.value) return
  startRouteProgress()
  routeAbortController.value = new AbortController()
  await withLoading(async () => {
    try {
      const task = await api('/api/optimize/multi-preview/tasks', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        signal: routeAbortController.value.signal,
        body: JSON.stringify({
          unitId: selectedMultiCompany.value.id,
          facilityIds: currentOptimizationFacilityIds.value,
          dispatchMode: dispatchEnabled.value ? dispatchMode.value : 'USER_ORDER',
          vehicles: normalizedDispatchVehicles(),
          endSelectionMode: endAnchorMode.value,
          endCandidates: normalizedEndCandidates(),
          ...optimizeOptions,
          displayRoadPath: false,
          useRoadPath: optimizeOptions.multiRouteStrategy === 'ROAD_GLOBAL'
        })
      })
      applyRouteTask(task)
      startRouteTaskPolling(task.taskId)
    } catch (err) {
      if (err?.name === 'AbortError') {
        cancelRouteProgress()
      } else {
        failRouteProgress(err)
        throw err
      }
    } finally {
      routeAbortController.value = null
    }
  })
}

async function stopRouteGeneration() {
  if (routeAbortController.value) {
    routeAbortController.value.abort()
  }
  if (routeProgress.taskId && !routeProgress.done && !routeProgress.failed) {
    try {
      const task = await api('/api/optimize/multi-preview/tasks/' + routeProgress.taskId + '/cancel', { method: 'POST' })
      applyRouteTask(task)
      return
    } catch (err) {
      // 前端仍然进入停止状态，后端取消失败会显示在通用错误提示中。
      error.value = err.message || String(err)
    }
  }
  cancelRouteProgress()
}

function buildRouteProgressSteps() {
  const steps = [
    {
      key: 'prepare',
      title: '整理本批点位',
      detail: currentOptimizationPointCount.value + ' 个候选点，' + (dispatchEnabled.value ? dispatchTripCount.value + ' 趟排班' : '使用默认最大趟数')
    }
  ]
  if (optimizeOptions.multiRouteStrategy === 'ROAD_GLOBAL') {
    steps.push(
      { key: 'cache-check', title: '调取 OD 缓存', detail: '批量读取本批点位已有道路点对' },
      { key: 'pair-resolve', title: '处理缺失点对', detail: '缓存未命中时按配置百度补算或直线回退' }
    )
  } else {
    steps.push({ key: 'direct-distance', title: '计算直线距离', detail: '使用点位经纬度距离进行快速规划' })
  }
  steps.push(
    { key: 'route-build', title: '生成多趟路线', detail: '按装载目标逐趟插入点位' },
  )
  if (optimizeOptions.multiRouteStrategy === 'DIRECT_GROUP_ROAD_REFINE') {
    steps.push({ key: 'route-refine', title: '单趟道路精排', detail: '每趟路线内部按道路距离重新排序' })
  }
  steps.push(
    { key: 'segment-build', title: '整理路段与地图数据', detail: '生成路线明细、路段距离和展示路径' },
    { key: 'result', title: '输出规划结果', detail: '统计已分配、未分配和装载率' }
  )
  return steps
}

function startRouteProgress() {
  stopRouteProgressTimer()
  routeProgress.visible = true
  routeProgress.activeIndex = 0
  routeProgress.done = false
  routeProgress.failed = false
  routeProgress.message = '正在发起路线规划请求'
  routeProgress.steps = buildRouteProgressSteps()
  routeProgress.taskId = ''
  activeOptimizationGroupId.value = currentClusterRouteKey.value
  routeProgress.status = 'STARTING'
  routeProgress.phase = 'PREPARE'
  routeProgress.currentRouteNo = 0
  routeProgress.totalRoutes = 0
  routeProgress.completedRoutes = 0
  routeProgress.currentRoutePoints = 0
  routeProgress.assignedPoints = 0
  routeProgress.remainingPoints = 0
  routeProgress.currentVehicleName = ''
  routeProgress.currentTripNo = 0
}

function startRouteTaskPolling(taskId) {
  stopRouteProgressTimer()
  routeProgressTimer.value = window.setInterval(async () => {
    try {
      const task = await api('/api/optimize/multi-preview/tasks/' + taskId)
      applyRouteTask(task)
      if (['DONE', 'FAILED', 'CANCELLED', 'NOT_FOUND'].includes(task.status)) {
        stopRouteProgressTimer()
      }
    } catch (err) {
      stopRouteProgressTimer()
      failRouteProgress(err)
    }
  }, 1000)
}

function applyRouteTask(task) {
  routeProgress.visible = true
  routeProgress.taskId = task.taskId || routeProgress.taskId
  routeProgress.status = task.status || ''
  routeProgress.phase = task.phase || ''
  routeProgress.message = task.message || '正在规划路线'
  routeProgress.currentRouteNo = Number(task.currentRouteNo || 0)
  routeProgress.totalRoutes = Number(task.totalRoutes || 0)
  routeProgress.completedRoutes = Number(task.completedRoutes || 0)
  routeProgress.currentRoutePoints = Number(task.currentRoutePoints || 0)
  routeProgress.assignedPoints = Number(task.assignedPoints || 0)
  routeProgress.remainingPoints = Number(task.remainingPoints || 0)
  routeProgress.currentVehicleName = task.currentVehicleName || ''
  routeProgress.currentTripNo = Number(task.currentTripNo || 0)
  routeProgress.activeIndex = routePhaseIndex(task.phase)
  if (task.status === 'DONE') {
    if (task.result) {
      multiOptimization.value = task.result
      const groupKey = activeOptimizationGroupId.value || selectedClusterGroupId.value || '__all__'
      groupOptimizationResults.value = { ...groupOptimizationResults.value, [groupKey]: task.result }
      selectedMultiRouteNo.value = multiOptimization.value?.routes?.[0]?.routeNo || null
    }
    finishRouteProgress()
  } else if (task.status === 'FAILED' || task.status === 'NOT_FOUND') {
    routeProgress.failed = true
    routeProgress.done = false
  } else if (task.status === 'CANCELLED') {
    routeProgress.failed = true
    routeProgress.done = false
  }
}

function routePhaseIndex(phase) {
  const keyMap = {
    PREPARE: 'prepare',
    OD_PRELOAD: 'cache-check',
    PAIR_RESOLVE: 'pair-resolve',
    DIRECT_DISTANCE: 'direct-distance',
    ROUTE_BUILD: 'route-build',
    ROUTE_REFINE: 'route-refine',
    SEGMENT_BUILD: 'segment-build',
    ROUTE_DONE: 'route-build',
    DONE: 'result',
    FAILED: 'result',
    CANCELLED: 'result'
  }
  const key = keyMap[phase] || 'prepare'
  const index = routeProgress.steps.findIndex((step) => step.key === key)
  return index >= 0 ? index : 0
}

function finishRouteProgress() {
  stopRouteProgressTimer()
  routeProgress.visible = true
  routeProgress.activeIndex = routeProgress.steps.length - 1
  routeProgress.done = true
  routeProgress.failed = false
  routeProgress.message = '路线规划完成'
}

function failRouteProgress(err) {
  stopRouteProgressTimer()
  routeProgress.visible = true
  routeProgress.failed = true
  routeProgress.done = false
  routeProgress.message = (err && err.message) ? err.message : '路线规划失败'
}

function cancelRouteProgress() {
  stopRouteProgressTimer()
  routeProgress.visible = true
  routeProgress.failed = true
  routeProgress.done = false
  routeProgress.message = '路线规划已停止'
}

async function setSelectedMultiRouteDisplay(useRoad) {
  const route = selectedMultiRoute.value
  if (!route) return
  if (!useRoad) {
    multiRouteDisplayModes.value = { ...multiRouteDisplayModes.value, [route.routeNo]: 'DIRECT' }
    return
  }
  multiRouteDisplayModes.value = { ...multiRouteDisplayModes.value, [route.routeNo]: 'ROAD' }
  if (route.roadSegments?.length) return
  routeSegmentLoading.value = true
  try {
    const result = await api('/api/optimize/route-segments', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        points: route.points || [],
        displayRoadPath: true
      })
    })
    route.roadSegments = result.segments || []
    route.roadDistance = result.distance
    route.roadDurationMinutes = result.durationMinutes
  } catch (err) {
    error.value = err.message || String(err)
    multiRouteDisplayModes.value = { ...multiRouteDisplayModes.value, [route.routeNo]: 'DIRECT' }
  } finally {
    routeSegmentLoading.value = false
  }
}

function resetRouteProgress() {
  stopRouteProgressTimer()
  routeProgress.visible = false
  routeProgress.activeIndex = -1
  routeProgress.done = false
  routeProgress.failed = false
  routeProgress.message = ''
  routeProgress.steps = []
  routeProgress.taskId = ''
  activeOptimizationGroupId.value = currentClusterRouteKey.value
  routeProgress.status = ''
  routeProgress.phase = ''
  routeProgress.currentRouteNo = 0
  routeProgress.totalRoutes = 0
  routeProgress.completedRoutes = 0
  routeProgress.currentRoutePoints = 0
  routeProgress.assignedPoints = 0
  routeProgress.remainingPoints = 0
  routeProgress.currentVehicleName = ''
  routeProgress.currentTripNo = 0
}

function stopRouteProgressTimer() {
  if (routeProgressTimer.value) {
    window.clearInterval(routeProgressTimer.value)
    routeProgressTimer.value = null
  }
}

function progressStepClass(index) {
  return {
    done: routeProgress.done || index < routeProgress.activeIndex,
    active: !routeProgress.done && !routeProgress.failed && index === routeProgress.activeIndex,
    pending: !routeProgress.done && index > routeProgress.activeIndex,
    failed: routeProgress.failed && index === routeProgress.activeIndex
  }
}

function progressStepMark(index) {
  if (routeProgress.failed && index === routeProgress.activeIndex) return '!'
  if (routeProgress.done || index < routeProgress.activeIndex) return '✓'
  return index + 1
}

async function exportCompanyRoutes() {
  if (!multiOptimization.value) return
  await withLoading(async () => {
    const response = await fetch('/api/optimize/multi-export', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        company: selectedMultiCompany.value,
        generatedAt: new Date().toISOString(),
        ...multiOptimization.value
      })
    })
    if (!response.ok) {
      throw new Error(`${response.status} ${response.statusText}`)
    }
    const groupName = selectedClusterGroup.value?.groupName ? `-${selectedClusterGroup.value.groupName}` : ''
    downloadBlob(await response.blob(), `多路线生成结果${groupName}-${toDateInput(new Date())}.xlsx`)
  })
}


function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
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
  await loadRouteMapStatus()
  if (currentView.value === 'score') {
    if (scoreCompanyIds.value.length > 0) {
      await loadCompanyScores()
    } else {
      await loadCompanies()
    }
    return
  }
  if (currentView.value === 'split') {
    if (selectedSplitCompany.value) {
      await selectSplitCompany(selectedSplitCompany.value)
    } else {
      await loadCompanies()
    }
    return
  }
  if (currentView.value === 'multi' || currentView.value === 'cluster') {
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
  clearMultiOptimization()
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

function formatDistanceAbs(value) {
  return formatDistance(Math.abs(Number(value || 0)))
}

function formatDuration(value) {
  const n = Number(value || 0)
  if (!n) return '0 min'
  if (n > 0 && n < 1) {
    return `${Math.max(1, Math.round(n * 60))} s`
  }
  if (n >= 60) {
    const hours = Math.floor(n / 60)
    const minutes = Math.round(n % 60)
    return `${hours} h ${minutes} min`
  }
  if (n < 10) {
    return `${n.toFixed(1)} min`
  }
  return `${n.toFixed(0)} min`
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


function formatNumber(value) {
  const n = Number(value || 0)
  if (!Number.isFinite(n)) return '0'
  return Number.isInteger(n) ? String(n) : n.toFixed(1)
}
function formatLoadRate(value) {
  const n = Number(value || 0)
  if (!n) return '-'
  return `${(n * 100).toFixed(1)}%`
}

function planningStrategyLabel(strategy) {
  if (strategy === 'DIRECT_GROUP_ROAD_REFINE') return '直线分组 + 道路精排'
  if (strategy === 'ROAD_GLOBAL') return '全程实际距离'
  return '直线快速分组'
}

function segmentBeforePoint(segments = [], pointOrder) {
  return (segments || []).find((segment) => Number(segment.order) === Number(pointOrder) - 1)
}

function routePointRoleLabel(point) {
  if (point?.role === "START") return "起点"
  if (point?.role === "END") return "终点"
  return "收运点"
}

function pathSourceSummary(segments = []) {
  const sources = new Set((segments || []).map((segment) => segment.pathSource || 'DIRECT'))
  if (sources.size === 0) return '-'
  if (sources.size === 1) return pathSourceLabel(Array.from(sources)[0])
  return Array.from(sources).map(pathSourceLabel).join('/')
}

function pathSourceLabel(source) {
  if (source === 'OD_CACHE' || source === 'OD_PRELOAD') return 'OD缓存'
  if (source === 'BAIDU_ONLINE') return '百度在线'
  return '直线回退'
}

function pathSourceClass(source) {
  if (source === 'OD_CACHE' || source === 'OD_PRELOAD') return 'cache'
  if (source === 'BAIDU_ONLINE') return 'online'
  return 'direct'
}

</script>
















