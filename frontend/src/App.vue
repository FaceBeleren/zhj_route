<template>
  <section v-if="!authenticated" class="login-shell">
    <form class="login-card" @submit.prevent="handleLogin">
      <div>
        <h1>路线分析</h1>
        <p>车辆作业路径规划临时访问入口</p>
      </div>
      <label>
        账号
        <input v-model.trim="loginForm.username" autocomplete="username" placeholder="请输入账号" />
      </label>
      <label>
        密码
        <input v-model="loginForm.password" autocomplete="current-password" type="password" placeholder="请输入密码" />
      </label>
      <p v-if="loginError" class="login-error">{{ loginError }}</p>
      <button type="submit">登录</button>
    </form>
  </section>

  <main v-else class="app-shell">
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
        <button class="secondary" @click="handleLogout">退出</button>
      </div>
    </header>

    <nav class="view-tabs" aria-label="功能视图">
      <button :class="{ active: currentView === 'score' }" @click="currentView = 'score'">
        路线评分
      </button>
      <button :class="{ active: currentView === 'flow-analysis' }" @click="currentView = 'flow-analysis'">
        流水分析
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
      <button :class="{ active: currentView === 'saved' }" @click="currentView = 'saved'">
        路线方案库
      </button>
      <button :class="{ active: currentView === 'od-cache' }" @click="currentView = 'od-cache'">
        OD缓存计算
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



    <template v-if="currentView === 'flow-analysis'">
      <section class="flow-analysis-shell">
        <section class="panel flow-analysis-filter-panel">
          <div class="panel-head">
            <div><h2>车辆流水分析</h2><span class="muted">从实际流水还原趟次，归纳历史岗位分堆</span></div>
            <div class="panel-actions"><button class="secondary" @click="loadFlowVehicles" :disabled="flowAnalysisLoading">刷新车辆</button><button @click="startFlowAnalysis" :disabled="flowAnalysisRunning || !flowAnalysisCompanyId || !flowAnalysisCarCode">开始分析</button><button class="danger" v-if="flowAnalysisRunning" @click="cancelFlowAnalysis">停止</button></div>
          </div>
          <div class="flow-analysis-filters">
            <label class="flow-company-picker">项目公司<input v-model="flowAnalysisCompanyKeyword" placeholder="输入公司名称或编号模糊查找" /><span v-if="flowAnalysisCompany" class="flow-selected-company">已选：{{ flowAnalysisCompany.depName || flowAnalysisCompany.id }}</span><span v-if="flowAnalysisCompanyKeyword && !flowAnalysisCompany" class="flow-company-results"><button v-for="company in filteredFlowAnalysisCompanies" :key="'flow-company-'+company.id" type="button" @click="selectFlowAnalysisCompany(company)">{{ company.depName || company.id }}</button><em v-if="!filteredFlowAnalysisCompanies.length">没有匹配公司</em></span></label>
            <label>车辆<select v-model="flowAnalysisCarCode" @change="resetFlowAnalysisResult"><option value="">请选择车辆</option><option v-for="vehicle in flowAnalysisVehicles" :key="'flow-car-'+vehicle.carCode" :value="vehicle.carCode">{{ vehicle.carCode }} · {{ vehicle.recordCount }} 条流水</option></select></label>
            <label>开始日期<input v-model="flowAnalysisFilters.startDate" type="date" /></label>
            <label>结束日期<input v-model="flowAnalysisFilters.endDate" type="date" /></label>
            <label>岗位<select v-model="flowAnalysisRouteId"><option value="">自动按岗位分别分析</option><option v-for="job in flowAnalysisJobs" :key="'flow-job-'+job.routeId" :value="String(job.routeId)">{{ job.routeName || '未绑定岗位' }} · {{ job.recordCount }} 条</option></select></label>
            <label>相似度阈值<input v-model.number="flowAnalysisThreshold" type="number" min="0.3" max="0.9" step="0.05" /></label>
          </div>
          <p class="muted">单日用于查看真实趟次；多日用于归纳稳定分堆。原始流水只读，人工修正只影响本次分析。</p>
        </section>

        <section class="panel flow-progress-panel">
          <div class="planning-progress-head"><div><h2>流水分析进度</h2><p>{{ flowAnalysisTask.message }}</p></div><strong>{{ flowAnalysisTask.percent }}%</strong></div>
          <div class="planning-progress-bar"><span :style="{ width: flowAnalysisTask.percent + '%' }"></span></div>
          <div class="od-cache-stats"><span>流水 {{ flowAnalysisTask.totalRecords || 0 }}</span><span>事件 {{ flowAnalysisTask.loadedEvents || 0 }}</span><span>状态 {{ flowAnalysisTask.status }}</span><span v-if="flowAnalysisTask.elapsedMs">耗时 {{ formatOdCacheElapsed(flowAnalysisTask.elapsedMs) }}</span></div>
        </section>

        <template v-if="flowAnalysisResult">
          <section class="flow-summary-grid">
            <div class="summary-strip"><div><span>有效作业日</span><strong>{{ flowAnalysisResult.activeDays }}</strong></div><div><span>识别趟次</span><strong>{{ flowAnalysisResult.tripCount }}</strong></div><div><span>完整趟次</span><strong>{{ flowAnalysisResult.completeTripCount }}</strong></div><div><span>日均趟次</span><strong>{{ flowAnalysisResult.avgTripsPerActiveDay }}</strong></div><div><span>异常趟次</span><strong>{{ flowAnalysisResult.anomalyTripCount }}</strong></div></div>
            <div v-if="flowAnalysisResult.tripCountMismatchRecords" class="flow-warning">有 {{ flowAnalysisResult.tripCountMismatchRecords }} 条流水记录的预计趟数与实际切分趟数不一致，请结合时间轴核对场站事件。</div>
                      </section>
          <section class="flow-analysis-results">
            <section class="panel flow-daily-panel">
              <div class="panel-head"><div><h2>实际趟次</h2><span class="muted">按场站事件切分，展开查看点位顺序；同一天的次数按日期汇总</span></div><label class="flow-filter-toggle" :class="{ active: flowShowCollectedOnly }"><input type="checkbox" v-model="flowShowCollectedOnly" /><span class="flow-filter-check" aria-hidden="true">✓</span><span class="flow-filter-copy"><strong>只展示收运点位</strong><small>隐藏纯途经点</small></span></label></div>
              <div v-for="day in flowAnalysisResult.dailyTrips" :key="'flow-day-'+day.date" class="flow-day"><div class="flow-day-head"><h3>{{ day.date }} <small>{{ day.tripCount }} 趟</small></h3><button type="button" class="flow-day-toggle" :class="{ expanded: flowExpandedDays.has(day.date) }" :aria-expanded="flowExpandedDays.has(day.date)" @click="toggleFlowDay(day.date)"><span aria-hidden="true">⌄</span>{{ flowExpandedDays.has(day.date) ? '收起趟次' : '展开趟次' }}</button></div><div v-if="day.pointStats?.length" class="flow-day-point-stats"><template v-for="point in day.pointStats" :key="'flow-day-point-'+day.date+'-'+point.facilityId"><span v-if="!flowShowCollectedOnly || Number(point.collectedCount || 0) > 0" :class="flowDailyPointClass(point)">{{ point.facilityName || point.facilityId }} · {{ point.finalMatchLabel }} · 收运 {{ point.collectedCount || 0 }}次 · 途经 {{ point.throughCount || 0 }}次</span></template></div><div v-if="flowExpandedDays.has(day.date)" class="flow-day-trips"><div v-for="(trip, index) in day.trips" :key="'flow-trip-'+trip.recordId+'-'+index" class="flow-trip"><div><strong>{{ trip.date || day.date || '日期未知' }} · 第 {{ index + 1 }} 趟</strong><span>{{ trip.complete ? '已到场' : '未记录终点' }} · {{ trip.pointCount }} 个收集点 · 已收 {{ trip.collectedPointCount || 0 }} · 途经 {{ trip.throughPointCount || 0 }}<button v-if="!trip.complete && trip.points?.length" class="ghost-button" @click="markFlowBoundary(trip)">将末点作为人工边界</button></span></div><div class="flow-sequence"><span v-if="trip.start">{{ trip.start.facilityName }}</span><template v-for="point in trip.points" :key="'flow-point-'+trip.recordId+'-'+point.eventId"><span v-if="!flowShowCollectedOnly || Number(point.matchType) === 0" :class="flowTripPointClass(point, day)" :title="flowPointDisplayLabel(point, day)">{{ point.facilityName || point.facilityId }} <small>{{ flowPointDisplayLabel(point, day) }}{{ flowPointOccurrenceLabel(point) }}</small></span></template><b v-if="trip.end">→ {{ trip.end.facilityName }}</b></div></div></div></div>
              <div v-if="!flowAnalysisResult.dailyTrips?.length" class="empty">没有找到可分析的流水</div>
            </section>
            <section class="panel flow-groups-panel">
              <div class="panel-head"><div><h2>历史岗位分堆</h2><span class="muted">稳定分堆可保存为岗位草案</span></div><button @click="openSaveFlowAnalysis" :disabled="!flowAnalysisResult.groups?.length">保存分堆草案</button></div>
              <div v-for="group in flowAnalysisResult.groups" :key="'flow-group-'+group.groupNo" class="flow-group"><div class="flow-group-head"><div><h3>{{ group.groupName }} <small :class="group.stable ? 'flow-stable' : 'flow-unstable'">{{ group.stable ? '稳定' : '样本不足' }}</small></h3><span>{{ group.tripCount }} 趟 · 覆盖 {{ group.activeDays }} 天 · 每周约 {{ group.visitsPerWeek }} 趟 · 稳定度 {{ group.stability }}</span></div><button class="secondary" @click="selectFlowGroup(group)">查看地图</button></div><p class="muted">典型终点：{{ group.typicalEnd?.facilityName || '未确定' }} · 覆盖完整趟 {{ group.supportOfAllCompleteTrips }}</p><div class="flow-point-chips"><span v-for="point in group.points" :key="'flow-group-point-'+group.groupNo+'-'+point.facilityId" :class="[flowPointClass(point), { optional: Number(point.support) < 0.6 }]">{{ point.facilityName || point.facilityId }} · {{ point.finalMatchLabel || point.matchLabel || '未知' }} · {{ Math.round(Number(point.support) * 100) }}%</span></div></div><div v-if="!flowAnalysisResult.groups?.length" class="empty">暂未形成分堆</div>
              <div v-if="flowAnalysisResult.unassignedPoints?.length" class="flow-warning">有 {{ flowAnalysisResult.unassignedPoints.length }} 个低频点位未作为核心点，请结合实际情况确认。</div>
              <div v-if="flowForcedBoundaryIds.size" class="flow-adjustments"><span>已标记 {{ flowForcedBoundaryIds.size }} 个人工边界</span><button class="secondary" @click="rerunFlowAnalysisWithAdjustments">按修正重新分析</button></div>
            </section>
          </section>
          <RouteMapPanel v-if="flowSelectedGroup" :original-points="[]" :optimized-points="flowMapPoints(flowSelectedGroup)" :show-original="false" optimized-label="历史分堆" />
        </template>
      </section>
    </template>

    <template v-else-if="currentView === 'saved'">
      <section class="saved-plan-shell">
        <aside class="panel saved-plan-list-panel">
          <div class="panel-head">
            <div>
              <h2>路线方案库</h2>
              <span class="muted">保存后的路线优化、拆分和多路线方案</span>
            </div>
            <div class="panel-actions saved-plan-actions">
              <button class="action-pill secondary" @click="openCreateFolderDialog" :disabled="loading">新建分组</button>
              <button class="action-pill primary" @click="openRouteImportDialog" :disabled="loading">导入路线</button>
              <button class="action-pill secondary" @click="loadSavedGroups" :disabled="loading">刷新列表</button>
            </div>
          </div>
          <div class="saved-library-tree">
            <button class="tree-root-node" :class="{ active: selectedSavedFolderId === 'all' }" @click="selectSavedFolder('all')">
              <span>{{ selectedSavedFolderId === 'all' ? '▾' : '▸' }}</span><strong>全部分组</strong>
            </button>
            <div class="tree-children">
              <div v-for="folder in savedLibraryTreeFolders" :key="'saved-folder-' + folder.id" class="tree-branch">
                <button class="tree-node folder-node" :class="{ active: String(selectedSavedFolderId) === String(folder.id) }" @click="toggleSavedLibraryFolder(folder.id)">
                  <span>{{ isSavedLibraryFolderExpanded(folder.id) ? '▾' : '▸' }}</span><span class="folder-icon">▰</span>{{ folder.folderName }}
                </button>
                <div v-if="isSavedLibraryFolderExpanded(folder.id)" class="tree-children">
                  <div v-for="group in savedLibraryTreeGroups(folder.id)" :key="'saved-group-' + group.id" class="tree-branch">
                    <button class="tree-node plan-node" :class="{ active: selectedSavedGroup?.id === group.id }" @click="toggleSavedLibraryGroup(group)">
                      <span>{{ isSavedLibraryGroupExpanded(group.id) ? '▾' : '▸' }}</span><span>▤</span>{{ group.groupName }}<small>{{ group.routeCount || 0 }} 趟</small>
                    </button>
                    <div v-if="isSavedLibraryGroupExpanded(group.id)" class="tree-children">
                      <button v-for="route in (savedGroupDetails[group.id]?.routes || [])" :key="'saved-route-' + route.id" class="tree-node route-node" :class="{ active: selectedSavedRouteId === route.id && selectedSavedGroup?.id === group.id }" @click="selectSavedLibraryRoute(group, route)">
                        <span>↳</span>{{ route.routeName || ('第 ' + route.routeNo + ' 趟') }}
                      </button>
                    </div>
                  </div>
                  <div v-if="savedLibraryTreeGroups(folder.id).length === 0" class="tree-empty">暂无方案</div>
                </div>
              </div>
              <div class="tree-branch">
                <button class="tree-node folder-node" :class="{ active: selectedSavedFolderId === 'ungrouped' }" @click="toggleSavedLibraryFolder('ungrouped')">
                  <span>{{ isSavedLibraryFolderExpanded('ungrouped') ? '▾' : '▸' }}</span><span>▰</span>未分组
                </button>
                <div v-if="isSavedLibraryFolderExpanded('ungrouped')" class="tree-children">
                  <div v-for="group in savedLibraryTreeGroups('ungrouped')" :key="'saved-ungrouped-' + group.id" class="tree-branch">
                    <button class="tree-node plan-node" :class="{ active: selectedSavedGroup?.id === group.id }" @click="toggleSavedLibraryGroup(group)">
                      <span>{{ isSavedLibraryGroupExpanded(group.id) ? '▾' : '▸' }}</span><span>▤</span>{{ group.groupName }}<small>{{ group.routeCount || 0 }} 趟</small>
                    </button>
                    <div v-if="isSavedLibraryGroupExpanded(group.id)" class="tree-children">
                      <button v-for="route in (savedGroupDetails[group.id]?.routes || [])" :key="'saved-ungrouped-route-' + route.id" class="tree-node route-node" :class="{ active: selectedSavedRouteId === route.id && selectedSavedGroup?.id === group.id }" @click="selectSavedLibraryRoute(group, route)">
                        <span>↳</span>{{ route.routeName || ('第 ' + route.routeNo + ' 趟') }}
                      </button>
                    </div>
                  </div>
                  <div v-if="savedLibraryTreeGroups('ungrouped').length === 0" class="tree-empty">暂无方案</div>
                </div>
              </div>
            </div>
          </div>
          <div v-if="selectedSavedPlanIds.length" class="saved-batch-toolbar">
            <span>已选 {{ selectedSavedPlanIds.length }} 个方案</span>
            <button class="action-pill primary" @click="openAssignFolderDialog">归入分组</button>
            <button class="ghost-button" @click="selectedSavedPlanIds = []">取消选择</button>
          </div>
          <div class="saved-filter-grid">
            <input v-model="savedFilters.keyword" placeholder="搜索方案、公司、来源路线" @keyup.enter="loadSavedGroups" />
            <select v-model="savedFilters.sourceType" @change="loadSavedGroups">
              <option value="">全部来源</option>
              <option value="SINGLE_OPTIMIZE">单路线优化</option>
              <option value="SPLIT">路线拆分</option>
              <option value="MULTI">多路线生成</option>
              <option value="IMPORT">导入路线</option>
              <option value="FLOW_ANALYSIS">流水归纳草案</option>
            </select>
            <select v-model="savedFilters.unitId" @change="loadSavedGroups">
              <option value="">全部公司</option>
              <option v-for="company in companies" :key="company.id" :value="company.id">{{ company.depName || company.id }}</option>
            </select>
          </div>
          <div class="saved-plan-list saved-plan-search-results">
            <div v-for="group in filteredSavedGroups" :key="group.id" class="saved-plan-item" :class="{ active: selectedSavedGroup?.id === group.id }" @click="selectSavedGroup(group)">
              <label class="saved-plan-check" @click.stop><input type="checkbox" :value="group.id" v-model="selectedSavedPlanIds" /></label>
              <strong>{{ group.groupName }}</strong>
              <span>{{ sourceTypeLabel(group.sourceType) }} · {{ group.routeCount || 0 }} 条 · {{ group.unitName || '未关联公司' }}</span>
              <small>{{ formatDate(group.createTime) }}</small>
            </div>
            <div v-if="filteredSavedGroups.length === 0" class="empty">暂无符合条件的保存方案。</div>
          </div>
        </aside>

        <section class="panel saved-plan-detail-panel">
          <template v-if="importedRoutePreview">
            <div class="panel-head">
              <div>
                <h2>{{ importedRoutePreview.routeName }}</h2>
                <span class="muted">导入路线 · {{ importedRoutePreview.unitName || '未选择公司' }} · sheet1：{{ importedRoutePreview.sheetName }}</span>
              </div>
              <div class="panel-actions">
                <button @click="saveImportedRoute" :disabled="loading || !importedRoutePreview.points?.length">保存路线</button>
                <button class="secondary" @click="clearImportedRoutePreview">关闭预览</button>
              </div>
            </div>
            <div class="optimization-metrics saved-plan-metrics">
              <span>导入行 {{ importedRoutePreview.totalCount || 0 }}</span>
              <span>匹配 {{ importedRoutePreview.matchedCount || 0 }}</span>
              <span>未匹配 {{ importedRoutePreview.unmatchedCount || 0 }}</span>
              <span>{{ importedRouteDisplaySummary }}</span>
            </div>
            <p v-if="importedRoutePreview.unmatchedCount" class="route-import-warning">
              有 {{ importedRoutePreview.unmatchedCount }} 行未匹配到坐标，已在地图连线中跳过；明细中红色行表示未匹配原始行。
            </p>
            <div class="route-display-toolbar saved-display-toolbar">
              <span>地图展示</span>
              <button :class="{ active: importedRouteDisplayMode === 'DIRECT' }" @click.stop="setImportedRouteDisplay(false)">点位直线</button>
              <button :class="{ active: importedRouteDisplayMode === 'ROAD' }" :disabled="routeSegmentLoading" @click.stop="setImportedRouteDisplay(true)">
                {{ routeSegmentLoading ? '加载道路...' : '道路折线' }}
              </button>
              <small>{{ importedRouteDisplaySummary }}</small>
            </div>
            <RouteMapPanel
              :original-points="[]"
              :optimized-points="importedRoutePreview.points || []"
              :optimized-segments="importedRouteDisplaySegments"
              :show-original="false"
              optimized-label="导入路线"
            />
            <div class="route-time-table saved-point-table imported-route-table">
              <div class="route-time-row head">
                <span>原始行</span><span>Excel名称</span><span>匹配结果</span><span>类型</span><span>状态</span><span>地图顺序</span>
              </div>
              <div v-for="row in importedRoutePreview.rows || []" :key="`import-row-${row.order}-${row.inputName}`" class="route-time-row" :class="{ unmatched: !row.matched }">
                <span>{{ row.order }}</span>
                <span>{{ row.inputName }}</span>
                <span>{{ row.point?.facilityName || '-' }}</span>
                <span>{{ row.point?.facilityTypeName || '-' }}</span>
                <span>{{ row.matched ? '已匹配' : row.message }}</span>
                <span>{{ row.matched ? row.mapOrder || '-' : '跳过' }}</span>
              </div>
            </div>
          </template>
          <template v-else-if="selectedSavedGroup">
            <div class="panel-head">
              <div>
                <h2>{{ selectedSavedGroup.groupName }} <span class="version-badge">V{{ selectedSavedGroup.versionNo || 1 }}</span></h2>
                <span class="muted">{{ sourceTypeLabel(selectedSavedGroup.sourceType) }} · {{ selectedSavedGroup.unitName || '未关联公司' }} · {{ formatDate(selectedSavedGroup.createTime) }}</span>
              </div>
              <div class="panel-actions">
                <button class="secondary" @click="deleteSavedGroup(selectedSavedGroup)" :disabled="loading">删除方案</button>
              </div>
            </div>
            <div class="version-toolbar">
              <span>版本记录</span>
              <select :value="selectedSavedGroup.id" @change="selectSavedVersion($event.target.value)">
                <option v-for="version in savedVersionRecords" :key="version.id" :value="version.groupId">V{{ version.versionNo }} · {{ version.operationType || '版本' }}</option>
              </select>
              <button class="secondary" @click="restoreSelectedVersion" :disabled="loading || !selectedSavedVersionRecord">恢复为新版本</button>
            </div>
            <div class="optimization-metrics saved-plan-metrics">
              <span>方案版本 V{{ selectedSavedGroup.versionNo || 1 }}</span>
              <span>路线 {{ selectedSavedGroup.routes?.length || 0 }}</span>
              <span>来源 {{ sourceTypeLabel(selectedSavedGroup.sourceType) }}</span>
              <span>策略 {{ planningStrategyLabel(selectedSavedGroup.planningStrategy) }}</span>
              <span>原路线 {{ selectedSavedGroup.originRouteName || '-' }}</span>
            </div>
            <div class="multi-routes saved-route-list">
              <article
                v-for="route in selectedSavedGroup.routes || []"
                :key="route.id"
                class="multi-route-card"
                :class="{ active: selectedSavedRoute?.id === route.id }"
                @click="selectedSavedRouteId = route.id"
              >
                <div>
                  <strong>{{ route.routeName || ('第 ' + route.routeNo + ' 趟') }}</strong>
                  <small>
                    V{{ route.routeVersionNo || selectedSavedGroup.versionNo || 1 }} · {{ route.vehicleName || '未填车辆' }} · {{ route.pointCount || route.points?.length || 0 }} 点 ·
                    {{ formatWeight(route.estimatedWeightKg) }} · {{ formatDistance(savedRouteDisplayDistance(route)) }} ·
                    合计 {{ formatDuration(savedRouteDisplayTotalDuration(route)) }} · {{ savedRouteDisplaySummary(route) }}
                  </small>
                </div>
                <div class="panel-actions">
                  <button class="secondary" @click.stop="openSavedRouteOptimize(route)">优化该趟</button>
                  <button class="secondary" @click.stop="openSavedRouteEdit(route)">编辑点位</button>
                  <button class="secondary" @click.stop="openSavedRouteSplit(route)">拆分该趟</button>
                  <button class="ghost-button" @click.stop="deleteSavedRoute(route)">删除</button>
                </div>
              </article>
            </div>
            <div v-if="selectedSavedRoute" class="route-display-toolbar saved-display-toolbar">
              <span>地图展示</span>
              <button :class="{ active: !selectedSavedRouteRoadDisplay }" @click.stop="setSavedRouteDisplay(false)">点位直线</button>
              <button :class="{ active: selectedSavedRouteRoadDisplay }" :disabled="routeSegmentLoading" @click.stop="setSavedRouteDisplay(true)">
                {{ routeSegmentLoading ? '加载道路...' : '道路折线' }}
              </button>
              <small>{{ selectedSavedRouteDisplaySummary }}</small>
            </div>
            <RouteMapPanel
              v-if="selectedSavedRoute"
              :original-points="[]"
              :optimized-points="selectedSavedRoute.points || []"
              :optimized-segments="selectedSavedRouteDisplaySegments"
              :optimized-facility-ids="(selectedSavedRoute.points || []).filter((point) => point.pointSource === 'OPTIMIZED').map((point) => point.facilityId)"
              :show-original="false"
              optimized-label="保存路线"
            />
            <div v-if="selectedSavedRoute" class="route-time-table saved-point-table">
              <div class="route-time-row head">
                <span>顺序</span><span>点位</span><span>角色</span><span>桶信息</span><span>重量</span><span>作业</span><span v-if="savedRouteEditMode">调整</span>
              </div>
              <div v-for="(point, index) in (savedRouteEditMode ? savedRouteEditPoints : (selectedSavedRoute.points || []))" :key="`saved-${selectedSavedRoute.id}-${point.order}-${point.facilityId}`" class="route-time-row">
                <span>{{ index + 1 }}</span>
                <span>{{ point.facilityName || point.facilityId }}</span>
                <span>{{ routePointRoleLabel(point) }}</span>
                <span>{{ point.containerInfo || '-' }}</span>
                <span>{{ formatWeight(point.estimatedWeightKg) }}</span>
                <span>{{ formatDuration(point.operationDurationMinutes) }}</span>
                <span v-if="savedRouteEditMode" class="panel-actions">
                  <button class="ghost-button" @click="moveSavedEditPoint(index, -1)" :disabled="index === 0">上移</button>
                  <button class="ghost-button" @click="moveSavedEditPoint(index, 1)" :disabled="index === savedRouteEditPoints.length - 1">下移</button>
                  <button class="ghost-button" @click="removeSavedEditPoint(index)">移除</button>
                </span>
              </div>
              <div v-if="savedRouteEditMode" class="panel-actions saved-edit-actions">
                <button @click="saveSavedRouteEdit" :disabled="savedRouteEditPoints.length < 2 || loading">保存为新版本</button>
                <button class="secondary" @click="cancelSavedRouteEdit">取消编辑</button>
              </div>
            </div>
          </template>
          <div v-else class="empty large-empty">请选择左侧保存方案查看路线。</div>
        </section>
      </section>
    </template>


    <template v-else-if="currentView === 'od-cache'">
      <section class="od-cache-shell">
        <div class="panel-head">
          <div><h2>OD缓存计算</h2><span class="muted">提前补齐选中点位、停车场和设施点之间的道路距离</span></div>
          <div class="panel-actions"><button class="secondary" @click="resetOdCacheSelection">清空选择</button><button @click="startOdCacheTask" :disabled="odCacheRunning || odCachePayload.length < 2">开始计算缓存</button><button class="danger" v-if="odCacheRunning" @click="stopOdCacheTask">停止计算</button></div>
        </div>
        <section class="od-cache-layout">
          <aside class="panel company-panel">
            <div class="panel-head"><h2>项目公司</h2><input v-model="odCacheCompanyKeyword" placeholder="搜索公司" /></div>
            <div class="list"><button v-for="company in filteredOdCacheCompanies" :key="'od-company-' + company.id" class="list-item" :class="{ active: odCacheCompany?.id === company.id }" @click="selectOdCacheCompany(company)"><strong>{{ company.depName || company.id }}</strong><small>{{ company.id }}</small></button></div>
          </aside>
          <section class="panel od-cache-selection-panel">
            <div class="od-cache-section-head"><div><h3>公司点位池</h3><span>{{ odCacheSelectedPointCount }} / {{ odCachePoints.length }} 个点位</span></div><label><input type="checkbox" v-model="odCacheShowSelectedOnly" /> 只看已选</label></div>
            <div class="toolbar-row"><input v-model="odCachePointKeyword" placeholder="搜索点位、桶信息" /><button class="secondary" @click="selectAllOdCachePoints">全选</button><button class="secondary" @click="clearOdCachePoints">清空</button><input ref="odCacheImportInput" type="file" accept=".xlsx,.xls" hidden @change="importOdCacheNames" /><button class="secondary" @click="triggerOdCacheImport">导入选中</button></div>
            <p class="muted">{{ odCacheImportSummary || '点位用于生成 OD 计算节点，导入文件只按名称匹配。' }}</p>
            <div class="od-cache-point-list"><label v-for="point in odCacheVisiblePoints" :key="'od-point-' + point.facilityId" class="list-item compact"><input type="checkbox" :checked="odCachePointIds.has(String(point.facilityId))" @change="toggleOdCachePoint(point.facilityId)" /><span><strong>{{ point.facilityName || point.facilityId }}</strong><small>{{ point.containerInfo || '-' }} · {{ point.facilityTypeName || '收运点' }}</small></span></label><div v-if="!odCacheVisiblePoints.length" class="empty">请选择公司</div></div>
          </section>
          <section class="panel od-cache-anchor-panel">
            <div class="od-cache-section-head"><h3>场站与设施点</h3><span class="muted">支持多选</span></div>
            <h4>停车场</h4><div class="anchor-check-list"><label v-for="anchor in odCacheParkingOptions" :key="'od-parking-' + anchor.facilityId" class="list-item compact"><input type="checkbox" :checked="odCacheParkingKeys.has(anchor.key)" @change="toggleOdCacheAnchor(anchor.key, anchor)" /><span>{{ anchor.facilityName || anchor.facilityId }}<small>{{ anchor.facilityTypeName || '停车场' }}</small></span></label><span v-if="!odCacheParkingOptions.length" class="muted">暂无停车场</span></div>
            <h4>处置场 / 中转站</h4><div class="anchor-check-list"><label v-for="anchor in odCacheFacilityOptions" :key="'od-facility-' + anchor.key" class="list-item compact"><input type="checkbox" :checked="odCacheFacilityKeys.has(anchor.key)" @change="toggleOdCacheAnchor(anchor.key, anchor)" /><span>{{ anchor.facilityName || anchor.facilityId }}<small>{{ anchor.facilityTypeName || '设施点' }}</small></span></label><span v-if="!odCacheFacilityOptions.length" class="muted">暂无设施点</span></div>
          </section>
        </section>
        <section class="panel od-cache-progress-panel">
          <div class="planning-progress-head"><div><h2>点位计算进度</h2><p>{{ odCacheTask.message }}</p></div><div class="od-cache-progress-metrics"><strong>{{ odCacheTask.percent }}%</strong><small>已用时 {{ odCacheElapsedText }}</small></div></div>
          <div class="planning-progress-bar"><span :style="{ width: odCacheTask.percent + '%' }"></span></div>
          <div class="od-cache-stats"><span>节点 {{ odCachePayload.length }}</span><span>总点对 {{ odCacheTask.totalPairs || odCachePairCount }}</span><span>已缓存 {{ odCacheTask.cachedPairs || 0 }}</span><span>已完成 {{ odCacheTask.completedPairs || 0 }}</span><span>成功写入 {{ odCacheTask.successPairs || 0 }}</span><span>失败 {{ odCacheTask.failedPairs || 0 }}</span></div>
          <div class="planning-progress-steps"><div :class="odCacheStepClass(1)"><b>1</b><span><strong>整理选中节点</strong><small>点位、停车场、处置场和中转站去重</small></span></div><div :class="odCacheStepClass(2)"><b>2</b><span><strong>查询已缓存数据</strong><small>先读取现有 ljszy_odpair_pool</small></span></div><div :class="odCacheStepClass(3)"><b>3</b><span><strong>计算缺失道路 OD</strong><small>{{ odCacheTask.currentPair || '未开始' }}</small></span></div><div :class="odCacheStepClass(4)"><b>4</b><span><strong>完成并持久化</strong><small>成功结果由后端写入数据库</small></span></div></div>
          <p v-if="odCacheTask.failures?.length" class="route-import-warning">部分点对未成功：{{ odCacheTask.failures.slice(0, 3).join('；') }}</p>
        </section>
      </section>
    </template>
    <template v-else-if="currentView === 'workbench'">
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
                <button @click="previewOptimize(false)" :disabled="!selectedRoute || loading">优化预览</button>
                <button class="feedback-button" @click="adjustByFeedback" :disabled="!selectedRoute || !feedbackFacilityIds.size || loading">优化调整<span v-if="feedbackFacilityIds.size">（{{ feedbackFacilityIds.size }} 个反馈点）</span></button>
              </div>
            </div>
            <div v-if="selectedRoute" class="route-overview">
              <div>
                <span>{{ currentTypeName }}名称</span>
                <strong>{{ selectedRoute.routeName || selectedRoute.id }}</strong>
              </div>
              <div>
                <span>{{ currentTypeName }}ID</span>
                <strong>{{ isSavedRouteWorkbench ? '方案库路线' : selectedRoute.id }}</strong>
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
              <div class="feedback-tip">勾选不当点位后点击“优化调整”，仅调整所选点的顺序。</div>
              <ol class="point-list">
                <li v-for="(point, index) in planPoints" :key="point.facilityId">
                  <label class="feedback-point-check"><input type="checkbox" :checked="feedbackFacilityIds.has(String(point.facilityId))" :disabled="index === 0 || index === planPoints.length - 1" @change="toggleFeedbackPoint(point)" /><span>{{ point.facilityName || point.facilityId }}</span></label>
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
                <button class="secondary" @click="toggleOriginalRoutePreview" :disabled="!selectedRoute || loading">{{ showOriginalRoutePreview ? '收起原路线' : '原路线预览' }}</button>
                <button @click="openSaveSingleOptimization" :disabled="!optimization || loading">{{ isSavedRouteWorkbench ? '保存为新版本' : '保存优化路线' }}</button>
              </div>
              <div v-if="showOriginalRoutePreview" class="original-route-preview">
                <div class="original-route-preview-head">
                  <strong>优化前路线</strong>
                  <span>当前已生成路线，共 {{ planPoints.length }} 个点位</span>
                  <div class="route-display-toolbar">
                    <button :class="{ active: originalRouteDisplayMode === 'DIRECT' }" @click.stop="setOriginalRouteDisplay(false)">点位直线</button>
                    <button :class="{ active: originalRouteDisplayMode === 'ROAD' }" :disabled="routeSegmentLoading || !planPoints.length" @click.stop="setOriginalRouteDisplay(true)">
                      {{ routeSegmentLoading && originalRouteDisplayMode === 'ROAD' ? '加载道路...' : '百度道路轨迹' }}
                    </button>
                  </div>
                </div>
                <RouteMapPanel
                  :original-points="planPoints"
                  :optimized-points="[]"
                  :original-segments="originalRouteDisplayMode === 'ROAD' ? originalRouteRoadSegments : []"
                  :show-original="true"
                  original-label="原路线"
                  optimized-label="优化后"
                />
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
                  :optimized-facility-ids="optimization.feedbackMode ? optimization.feedbackFacilityIds : null"
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
            <div><h2>路线来源</h2><span class="muted">选择待拆分路线的来源</span></div>
          </div>
          <div class="split-source-switch">
            <button :class="{ active: splitSourceMode === 'COMPANY' }" @click="setSplitSourceMode('COMPANY')">项目公司</button>
            <button :class="{ active: splitSourceMode === 'LIBRARY' }" @click="setSplitSourceMode('LIBRARY')">路线方案库</button>
          </div>
          <div v-if="splitSourceMode === 'COMPANY'" class="split-company-list">
            <div class="panel-head compact"><h3>项目公司</h3><input v-model="companyKeyword" placeholder="搜索公司" /></div>
            <div class="list">
              <button v-for="company in filteredCompanies" :key="company.id" class="list-item" :class="{ active: selectedSplitCompany?.id === company.id }" @click="selectSplitCompany(company)">
                <span>{{ company.depName || company.id }}</span><small>{{ company.depCode || '-' }}</small>
              </button>
            </div>
          </div>
          <div v-else class="split-library-hint">
            <strong>路线方案库</strong><span>按“分组 → 方案 → 趟次”选择路线</span>
            <button class="secondary" @click="currentView = 'saved'">打开方案库管理</button>
          </div>
        </aside>

        <section class="panel route-panel">
          <div class="route-panel-head">
            <div class="panel-head">
              <h2>{{ splitSourceMode === 'LIBRARY' ? '方案库路线' : '待拆分路线' }}</h2>
              <span class="muted">{{ selectedSplitCompany?.depName || '请选择公司' }}</span>
            </div>
            <div v-if="splitSourceMode === 'COMPANY'" class="type-segment split-type-segment">
              <button v-for="option in dataTypeOptions" :key="option.value" :class="{ active: dataType === option.value }" :disabled="loading" @click="changeSplitDataType(option.value)">{{ option.label }}</button>
            </div>
            <p class="split-source-note">
              <template v-if="splitFromSavedRoute">来源：路线方案库，当前拆分保存方案中的这一趟路线。</template>
              <template v-else-if="splitSourceMode === 'LIBRARY'">请在下方树中展开方案并选择具体趟次。</template>
              <template v-else>来源与“路线详情”一致，当前展示 {{ currentTypeName }}。</template>
            </p>
          </div>
          <div v-if="splitSourceMode === 'LIBRARY'" class="saved-route-tree split-saved-route-tree">
            <div v-for="folder in splitSavedTreeFolders" :key="'split-folder-' + folder.id" class="tree-branch">
              <button class="tree-node folder-node" :class="{ active: isSplitSavedFolderExpanded(folder.id) }" @click="toggleSplitSavedFolder(folder.id)">
                <span>{{ isSplitSavedFolderExpanded(folder.id) ? '▾' : '▸' }}</span><span class="folder-icon">▰</span>{{ folder.folderName }}
              </button>
              <div v-if="isSplitSavedFolderExpanded(folder.id)" class="tree-children">
                <div v-for="group in splitSavedTreeGroups(folder.id)" :key="'split-group-' + group.id" class="tree-branch">
                  <button class="tree-node plan-node" :class="{ active: isSplitSavedGroupExpanded(group.id) }" @click="toggleSplitSavedGroup(group)">
                    <span>{{ isSplitSavedGroupExpanded(group.id) ? '▾' : '▸' }}</span><span>▤</span>{{ group.groupName }}<small>{{ group.routeCount || 0 }} 趟</small>
                  </button>
                  <div v-if="isSplitSavedGroupExpanded(group.id)" class="tree-children">
                    <button v-for="route in (splitSavedGroupDetails[group.id]?.routes || [])" :key="'split-route-' + route.id" class="tree-node route-node" :class="{ active: selectedSplitRoute?.id === route.id && splitFromSavedRoute }" @click="selectSplitSavedRoute(group, route)">
                      <span>↳</span>{{ route.routeName || ('第 ' + route.routeNo + ' 趟') }}
                    </button>
                  </div>
                </div>
                <div v-if="splitSavedTreeGroups(folder.id).length === 0" class="tree-empty">暂无方案</div>
              </div>
            </div>
          </div>
          <div v-else class="list route-list">
            <button v-for="route in splitRoutes" :key="route.id" class="list-item" :class="{ active: selectedSplitRoute?.id === route.id }" @click="selectSplitRoute(route)">
              <span>{{ route.routeName || route.id }}</span><small>{{ route.dataTypeName || currentTypeName }} ID {{ route.id }}</small>
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
              <label>标准工时 h<input v-model.number="optimizeOptions.workHours" type="number" min="1" max="24" step="0.5" /></label>
              <label>每桶秒<input v-model.number="optimizeOptions.secondsPerContainer" type="number" min="1" step="1" /></label>
              <label>每点分钟<input v-model.number="optimizeOptions.minutesPerPoint" type="number" min="0" step="0.5" /></label>
              <div class="route-mode-card optimizer-mode-card strategy-mode-card">
                <label class="strategy-select"><span>规划策略</span><select v-model="optimizeOptions.multiRouteStrategy"><option value="DIRECT_GROUP">直线快速分组</option><option value="DIRECT_GROUP_ROAD_REFINE">直线分组 + 道路精排</option><option value="ROAD_GLOBAL">全程实际距离</option></select></label>
              </div>
              <label class="trace-toggle"><span>调试日志</span><input v-model="optimizeOptions.traceEnabled" type="checkbox" /> <small>{{ optimizeOptions.traceEnabled ? '开启后记录插入过程' : '默认关闭' }}</small></label>
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
              <div class="panel-head compact"><h2>拆分结果</h2><div class="panel-actions"><button @click="openSaveSplitCurrentRoute" :disabled="!selectedMultiRoute || loading">另存当前路线</button><button @click="openSaveSplitGroup" :disabled="!multiOptimization || loading">{{ splitFromSavedRoute ? '保存为新版本' : '另存整组方案' }}</button><button @click="exportCompanyRoutes" :disabled="!multiOptimization || loading">导出Excel</button></div></div>
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
                标准工时 h
                <input v-model.number="optimizeOptions.workHours" type="number" min="1" max="24" step="0.5" />
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
              <label class="trace-toggle"><span>调试日志</span><input v-model="optimizeOptions.traceEnabled" type="checkbox" /> <small>{{ optimizeOptions.traceEnabled ? '开启后记录插入过程' : '默认关闭' }}</small></label>
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
                <strong>{{ dispatchEnabled ? (dispatchMode === 'WORK_HOURS' ? '自动计算' : dispatchTripCount + ' 趟') : '未启用' }}</strong>
              </div>
              <div>
                <span>排班额定总量</span>
                <strong>{{ dispatchEnabled ? (dispatchMode === 'WORK_HOURS' ? '按实际工时' : formatWeight(plannedCapacityKg)) : '-' }}</strong>
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
                  <select v-model="dispatchMode" :class="{ 'new-feature-select': dispatchMode === 'WORK_HOURS' }" @change="clearMultiOptimization">
                    <option value="USER_ORDER">按车辆顺序跑完</option>
                    <option value="ROUND_ROBIN">车辆轮询排班</option>
                    <option value="USER_ORDER_THEN_ROUND_ROBIN">顺序跑完后轮询兜底</option>
                    <option value="WORK_HOURS" class="new-feature-option">按车辆顺序跑满8小时（新功能）</option>
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
                  <input v-model.number="vehicle.tripCount" @input="clearMultiOptimization" :disabled="dispatchMode === 'ROUND_ROBIN' || dispatchMode === 'WORK_HOURS'" :title="dispatchMode === 'ROUND_ROBIN' ? '轮询模式按最大趟数生成，不读取单车趟数' : (dispatchMode === 'WORK_HOURS' ? '新功能按累计工时自动计算趟数，不读取单车趟数' : '')" type="number" min="1" step="1" />
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
                  <button @click="openSaveMultiGroup" :disabled="!multiOptimization || loading">保存方案</button>
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
                        <em v-if="route.timeExceeded" class="time-warning">超出 {{ formatDuration(route.overdueMinutes) }}</em>
                        <em v-else class="time-ok">{{ formatNumber(route.workLimitHours || optimizeOptions.workHours || 8) }} 小时内</em>
                        <em v-if="route.vehicleWorkedMinutes != null" class="time-progress">车辆累计 {{ formatDuration(route.vehicleWorkedMinutes) }}</em>
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
                  <div class="route-time-table multi-route-time-table">
                    <div class="route-time-row head">
                      <span>顺序</span>
                      <span>点位</span>
                      <span>上一段行驶</span>
                      <span>速度</span>
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
                      <span v-if="segmentBeforePoint(selectedMultiRouteDisplaySegments, point.order)">
                        {{ formatNumber(segmentBeforePoint(selectedMultiRouteDisplaySegments, point.order).speedKmh) }} km/h
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
            <h2>{{ splitFromSavedRoute ? '方案库路线' : '项目公司' }}</h2>
            <input v-if="!splitFromSavedRoute" v-model="companyKeyword" placeholder="搜索公司" />
          </div>
          <div v-if="splitFromSavedRoute" class="saved-split-source">
            <strong>{{ selectedSplitRoute?.routeName || '保存路线' }}</strong>
            <small>{{ selectedSavedGroup?.groupName || '方案库方案' }}</small>
            <button class="secondary" @click="exitSavedRouteSplit">返回公司路线</button>
          </div>
          <div v-else class="list">
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



    <div v-if="routeImportDialog.visible" class="modal-mask" @click.self="closeRouteImportDialog">
      <section class="modal-card save-plan-modal">
        <div class="panel-head">
          <div>
            <h2>导入路线展示</h2>
            <span class="muted">只读取 Excel 的 sheet1，并按名称在所选公司下匹配点位和场站</span>
          </div>
          <button class="secondary" @click="closeRouteImportDialog">关闭</button>
        </div>
        <div class="route-import-form">
          <label>
            项目公司
            <div class="company-combobox">
              <input v-model="routeImportCompanyKeyword" placeholder="搜索公司名称、编码或ID" @input="routeImportDialog.unitId = ''" />
              <div class="company-combobox-list">
                <button
                  v-for="company in filteredRouteImportCompanies"
                  :key="company.id"
                  type="button"
                  :class="{ active: String(routeImportDialog.unitId) === String(company.id) }"
                  @click="selectRouteImportCompany(company)"
                >
                  <span>{{ company.depName || company.id }}</span>
                  <small>{{ company.depCode || company.id }}</small>
                </button>
              </div>
            </div>
          </label>
          <label>
            路线名称
            <input v-model.trim="routeImportDialog.routeName" placeholder="不填则使用sheet名称" />
          </label>
          <label>
            Excel文件
            <input ref="routeImportInput" type="file" accept=".xls,.xlsx" @change="handleRouteImportFileChange" />
          </label>
          <p v-if="routeImportDialog.error" class="login-error">{{ routeImportDialog.error }}</p>
        </div>
        <div class="modal-actions">
          <button class="secondary" @click="closeRouteImportDialog">取消</button>
          <button @click="importRoutePreview" :disabled="loading || !routeImportDialog.unitId || !routeImportDialog.file">导入</button>
        </div>
      </section>
    </div>

    <div v-if="folderDialog.visible" class="modal-mask" @click.self="closeFolderDialog">
      <section class="modal-card save-plan-modal">
        <div class="panel-head">
          <div>
            <h2>{{ folderDialog.assignMode ? '归入方案分组' : '新建方案分组' }}</h2>
            <span class="muted">{{ folderDialog.assignMode ? '选择一个文件夹，批量整理已勾选的方案' : '例如：原聚类方案、月山试跑方案' }}</span>
          </div>
          <button class="secondary" @click="closeFolderDialog">关闭</button>
        </div>
        <label v-if="!folderDialog.assignMode" class="save-name-field">
          分组名称
          <input v-model.trim="folderDialog.name" placeholder="请输入分组名称" autofocus />
        </label>
        <label v-else class="save-name-field">
          目标分组
          <select v-model="folderDialog.folderId">
            <option value="">移出分组（放回未分组）</option>
            <option v-for="folder in savedFolders" :key="folder.id" :value="String(folder.id)">{{ folder.folderName }}</option>
          </select>
        </label>
        <div class="modal-actions">
          <button class="secondary" @click="closeFolderDialog">取消</button>
          <button @click="folderDialog.assignMode ? assignSavedPlans() : createSavedFolder()" :disabled="loading">
            {{ folderDialog.assignMode ? '确认归组' : '创建分组' }}
          </button>
        </div>
      </section>
    </div>

    <div v-if="saveDialog.visible" class="modal-mask" @click.self="closeSaveDialog">
      <section class="modal-card save-plan-modal">
        <div class="panel-head">
          <div>
            <h2>{{ saveDialog.title }}</h2>
            <span class="muted">名称为空时使用当前时间戳自动命名</span>
          </div>
          <button class="secondary" @click="closeSaveDialog">关闭</button>
        </div>
        <label class="save-name-field">
          方案名称
          <input v-model="saveDialog.name" placeholder="不填则自动命名" autofocus />
        </label>
        <div class="modal-actions">
          <button class="secondary" @click="closeSaveDialog">取消</button>
          <button @click="confirmSavePlan" :disabled="loading">保存</button>
        </div>
      </section>
    </div>

    <div v-if="error" class="toast error">{{ error }}</div>
    <div v-if="loading" class="toast">加载中...</div>
  </main>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import RouteMapPanel from './components/RouteMapPanel.vue'
import ClusterMapPanel from './components/ClusterMapPanel.vue'

const LOGIN_USERNAME = 'admin'
const LOGIN_PASSWORD = 'zhj521%@!'
const LOGIN_SESSION_KEY = 'zhj_route_authenticated'

const authenticated = ref(window.sessionStorage.getItem(LOGIN_SESSION_KEY) === '1')
const loginForm = reactive({
  username: '',
  password: ''
})
const loginError = ref('')

const currentView = ref('score')
const flowAnalysisCompanyId = ref('')
const flowAnalysisCompanyKeyword = ref('')
const flowAnalysisCarCode = ref('')
const flowAnalysisRouteId = ref('')
const flowAnalysisVehicles = ref([])
const flowAnalysisTask = reactive({ taskId: '', status: 'IDLE', phase: 'PREPARE', message: '请选择公司和车辆', percent: 0, totalRecords: 0, loadedEvents: 0, elapsedMs: 0 })
const flowAnalysisResult = ref(null)
const flowSelectedGroup = ref(null)
const flowShowCollectedOnly = ref(false)
const flowExpandedDays = ref(new Set())
const flowAnalysisPollTimer = ref(null)
const flowAnalysisLoading = ref(false)
const flowAnalysisThreshold = ref(0.6)
const flowForcedBoundaryIds = ref(new Set())
const flowAnalysisFilters = reactive({ startDate: toDateInput(new Date(Date.now() - 29 * 86400000)), endDate: toDateInput(new Date()) })
const odCacheCompany = ref(null)
const odCacheCompanyKeyword = ref('')
const odCachePoints = ref([])
const odCachePointKeyword = ref('')
const odCachePointIds = ref(new Set())
const odCacheParkingOptions = ref([])
const odCacheFacilityOptions = ref([])
const odCacheParkingKeys = ref(new Set())
const odCacheFacilityKeys = ref(new Set())
const odCacheShowSelectedOnly = ref(false)
const odCacheImportInput = ref(null)
const odCacheImportSummary = ref('')
const odCachePollTimer = ref(null)
const odCacheClockTimer = ref(null)
const odCacheClockNow = ref(Date.now())
const odCacheTask = reactive({ visible: false, taskId: '', status: 'IDLE', phase: 'PREPARE', message: '请选择公司和节点', percent: 0, totalPairs: 0, cachedPairs: 0, completedPairs: 0, successPairs: 0, failedPairs: 0, currentPair: '', failures: [], startedAt: 0, elapsedMs: 0, finishedAt: 0 })
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
const showOriginalRoutePreview = ref(false)
const originalRouteDisplayMode = ref('DIRECT')
const originalRouteRoadSegments = ref([])
const feedbackFacilityIds = ref(new Set())
const multiOptimization = ref(null)
const routeMapStatus = ref(null)
const routeProgressTimer = ref(null)
const routeProgressPollFailures = ref(0)
const routeProgressPolling = ref(false)
const routeAbortController = ref(null)
const routeSegmentLoading = ref(false)
const multiRouteDisplayModes = ref({})
const savedRouteDisplayModes = ref({})
const savedGroups = ref([])
const savedFolders = ref([])
const selectedSavedFolderId = ref('all')
const selectedSavedPlanIds = ref([])
const selectedSavedGroup = ref(null)
const selectedSavedRouteId = ref(null)
const savedVersionRecords = ref([])
const selectedSavedVersionRecord = computed(() => savedVersionRecords.value.find((version) => String(version.groupId) === String(selectedSavedGroup.value?.id)))
const savedRouteEditMode = ref(false)
const savedRouteEditPoints = ref([])
const savedFilters = reactive({
  keyword: '',
  sourceType: '',
  unitId: ''
})
const folderDialog = reactive({
  visible: false,
  assignMode: false,
  name: '',
  folderId: ''
})
const saveDialog = reactive({
  visible: false,
  title: '',
  sourceType: '',
  mode: 'group',
  name: '',
  payload: null
})
const routeImportInput = ref(null)
const routeImportCompanyKeyword = ref('')
const routeImportDialog = reactive({
  visible: false,
  unitId: '',
  routeName: '',
  file: null,
  error: ''
})
const importedRoutePreview = ref(null)
const importedRouteDisplayMode = ref('DIRECT')
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

const filteredOdCacheCompanies = computed(() => {
  const keyword = odCacheCompanyKeyword.value.trim().toLowerCase()
  if (!keyword) return companies.value
  return companies.value.filter((company) => `${company.depName || ''} ${company.id || ''}`.toLowerCase().includes(keyword))
})
const flowAnalysisCompany = computed(() => companies.value.find((item) => String(item.id) === String(flowAnalysisCompanyId.value)) || null)
const filteredFlowAnalysisCompanies = computed(() => {
  const keyword = flowAnalysisCompanyKeyword.value.trim().toLowerCase()
  if (!keyword) return []
  return companies.value.filter((company) => `${company.depName || ''} ${company.id || ''}`.toLowerCase().includes(keyword)).slice(0, 30)
})
const flowAnalysisJobs = computed(() => {
  const rows = flowAnalysisVehicles.value.filter((row) => String(row.carCode) === String(flowAnalysisCarCode.value))
  return rows.filter((row, index) => rows.findIndex((item) => String(item.routeId || '') === String(row.routeId || '')) === index)
})
const odCacheVisiblePoints = computed(() => {
  const keyword = odCachePointKeyword.value.trim().toLowerCase()
  return odCachePoints.value.filter((point) => {
    const matchesKeyword = !keyword || `${point.facilityName || ''} ${point.containerInfo || ''}`.toLowerCase().includes(keyword)
    const selected = odCachePointIds.value.has(String(point.facilityId))
    return matchesKeyword && (!odCacheShowSelectedOnly.value || selected)
  })
})
const odCacheSelectedPointCount = computed(() => odCachePointIds.value.size)
const odCachePayload = computed(() => {
  const result = []
  odCachePoints.value.forEach((point) => { if (odCachePointIds.value.has(String(point.facilityId))) result.push(point) })
  ;[...odCacheParkingOptions.value, ...odCacheFacilityOptions.value].forEach((point) => {
    if ((odCacheParkingKeys.value.has(point.key) || odCacheFacilityKeys.value.has(point.key)) && !result.some((item) => String(item.facilityId) === String(point.facilityId))) result.push(point)
  })
  return result
})
const odCachePairCount = computed(() => odCachePayload.value.length * Math.max(0, odCachePayload.value.length - 1))
const odCacheRunning = computed(() => ['QUEUED', 'RUNNING'].includes(odCacheTask.status))
const odCacheElapsedMs = computed(() => {
  const terminal = ['DONE', 'PARTIAL', 'FAILED', 'CANCELLED', 'NOT_FOUND'].includes(odCacheTask.status)
  if (terminal && odCacheTask.elapsedMs) return Number(odCacheTask.elapsedMs)
  if (!odCacheTask.startedAt) return 0
  const end = terminal && odCacheTask.finishedAt ? Number(odCacheTask.finishedAt) : odCacheClockNow.value
  return Math.max(0, end - Number(odCacheTask.startedAt))
})
const odCacheElapsedText = computed(() => formatOdCacheElapsed(odCacheElapsedMs.value))
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
const splitFromSavedRoute = ref(false)
const splitSourceMode = ref('COMPANY')
const savedTreeExpandedFolderIds = ref(new Set())
const savedTreeExpandedGroupIds = ref(new Set())
const savedGroupDetails = ref({})
const splitSavedExpandedFolderIds = ref(new Set())
const splitSavedExpandedGroupIds = ref(new Set())
const splitSavedGroupDetails = ref({})
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
  workHours: 8,
  secondsPerContainer: 35,
  minutesPerPoint: 3,
  useRoadPath: false,
  multiRouteStrategy: 'DIRECT_GROUP',
  displayRoadPath: false,
  traceEnabled: false,
  startLongitude: null,
  startLatitude: null,
  startFacilityId: null,
  startFacilityName: null,
  endLongitude: null,
  endLatitude: null,
  endFacilityId: null,
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

const filteredRouteImportCompanies = computed(() => {
  const keyword = routeImportCompanyKeyword.value.trim().toLowerCase()
  const list = keyword
    ? companies.value.filter((item) =>
        [item.depName, item.depCode, item.id].some((value) =>
          String(value || '').toLowerCase().includes(keyword)
        )
      )
    : companies.value
  return list.slice(0, 60)
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
  // 直线展示严格按当前路线点位顺序重建，避免后端某段几何缺失导致标记点未被连线.
  return route.segments?.length ? route.segments : buildDirectRouteSegments(route.points || [], Number(route.speedKmh) > 0 ? Number(route.speedKmh) : 20)
}
function routeDisplayDistance(route) {
  if (!route) return 0
  // Map toggles must not change planning metrics.?
  return Number(route.planningDistance ?? route.distance ?? 0)
}
function routeDisplayTravelDuration(route) {
  if (!route) return 0
  return Number(route.planningTravelDurationMinutes ?? route.travelDurationMinutes ?? 0)
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

const filteredSavedGroups = computed(() => {
  if (selectedSavedFolderId.value === 'all') return savedGroups.value
  if (selectedSavedFolderId.value === 'ungrouped') return savedGroups.value.filter((group) => !group.folderId)
  return savedGroups.value.filter((group) => String(group.folderId) === String(selectedSavedFolderId.value))
})

const savedLibraryTreeFolders = computed(() => savedFolders.value || [])
const splitSavedTreeFolders = computed(() => [
  ...(savedFolders.value || []),
  { id: 'ungrouped', folderName: '未分组' }
])
function savedLibraryTreeGroups(folderId) {
  if (String(folderId) === 'ungrouped') return savedGroups.value.filter((group) => !group.folderId)
  return savedGroups.value.filter((group) => String(group.folderId) === String(folderId))
}
function splitSavedTreeGroups(folderId) {
  return savedLibraryTreeGroups(folderId)
}
function isSavedLibraryFolderExpanded(folderId) {
  return savedTreeExpandedFolderIds.value.has(String(folderId))
}
function isSavedLibraryGroupExpanded(groupId) {
  return savedTreeExpandedGroupIds.value.has(String(groupId))
}
function isSplitSavedFolderExpanded(folderId) {
  return splitSavedExpandedFolderIds.value.has(String(folderId))
}
function isSplitSavedGroupExpanded(groupId) {
  return splitSavedExpandedGroupIds.value.has(String(groupId))
}
async function ensureSavedGroupDetail(group, splitMode = false) {
  const store = splitMode ? splitSavedGroupDetails.value : savedGroupDetails.value
  if (store[group.id]) return store[group.id]
  const detail = await api('/api/route-plans/groups/' + group.id)
  if (splitMode) {
    splitSavedGroupDetails.value = { ...splitSavedGroupDetails.value, [group.id]: detail }
  } else {
    savedGroupDetails.value = { ...savedGroupDetails.value, [group.id]: detail }
  }
  return detail
}
async function toggleSavedLibraryFolder(folderId) {
  const next = new Set(savedTreeExpandedFolderIds.value)
  const key = String(folderId)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  savedTreeExpandedFolderIds.value = next
  selectSavedFolder(folderId)
}
async function toggleSavedLibraryGroup(group) {
  const next = new Set(savedTreeExpandedGroupIds.value)
  const key = String(group.id)
  if (next.has(key)) next.delete(key)
  else {
    next.add(key)
    await ensureSavedGroupDetail(group)
  }
  savedTreeExpandedGroupIds.value = next
  selectedSavedGroup.value = selectedSavedGroup.value?.id === group.id ? selectedSavedGroup.value : null
}
async function selectSavedLibraryRoute(group, route) {
  const detail = await ensureSavedGroupDetail(group)
  selectedSavedGroup.value = detail
  selectedSavedRouteId.value = route.id
  savedVersionRecords.value = await api('/api/route-plans/groups/' + detail.id + '/versions')
}
async function setSplitSourceMode(mode) {
  splitSourceMode.value = mode
  if (mode === 'COMPANY') {
    exitSavedRouteSplit()
    return
  }
  splitFromSavedRoute.value = false
  selectedSplitCompany.value = null
  selectedSplitRoute.value = null
  splitRoutes.value = []
  splitPlanPoints.value = []
  clearMultiOptimization()
}
async function toggleSplitSavedFolder(folderId) {
  const next = new Set(splitSavedExpandedFolderIds.value)
  const key = String(folderId)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  splitSavedExpandedFolderIds.value = next
}
async function toggleSplitSavedGroup(group) {
  const next = new Set(splitSavedExpandedGroupIds.value)
  const key = String(group.id)
  if (next.has(key)) next.delete(key)
  else {
    next.add(key)
    await ensureSavedGroupDetail(group, true)
  }
  splitSavedExpandedGroupIds.value = next
}
async function selectSplitSavedRoute(group, route) {
  const detail = await ensureSavedGroupDetail(group, true)
  selectedSavedGroup.value = detail
  selectedSavedRouteId.value = route.id
  savedVersionRecords.value = await api('/api/route-plans/groups/' + detail.id + '/versions')
  openSavedRouteSplit(route)
}

const isSavedRouteWorkbench = computed(() => Boolean(selectedRoute.value?.inlinePoints && selectedRoute.value?.sourceSavedRouteId))

const selectedSavedRoute = computed(() => {
  const routes = selectedSavedGroup.value?.routes || []
  if (!routes.length) return null
  return routes.find((route) => route.id === selectedSavedRouteId.value) || routes[0]
})
const selectedSavedRouteRoadDisplay = computed(() => {
  const routeId = selectedSavedRoute.value?.id
  return !!(routeId && savedRouteDisplayModes.value[routeId] === 'ROAD')
})
const selectedSavedRouteDisplaySegments = computed(() => {
  const route = selectedSavedRoute.value
  if (!route) return []
  return savedRouteDisplaySegments(route)
})
const selectedSavedRouteDisplaySummary = computed(() => {
  const route = selectedSavedRoute.value
  if (!route) return ''
  return formatDistance(savedRouteDisplayDistance(route)) + ' · ' + formatDuration(savedRouteDisplayTotalDuration(route)) + ' · ' + savedRouteDisplaySummary(route)
})
const importedRouteDisplaySegments = computed(() => {
  const route = importedRoutePreview.value
  if (!route) return []
  if (importedRouteDisplayMode.value === 'ROAD' && hasRoadSegments(route.roadSegments)) return route.roadSegments
  // 直线展示严格按当前路线点位顺序重建，避免后端某段几何缺失导致标记点未被连线.
  return route.segments?.length ? route.segments : buildDirectRouteSegments(route.points || [], Number(route.speedKmh) > 0 ? Number(route.speedKmh) : 20)
})
const importedRouteDisplaySummary = computed(() => {
  const route = importedRoutePreview.value
  if (!route) return ''
  const distance = importedRouteDisplayMode.value === 'ROAD' && route.roadDistance ? route.roadDistance : route.distance
  const travel = importedRouteDisplayMode.value === 'ROAD' && route.roadDurationMinutes ? route.roadDurationMinutes : route.travelDurationMinutes
  return formatDistance(distance) + ' · ' + formatDuration(travel) + ' · ' + pathSourceSummary(importedRouteDisplaySegments.value)
})
function savedRouteRoadDisplay(route) {
  return !!(route?.id && savedRouteDisplayModes.value[route.id] === 'ROAD')
}
function savedRouteDisplaySegments(route) {
  if (!route) return []
  if (savedRouteRoadDisplay(route) && hasRoadSegments(route.roadSegments)) return route.roadSegments
  return route.segments?.length ? route.segments : buildDirectRouteSegments(route.points || [], savedRouteSpeedKmh(route))
}
function savedRouteDisplayDistance(route) {
  return sumSegmentDistance(savedRouteDisplaySegments(route))
}
function savedRouteDisplayTravelDuration(route) {
  return sumSegmentDuration(savedRouteDisplaySegments(route))
}
function savedRouteDisplayTotalDuration(route) {
  if (!route) return 0
  return Number(savedRouteDisplayTravelDuration(route) || 0) + Number(route.operationDurationMinutes || 0)
}
function savedRouteSpeedKmh(route) {
  const request = selectedSavedGroup.value?.request || {}
  const optimizeOptions = request.optimizeOptions || {}
  const value = route?.speedKmh || optimizeOptions.speedKmh || request.speedKmh
  const speed = Number(value)
  return Number.isFinite(speed) && speed > 0 ? speed : 20
}
function buildDirectRouteSegments(points, speedKmh = 20) {
  const segments = []
  for (let i = 0; i < points.length - 1; i += 1) {
    const from = points[i]
    const to = points[i + 1]
    const distance = coordinateDistance(from, to)
    segments.push({
      order: i + 1,
      fromFacilityId: from.facilityId,
      fromFacilityName: from.facilityName,
      toFacilityId: to.facilityId,
      toFacilityName: to.facilityName,
      distance,
      durationMinutes: distance / (speedKmh * 1000) * 60,
      pathSource: 'DIRECT',
      path: [
        { facilityId: from.facilityId, facilityName: from.facilityName, longitude: Number(from.longitude), latitude: Number(from.latitude) },
        { facilityId: to.facilityId, facilityName: to.facilityName, longitude: Number(to.longitude), latitude: Number(to.latitude) }
      ]
    })
  }
  return segments
}
function sumSegmentDistance(segments = []) {
  return (segments || []).reduce((sum, segment) => sum + Number(segment.distance || 0), 0)
}
function sumSegmentDuration(segments = []) {
  return (segments || []).reduce((sum, segment) => sum + Number(segment.durationMinutes || 0), 0)
}
function savedRouteDisplaySummary(route) {
  const segments = savedRouteDisplaySegments(route)
  return pathSourceSummary(segments || []) || '点位直线'
}

function hasRoadSegments(segments) {
  return (segments || []).some((segment) => isRoadPathSource(segment?.pathSource))
}

function isRoadPathSource(source) {
  return source === 'OD_CACHE' || source === 'OD_PRELOAD' || source === 'BAIDU_ONLINE'
}

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
  if (dispatchMode.value === 'WORK_HOURS') {
    return '按车辆列表顺序连续生成路线；每趟受剩余工时约束，达到标准工时后自动切换下一辆车，单车趟数无需预先填写。'
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
  if (authenticated.value) {
    await initializeAppData()
  }
})

async function initializeAppData() {
  await Promise.all([loadCompanies(), loadRouteMapStatus(), loadSavedGroups()])
}

async function handleLogin() {
  if (loginForm.username === LOGIN_USERNAME && loginForm.password === LOGIN_PASSWORD) {
    window.sessionStorage.setItem(LOGIN_SESSION_KEY, '1')
    authenticated.value = true
    loginError.value = ''
    loginForm.password = ''
    await initializeAppData()
    return
  }
  loginError.value = '账号或密码错误'
}

function handleLogout() {
  window.sessionStorage.removeItem(LOGIN_SESSION_KEY)
  authenticated.value = false
  loginForm.password = ''
}

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
  feedbackFacilityIds.value = new Set()
  showOriginalRoutePreview.value = false
  originalRouteDisplayMode.value = 'DIRECT'
  originalRouteRoadSegments.value = []
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
  splitSourceMode.value = 'COMPANY'
  splitFromSavedRoute.value = false
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
  optimizeOptions.startFacilityId = null
  optimizeOptions.startFacilityName = null
  optimizeOptions.endLongitude = null
  optimizeOptions.endLatitude = null
  optimizeOptions.endFacilityId = null
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
  const idKey = `${prefix}FacilityId`
  const nameKey = `${prefix}FacilityName`
  if (!anchor) {
    optimizeOptions[longitudeKey] = null
    optimizeOptions[latitudeKey] = null
    optimizeOptions[idKey] = null
    optimizeOptions[nameKey] = null
    return
  }
  optimizeOptions[longitudeKey] = Number(anchor.longitude)
  optimizeOptions[latitudeKey] = Number(anchor.latitude)
  optimizeOptions[idKey] = anchor.facilityId || null
  optimizeOptions[nameKey] = anchor.facilityName || anchor.facilityId
}

function toggleOriginalRoutePreview() {
  showOriginalRoutePreview.value = !showOriginalRoutePreview.value
}

async function setOriginalRouteDisplay(useRoad) {
  if (!useRoad) {
    originalRouteDisplayMode.value = 'DIRECT'
    return
  }
  if (originalRouteDisplayMode.value === 'ROAD' && originalRouteRoadSegments.value.length) return
  originalRouteDisplayMode.value = 'ROAD'
  routeSegmentLoading.value = true
  try {
    const result = await api('/api/optimize/route-segments', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ points: planPoints.value || [], displayRoadPath: true })
    })
    originalRouteRoadSegments.value = result.segments || []
  } catch (err) {
    error.value = err.message || String(err)
    originalRouteDisplayMode.value = 'DIRECT'
  } finally {
    routeSegmentLoading.value = false
  }
}

function toggleFeedbackPoint(point) {
  const id = String(point?.facilityId || '')
  if (!id) return
  const next = new Set(feedbackFacilityIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  feedbackFacilityIds.value = next
}

async function previewOptimize(feedback = false) {
  if (!selectedRoute.value) return
  await withLoading(async () => {
    const request = {
      unitId: selectedCompany.value.id,
      // 普通“优化预览”明确清空反馈点，只执行整条路线优化。
      feedbackFacilityIds: feedback ? [...feedbackFacilityIds.value] : [],
      ...optimizeOptions
    }
    if (selectedRoute.value.inlinePoints) request.points = planPoints.value || []
    else request.routeId = selectedRoute.value.id
    optimization.value = await api('/api/optimize/preview', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request)
    })
  })
}

async function adjustByFeedback() {
  if (!feedbackFacilityIds.value.size) return
  await previewOptimize(true)
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
      const request = {
        unitId: selectedSplitCompany.value.id,
        dispatchMode: dispatchEnabled.value ? dispatchMode.value : 'USER_ORDER',
        vehicles: normalizedDispatchVehicles(),
        endSelectionMode: endAnchorMode.value,
        endCandidates: normalizedEndCandidates(),
        ...optimizeOptions,
        displayRoadPath: false,
        useRoadPath: optimizeOptions.multiRouteStrategy === 'ROAD_GLOBAL'
      }
      if (splitFromSavedRoute.value) {
        request.points = splitPlanPoints.value
        request.sourceType = 'SAVED_PLAN_ROUTE'
        request.sourceRouteName = selectedSplitRoute.value.routeName
      } else {
        request.routeId = selectedSplitRoute.value.id
      }
      const task = await api('/api/optimize/multi-preview/tasks', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        signal: routeAbortController.value.signal,
        body: JSON.stringify(request)
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

function openSavedRouteEdit(route) {
  if (!route?.points?.length) return
  selectedSavedRouteId.value = route.id
  savedRouteEditPoints.value = deepClone(route.points).map((point, index) => ({
    ...point,
    order: index + 1,
    pointOrder: index + 1
  }))
  savedRouteEditMode.value = true
}

function moveSavedEditPoint(index, delta) {
  const target = index + delta
  if (target < 0 || target >= savedRouteEditPoints.value.length) return
  const points = [...savedRouteEditPoints.value]
  const current = points[index]
  points[index] = points[target]
  points[target] = current
  savedRouteEditPoints.value = points.map((point, pointIndex) => ({
    ...point,
    order: pointIndex + 1,
    pointOrder: pointIndex + 1
  }))
}

function removeSavedEditPoint(index) {
  if (savedRouteEditPoints.value.length <= 2) return
  savedRouteEditPoints.value = savedRouteEditPoints.value
    .filter((_, pointIndex) => pointIndex !== index)
    .map((point, pointIndex) => ({ ...point, order: pointIndex + 1, pointOrder: pointIndex + 1 }))
}

function cancelSavedRouteEdit() {
  savedRouteEditMode.value = false
  savedRouteEditPoints.value = []
}

async function saveSavedRouteEdit() {
  const group = selectedSavedGroup.value
  const route = selectedSavedRoute.value
  if (!group || !route || savedRouteEditPoints.value.length < 2) return
  const editedRoute = routeSnapshotForSave({
    ...route,
    points: savedRouteEditPoints.value,
    operationType: 'EDIT',
    parentRouteId: route.id
  })
  const payload = {
    mode: 'group',
    groupName: group.groupName,
    sourceType: 'EDIT',
    operationType: 'EDIT',
    parentGroupId: group.id,
    rootGroupId: group.rootGroupId || group.id,
    versionNo: Math.max(1, Number(group.versionNo || 1) + 1),
    unitId: group.unitId,
    unitName: group.unitName,
    originRouteId: group.originRouteId,
    originRouteName: group.originRouteName,
    planningStrategy: group.planningStrategy,
    defaultDisplayMode: group.defaultDisplayMode || 'DIRECT',
    routes: (group.routes || []).map((item) => item.id === route.id ? editedRoute : routeSnapshotForSave({ ...item, operationType: 'EDIT' })),
    summary: { operation: 'EDIT', editedRouteId: route.id },
    request: { operation: 'EDIT', sourceGroupId: group.id, sourceRouteId: route.id }
  }
  await withLoading(async () => {
    const saved = await api('/api/route-plans/groups', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    })
    cancelSavedRouteEdit()
    await loadSavedGroups()
    currentView.value = 'saved'
    await selectSavedGroup({ id: saved.id })
  })
}

async function openSavedRouteOptimize(route) {
  const group = selectedSavedGroup.value
  if (!route?.points?.length) return
  selectedCompany.value = {
    id: group?.unitId || '',
    depName: group?.unitName || '方案库来源',
    depCode: '方案库'
  }
  selectedRoute.value = {
    ...route,
    id: null,
    sourceSavedRouteId: route.id,
    sourceType: 'SAVED_PLAN_ROUTE',
    inlinePoints: true,
    routeName: route.routeName || ('第 ' + (route.routeNo || 1) + ' 趟')
  }
  planPoints.value = (route.points || []).map((point, index) => ({
    ...point,
    orderNum: point.orderNum ?? point.order ?? index + 1
  }))
  records.value = []
  feedbackFacilityIds.value = new Set()
  optimization.value = null
  showOriginalRoutePreview.value = false
  originalRouteDisplayMode.value = 'DIRECT'
  originalRouteRoadSegments.value = []
  currentView.value = 'workbench'
}

async function openSavedRouteSplit(route) {
  const group = selectedSavedGroup.value
  splitSourceMode.value = 'LIBRARY'
  if (group?.folderId) {
    splitSavedExpandedFolderIds.value = new Set([...splitSavedExpandedFolderIds.value, String(group.folderId)])
    savedTreeExpandedFolderIds.value = new Set([...savedTreeExpandedFolderIds.value, String(group.folderId)])
  }
  if (group?.id) {
    splitSavedExpandedGroupIds.value = new Set([...splitSavedExpandedGroupIds.value, String(group.id)])
    savedTreeExpandedGroupIds.value = new Set([...savedTreeExpandedGroupIds.value, String(group.id)])
  }
  const points = (route?.points || []).map((point, index) => ({
    ...point,
    orderNum: point.orderNum ?? point.order ?? index + 1
  }))
  splitFromSavedRoute.value = true
  selectedSplitCompany.value = {
    id: group?.unitId || route?.unitId || '',
    depName: group?.unitName || '方案库来源',
    depCode: '方案库'
  }
  selectedSplitRoute.value = {
    ...route,
    routeName: route.routeName || ('第 ' + (route.routeNo || 1) + ' 趟')
  }
  splitRoutes.value = [selectedSplitRoute.value]
  splitPlanPoints.value = points
  resetAnchors()
  clearMultiOptimization()
  currentView.value = 'split'
  const unitId = selectedSplitCompany.value.id
  if (unitId) {
    try {
      const anchors = await api('/api/companies/' + unitId + '/route-anchors')
      companyAnchors.value = normalizeAnchors(anchors)
      applyDefaultAnchors()
    } catch (err) {
      error.value = err.message || String(err)
    }
  }
}

function exitSavedRouteSplit() {
  splitSourceMode.value = 'COMPANY'
  splitFromSavedRoute.value = false
  selectedSplitCompany.value = null
  selectedSplitRoute.value = null
  splitRoutes.value = []
  splitPlanPoints.value = []
  resetAnchors()
  clearMultiOptimization()
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
      { key: 'cache-check', title: '检查道路 OD 缓存', detail: '读取本批已有的道路点对' },
      { key: 'pair-resolve', title: '补齐道路 OD 点对', detail: '缺失点对统一调用百度补算，失败则停止，不使用直线回退' }
    )
  } else {
    steps.push({ key: 'direct-distance', title: '计算直线距离', detail: '使用点位经纬度距离进行快速规划' })
  }
  steps.push(
    { key: 'route-build', title: '生成多趟路线', detail: optimizeOptions.multiRouteStrategy === 'ROAD_GLOBAL' ? 'OD 全部准备完成后，按道路距离逐趟插入点位' : '按装载目标逐趟插入点位' },
  )
  if (optimizeOptions.multiRouteStrategy === 'DIRECT_GROUP_ROAD_REFINE') {
    steps.push({ key: 'route-refine', title: '单趟道路精排', detail: '每趟先补齐全部有向 OD，再按道路距离重新排序' })
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
  routeProgressPollFailures.value = 0
  routeProgressPolling.value = false
  routeProgressTimer.value = window.setInterval(async () => {
    if (routeProgressPolling.value) return
    routeProgressPolling.value = true
    try {
      const task = await api('/api/optimize/multi-preview/tasks/' + taskId)
      routeProgressPollFailures.value = 0
      applyRouteTask(task)
      if (['DONE', 'FAILED', 'CANCELLED', 'NOT_FOUND'].includes(task.status)) {
        stopRouteProgressTimer()
      }
    } catch (err) {
      routeProgressPollFailures.value += 1
      if (routeProgressPollFailures.value >= 5) {
        stopRouteProgressTimer()
        failRouteProgress(err)
      } else {
        routeProgress.message = '进度查询暂时失败，正在自动重试（' + routeProgressPollFailures.value + '/5）'
      }
    } finally {
      routeProgressPolling.value = false
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
  if (hasRoadSegments(route.roadSegments)) return
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



function openRouteImportDialog() {
  routeImportDialog.visible = true
  routeImportDialog.error = ''
  if (!routeImportDialog.unitId && savedFilters.unitId) {
    routeImportDialog.unitId = savedFilters.unitId
  }
  const selected = companies.value.find((item) => String(item.id) === String(routeImportDialog.unitId))
  routeImportCompanyKeyword.value = selected ? `${selected.depName || selected.id}` : ''
}

function selectRouteImportCompany(company) {
  routeImportDialog.unitId = company.id
  routeImportCompanyKeyword.value = company.depName || company.depCode || String(company.id)
}

function closeRouteImportDialog() {
  routeImportDialog.visible = false
  routeImportDialog.error = ''
}

function handleRouteImportFileChange(event) {
  routeImportDialog.file = event.target.files?.[0] || null
}

async function importRoutePreview() {
  if (!routeImportDialog.unitId || !routeImportDialog.file) return
  const company = companies.value.find((item) => String(item.id) === String(routeImportDialog.unitId))
  const form = new FormData()
  form.append('unitId', routeImportDialog.unitId)
  form.append('file', routeImportDialog.file)
  await withLoading(async () => {
    const response = await fetch('/api/import/route-preview', { method: 'POST', body: form })
    if (!response.ok) {
      const body = await response.json().catch(() => null)
      throw new Error(body?.message || `${response.status} ${response.statusText}`)
    }
    const preview = await response.json()
    const routeName = routeImportDialog.routeName || preview.routeName || '导入路线'
    const direct = await api('/api/optimize/route-segments', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ points: preview.points || [], displayRoadPath: false })
    })
    importedRoutePreview.value = {
      ...preview,
      routeName,
      unitId: routeImportDialog.unitId,
      unitName: company?.depName || routeImportDialog.unitId,
      points: preview.points || [],
      segments: direct.segments || [],
      distance: direct.distance,
      travelDurationMinutes: direct.durationMinutes,
      pathSourceSummary: pathSourceSummary(direct.segments || [])
    }
    importedRouteDisplayMode.value = 'DIRECT'
    selectedSavedGroup.value = null
    selectedSavedRouteId.value = null
    closeRouteImportDialog()
  })
}

async function setImportedRouteDisplay(useRoad) {
  const route = importedRoutePreview.value
  if (!route) return
  if (!useRoad) {
    importedRouteDisplayMode.value = 'DIRECT'
    return
  }
  importedRouteDisplayMode.value = 'ROAD'
  if (hasRoadSegments(route.roadSegments)) return
  routeSegmentLoading.value = true
  try {
    const result = await api('/api/optimize/route-segments', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ points: route.points || [], displayRoadPath: true })
    })
    route.roadSegments = result.segments || []
    route.roadDistance = result.distance
    route.roadDurationMinutes = result.durationMinutes
  } catch (err) {
    error.value = err.message || String(err)
    importedRouteDisplayMode.value = 'DIRECT'
  } finally {
    routeSegmentLoading.value = false
  }
}

function clearImportedRoutePreview() {
  importedRoutePreview.value = null
  importedRouteDisplayMode.value = 'DIRECT'
}

async function saveImportedRoute() {
  const route = importedRoutePreview.value
  if (!route?.points?.length) return
  const payload = {
    mode: 'route',
    sourceType: 'IMPORT',
    unitId: route.unitId,
    unitName: route.unitName,
    planningStrategy: 'IMPORT_DISPLAY',
    defaultDisplayMode: importedRouteDisplayMode.value,
    routeName: route.routeName,
    groupName: route.routeName,
    route: routeSnapshotForSave({ ...route, sourceType: 'IMPORT' }),
    summary: {
      totalCount: route.totalCount,
      matchedCount: route.matchedCount,
      unmatchedCount: route.unmatchedCount,
      sheetName: route.sheetName
    },
    request: {
      importRows: route.rows,
      source: 'excel-sheet1'
    }
  }
  await withLoading(async () => {
    const saved = await api('/api/route-plans/routes', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    })
    await loadSavedGroups()
    clearImportedRoutePreview()
    await selectSavedGroup({ id: saved.groupId })
  })
}

async function loadSavedGroups() {
  const params = new URLSearchParams()
  if (savedFilters.keyword) params.set('keyword', savedFilters.keyword)
  if (savedFilters.sourceType) params.set('sourceType', savedFilters.sourceType)
  if (savedFilters.unitId) params.set('unitId', savedFilters.unitId)
  await withLoading(async () => {
    const [groups, folders] = await Promise.all([
      api('/api/route-plans/groups' + (params.toString() ? '?' + params.toString() : '')),
      api('/api/route-plan-folders')
    ])
    savedGroups.value = groups
    savedFolders.value = folders
    selectedSavedPlanIds.value = selectedSavedPlanIds.value.filter((id) => savedGroups.value.some((group) => String(group.id) === String(id)))
    if (selectedSavedGroup.value && !savedGroups.value.some((group) => group.id === selectedSavedGroup.value.id)) {
      selectedSavedGroup.value = null
      selectedSavedRouteId.value = null
    }
  })
}

function selectSavedFolder(folderId) {
  selectedSavedFolderId.value = folderId
}

function openCreateFolderDialog() {
  folderDialog.visible = true
  folderDialog.assignMode = false
  folderDialog.name = ''
  folderDialog.folderId = ''
}

function openAssignFolderDialog() {
  if (!selectedSavedPlanIds.value.length) return
  folderDialog.visible = true
  folderDialog.assignMode = true
  folderDialog.name = ''
  folderDialog.folderId = selectedSavedFolderId.value !== 'all' && selectedSavedFolderId.value !== 'ungrouped'
    ? String(selectedSavedFolderId.value)
    : ''
}

function closeFolderDialog() {
  folderDialog.visible = false
  folderDialog.assignMode = false
  folderDialog.name = ''
  folderDialog.folderId = ''
}

async function createSavedFolder() {
  if (!folderDialog.name.trim()) {
    error.value = '请输入分组名称'
    return
  }
  await withLoading(async () => {
    await api('/api/route-plan-folders', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        folderName: folderDialog.name.trim(),
        unitId: savedFilters.unitId || null
      })
    })
    closeFolderDialog()
    await loadSavedGroups()
  })
}

async function assignSavedPlans() {
  if (!selectedSavedPlanIds.value.length) return
  await withLoading(async () => {
    await api('/api/route-plans/groups/folder', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        folderId: folderDialog.folderId || null,
        groupIds: selectedSavedPlanIds.value
      })
    })
    selectedSavedPlanIds.value = []
    closeFolderDialog()
    await loadSavedGroups()
  })
}

async function selectSavedGroup(group) {
  importedRoutePreview.value = null
  importedRouteDisplayMode.value = 'DIRECT'
  await withLoading(async () => {
    selectedSavedGroup.value = await api('/api/route-plans/groups/' + group.id)
    selectedSavedRouteId.value = selectedSavedGroup.value.routes?.[0]?.id || null
    savedVersionRecords.value = await api('/api/route-plans/groups/' + group.id + '/versions')
  })
}

async function selectSavedVersion(groupId) {
  if (!groupId || String(groupId) === String(selectedSavedGroup.value?.id)) return
  await withLoading(async () => {
    selectedSavedGroup.value = await api('/api/route-plans/groups/' + groupId)
    selectedSavedRouteId.value = selectedSavedGroup.value.routes?.[0]?.id || null
  })
}

async function restoreSelectedVersion() {
  const group = selectedSavedGroup.value
  const version = selectedSavedVersionRecord.value
  const rootGroupId = group?.rootGroupId || group?.id
  if (!rootGroupId || !version || !window.confirm(`确认基于 V${version.versionNo} 恢复为新版本？`)) return
  await withLoading(async () => {
    const restored = await api('/api/route-plans/groups/' + rootGroupId + '/restore', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ versionId: version.id, changeSummary: `从V${version.versionNo}恢复` })
    })
    await loadSavedGroups()
    await selectSavedGroup({ id: restored.id })
  })
}

async function setSavedRouteDisplay(useRoad) {
  const route = selectedSavedRoute.value
  if (!route) return
  if (!useRoad) {
    savedRouteDisplayModes.value = { ...savedRouteDisplayModes.value, [route.id]: 'DIRECT' }
    return
  }
  savedRouteDisplayModes.value = { ...savedRouteDisplayModes.value, [route.id]: 'ROAD' }
  if (hasRoadSegments(route.roadSegments) && Number(route.roadDistance || 0) > 0) return
  routeSegmentLoading.value = true
  try {
    const result = await api('/api/optimize/route-segments', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ points: route.points || [], displayRoadPath: true })
    })
    route.roadSegments = result.segments || []
    route.roadDistance = result.distance
    route.roadDurationMinutes = result.durationMinutes
  } catch (err) {
    error.value = err.message || String(err)
    savedRouteDisplayModes.value = { ...savedRouteDisplayModes.value, [route.id]: 'DIRECT' }
  } finally {
    routeSegmentLoading.value = false
  }
}

async function deleteSavedGroup(group) {
  if (!group || !window.confirm('确认删除该路线方案？')) return
  await withLoading(async () => {
    await fetch('/api/route-plans/groups/' + group.id, { method: 'DELETE' })
    selectedSavedGroup.value = null
    selectedSavedRouteId.value = null
    await loadSavedGroups()
  })
}

async function deleteSavedRoute(route) {
  if (!route || !window.confirm('确认删除该条保存路线？')) return
  await withLoading(async () => {
    await fetch('/api/route-plans/routes/' + route.id, { method: 'DELETE' })
    if (selectedSavedGroup.value) {
      const groupId = selectedSavedGroup.value.id
      selectedSavedGroup.value = await api('/api/route-plans/groups/' + groupId)
      selectedSavedRouteId.value = selectedSavedGroup.value.routes?.[0]?.id || null
    }
    await loadSavedGroups()
  })
}

function openSaveSingleOptimization() {
  if (!optimization.value || !selectedRoute.value) return
  openSaveDialog(isSavedRouteWorkbench.value ? '保存优化结果为新版本' : '保存优化路线', buildSingleOptimizationSavePayload())
}

function openSaveSplitGroup() {
  if (!multiOptimization.value || !selectedSplitRoute.value) return
  const title = splitFromSavedRoute.value ? '保存拆分结果为新版本' : '另存整组拆分方案'
  openSaveDialog(title, buildMultiGroupSavePayload('SPLIT'))
}

function openSaveSplitCurrentRoute() {
  if (!selectedMultiRoute.value || !selectedSplitRoute.value) return
  openSaveDialog('另存当前拆分路线', buildSingleMultiRouteSavePayload('SPLIT'))
}

function openSaveMultiGroup() {
  if (!multiOptimization.value || !selectedMultiCompany.value) return
  openSaveDialog('保存多路线方案', buildMultiGroupSavePayload('MULTI'))
}

function openSaveDialog(title, payload) {
  saveDialog.visible = true
  saveDialog.title = title
  saveDialog.name = ''
  saveDialog.mode = payload.mode
  saveDialog.sourceType = payload.sourceType
  saveDialog.payload = payload
}

function closeSaveDialog() {
  saveDialog.visible = false
  saveDialog.name = ''
  saveDialog.payload = null
}

async function confirmSavePlan() {
  if (!saveDialog.payload) return
  const payload = deepClone(saveDialog.payload)
  if (saveDialog.name.trim()) {
    payload.groupName = saveDialog.name.trim()
    payload.routeName = saveDialog.name.trim()
  }
  const path = payload.mode === 'route' ? '/api/route-plans/routes' : '/api/route-plans/groups'
  await withLoading(async () => {
    const saved = await api(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    })
    closeSaveDialog()
    await loadSavedGroups()
    currentView.value = 'saved'
    if (payload.mode === 'route') {
      await selectSavedGroup({ id: saved.groupId || saved.group?.id || saved.groupId })
    } else {
      await selectSavedGroup({ id: saved.id })
    }
  })
}

function buildSingleOptimizationSavePoints() {
  const feedbackIds = new Set((optimization.value.feedbackFacilityIds || []).map((id) => String(id)))
  const originalOrders = new Map((planPoints.value || []).map((point, index) => [
    String(point.facilityId),
    Number(point.order || point.pointOrder || index + 1)
  ]))
  const feedbackMode = Boolean(optimization.value.feedbackMode)
  return (optimization.value.points || []).map((point, index) => {
    const selected = feedbackIds.has(String(point.facilityId))
    return {
      ...point,
      originalOrder: originalOrders.get(String(point.facilityId)) || point.originalOrder || index + 1,
      pointSource: feedbackMode && !selected ? 'ORIGINAL' : 'OPTIMIZED',
      feedbackFlag: feedbackMode && selected ? 1 : 0
    }
  })
}

function buildSingleOptimizationSavePayload() {
  const route = {
    routeNo: 1,
    routeName: (selectedRoute.value.routeName || selectedRoute.value.id) + ' 优化路线',
    points: buildSingleOptimizationSavePoints(),
    segments: optimization.value.segments || [],
    distance: optimization.value.optimizedDistance,
    roadDistance: optimization.value.displayMode === 'ROAD' ? optimization.value.optimizedDistance : null,
    travelDurationMinutes: optimization.value.pathDurationMinutes,
    estimatedWeightKg: optimization.value.estimatedWeightKg,
    estimatedVolumeLiter: optimization.value.estimatedVolumeLiter,
    loadRate: optimization.value.loadRate,
    pathSourceSummary: pathSourceSummary(optimization.value.segments || [])
  }
  const sourceGroup = selectedRoute.value?.inlinePoints ? selectedSavedGroup.value : null
  const sourceRouteId = selectedRoute.value?.sourceSavedRouteId
  if (sourceGroup && sourceRouteId != null) {
    return {
      mode: 'group',
      groupName: sourceGroup.groupName,
      folderId: sourceGroup.folderId || null,
      sourceType: 'SINGLE_OPTIMIZE',
      operationType: 'OPTIMIZE',
      parentGroupId: sourceGroup.id,
      rootGroupId: sourceGroup.rootGroupId || sourceGroup.id,
      versionNo: Math.max(1, Number(sourceGroup.versionNo || 1) + 1),
      unitId: sourceGroup.unitId,
      unitName: sourceGroup.unitName,
      originRouteId: sourceGroup.originRouteId,
      originRouteName: sourceGroup.originRouteName,
      planningStrategy: sourceGroup.planningStrategy,
      defaultDisplayMode: optimization.value.displayMode || 'DIRECT',
      routes: (sourceGroup.routes || []).map((item) => item.id === sourceRouteId
        ? routeSnapshotForSave({ ...route, parentRouteId: sourceRouteId, operationType: 'OPTIMIZE' })
        : routeSnapshotForSave({ ...item, parentRouteId: item.id, operationType: 'OPTIMIZE' })),
      summary: optimization.value,
      request: { sourceGroupId: sourceGroup.id, sourceRouteId, ...optimizeOptions }
    }
  }
  return {
    mode: 'route',
    sourceType: 'SINGLE_OPTIMIZE',
    unitId: selectedCompany.value?.id,
    unitName: selectedCompany.value?.depName,
    originRouteId: selectedRoute.value?.id,
    originRouteName: selectedRoute.value?.routeName,
    planningStrategy: optimizeOptions.useRoadPath ? 'ROAD_GLOBAL' : 'DIRECT_GROUP',
    defaultDisplayMode: optimization.value.displayMode || 'DIRECT',
    route,
    summary: optimization.value,
    request: { routeId: selectedRoute.value?.id, ...optimizeOptions }
  }
}

function buildMultiGroupSavePayload(sourceType) {
  const isSplit = sourceType === 'SPLIT'
  const company = isSplit ? selectedSplitCompany.value : selectedMultiCompany.value
  const originRoute = isSplit ? selectedSplitRoute.value : null
  const sourceGroup = isSplit && splitFromSavedRoute.value ? selectedSavedGroup.value : null
  const generatedRoutes = (multiOptimization.value?.routes || []).map((route) => routeSnapshotForSave({
    ...route,
    parentRouteId: sourceGroup ? originRoute?.id : route.parentRouteId,
    operationType: sourceType
  }))
  const inheritedRoutes = sourceGroup
    ? (sourceGroup.routes || [])
        .filter((route) => String(route.id) !== String(originRoute?.id))
        .map((route) => routeSnapshotForSave({ ...route, parentRouteId: route.id, operationType: sourceType }))
    : []
  const nextVersion = sourceGroup ? Math.max(1, Number(sourceGroup.versionNo || 1) + 1) : 1
  return {
    mode: 'group',
    groupName: sourceGroup?.groupName,
    folderId: sourceGroup?.folderId || null,
    sourceType,
    operationType: sourceGroup ? sourceType : undefined,
    parentGroupId: sourceGroup?.id,
    rootGroupId: sourceGroup?.rootGroupId || sourceGroup?.id,
    versionNo: nextVersion,
    unitId: company?.id,
    unitName: company?.depName,
    originRouteId: originRoute?.id,
    originRouteName: originRoute?.routeName,
    planningStrategy: multiOptimization.value?.planningStrategy || optimizeOptions.multiRouteStrategy,
    defaultDisplayMode: 'DIRECT',
    routes: [...inheritedRoutes, ...generatedRoutes],
    summary: multiOptimization.value,
    request: buildCurrentRouteRequestSnapshot()
  }
}

function buildSingleMultiRouteSavePayload(sourceType) {
  const payload = buildMultiGroupSavePayload(sourceType)
  payload.mode = 'route'
  payload.route = routeSnapshotForSave(selectedMultiRoute.value)
  payload.routeName = payload.route.routeName
  delete payload.routes
  return payload
}

function routeSnapshotForSave(route) {
  const snapshot = deepClone(route || {})
  snapshot.routeName = snapshot.routeName || ('第' + (snapshot.routeNo || 1) + '趟')
  snapshot.segments = routeDisplaySegments(route)
  if (route?.roadSegments?.length) snapshot.roadSegments = route.roadSegments
  snapshot.distance = routeDisplayDistance(route)
  snapshot.travelDurationMinutes = routeDisplayTravelDuration(route)
  snapshot.totalDurationMinutes = routeDisplayTotalDuration(route)
  snapshot.pathSourceSummary = pathSourceSummary(snapshot.segments || [])
  const defaultPointSource = route?.sourceType === 'IMPORT' ? 'ORIGINAL' : 'OPTIMIZED'
  snapshot.points = (snapshot.points || []).map((point, index) => ({
    ...point,
    originalOrder: point.originalOrder || point.order || point.pointOrder || index + 1,
    pointSource: point.pointSource || defaultPointSource,
    feedbackFlag: point.feedbackFlag || 0
  }))
  return snapshot
}

function buildCurrentRouteRequestSnapshot() {
  return {
    optimizeOptions: deepClone(optimizeOptions),
    dispatchMode: dispatchEnabled.value ? dispatchMode.value : 'USER_ORDER',
    vehicles: normalizedDispatchVehicles(),
    endSelectionMode: endAnchorMode.value,
    endCandidates: normalizedEndCandidates(),
    selectedFacilityIds: currentOptimizationFacilityIds.value
  }
}

function deepClone(value) {
  return JSON.parse(JSON.stringify(value || {}))
}

function sourceTypeLabel(value) {
  if (value === 'SINGLE_OPTIMIZE') return '单路线优化'
  if (value === 'SPLIT') return '路线拆分'
  if (value === 'MULTI') return '多路线生成'
  if (value === 'IMPORT') return '导入路线'
  if (value === 'EDIT') return '路线编辑'
  if (value === 'FLOW_ANALYSIS') return '流水归纳草案'
  return value || '-'
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

const flowAnalysisRunning = computed(() => ['QUEUED', 'RUNNING'].includes(flowAnalysisTask.status))

function resetFlowAnalysisResult() {
  flowAnalysisResult.value = null
  flowSelectedGroup.value = null
  flowShowCollectedOnly.value = false
  flowExpandedDays.value = new Set()
  flowForcedBoundaryIds.value = new Set()
  Object.assign(flowAnalysisTask, { taskId: '', status: 'IDLE', phase: 'PREPARE', message: '请选择公司和车辆', percent: 0, totalRecords: 0, loadedEvents: 0, elapsedMs: 0 })
}

async function loadFlowVehicles() {
  if (!flowAnalysisCompanyId.value) return
  flowAnalysisLoading.value = true
  try {
    const params = new URLSearchParams({ unitId: flowAnalysisCompanyId.value, startDate: flowAnalysisFilters.startDate, endDate: flowAnalysisFilters.endDate })
    flowAnalysisVehicles.value = await api(`/api/flow-analysis/vehicles?${params}`)
    if (!flowAnalysisVehicles.value.some((item) => String(item.carCode) === String(flowAnalysisCarCode.value))) flowAnalysisCarCode.value = ''
    resetFlowAnalysisResult()
  } finally {
    flowAnalysisLoading.value = false
  }
}

async function selectFlowAnalysisCompany(company) {
  flowAnalysisCompanyId.value = String(company.id)
  flowAnalysisCompanyKeyword.value = company.depName || String(company.id)
  flowAnalysisCarCode.value = ''
  flowAnalysisRouteId.value = ''
  await loadFlowVehicles()
}

function flowAnalysisJobName() {
  const job = flowAnalysisJobs.value.find((item) => String(item.routeId || '') === String(flowAnalysisRouteId.value || ''))
  return job?.routeName || '车辆流水归纳'
}

function applyFlowAnalysisTask(task) {
  Object.assign(flowAnalysisTask, task || {}, { percent: Number(task?.percent || 0) })
  if (task?.result) {
    flowAnalysisResult.value = task.result
    flowSelectedGroup.value = task.result.groups?.[0] || null
  }
}

async function startFlowAnalysis() {
  if (!flowAnalysisCompanyId.value || !flowAnalysisCarCode.value) return
  if (flowAnalysisPollTimer.value) clearInterval(flowAnalysisPollTimer.value)
  resetFlowAnalysisResult()
  Object.assign(flowAnalysisTask, { status: 'QUEUED', phase: 'PREPARE', message: '正在提交流水分析任务', percent: 0 })
  try {
    const response = await api('/api/flow-analysis/tasks', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ unitId: flowAnalysisCompanyId.value, carCode: flowAnalysisCarCode.value, routeId: flowAnalysisRouteId.value || null, startDate: flowAnalysisFilters.startDate, endDate: flowAnalysisFilters.endDate, jaccardThreshold: flowAnalysisThreshold.value })
    })
    applyFlowAnalysisTask(response)
    flowAnalysisPollTimer.value = setInterval(() => pollFlowAnalysis(response.taskId), 1000)
    await pollFlowAnalysis(response.taskId)
  } catch (error) {
    Object.assign(flowAnalysisTask, { status: 'FAILED', phase: 'FAILED', message: error?.message || '流水分析提交失败' })
  }
}

async function pollFlowAnalysis(taskId) {
  try {
    const task = await api(`/api/flow-analysis/tasks/${taskId}`)
    applyFlowAnalysisTask(task)
    if (['DONE', 'FAILED', 'CANCELLED', 'NOT_FOUND'].includes(task.status)) {
      clearInterval(flowAnalysisPollTimer.value)
      flowAnalysisPollTimer.value = null
    }
  } catch (error) {
    flowAnalysisTask.message = error.message
  }
}

async function cancelFlowAnalysis() {
  if (!flowAnalysisTask.taskId) return
  const task = await api(`/api/flow-analysis/tasks/${flowAnalysisTask.taskId}/cancel`, { method: 'POST' })
  applyFlowAnalysisTask(task)
  if (flowAnalysisPollTimer.value) clearInterval(flowAnalysisPollTimer.value)
  flowAnalysisPollTimer.value = null
}

function markFlowBoundary(trip) {
  const lastPoint = trip?.points?.[trip.points.length - 1]
  if (!lastPoint?.eventId) return
  const next = new Set(flowForcedBoundaryIds.value)
  next.add(Number(lastPoint.eventId))
  flowForcedBoundaryIds.value = next
}

async function rerunFlowAnalysisWithAdjustments() {
  if (!flowAnalysisTask.taskId) return
  const task = await api(`/api/flow-analysis/tasks/${flowAnalysisTask.taskId}/adjustments`, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ boundaryAfterEventIds: [...flowForcedBoundaryIds.value] })
  })
  applyFlowAnalysisTask(task)
  flowAnalysisPollTimer.value = setInterval(() => pollFlowAnalysis(task.taskId), 1000)
}

function selectFlowGroup(group) { flowSelectedGroup.value = group }
function toggleFlowDay(date) { const next = new Set(flowExpandedDays.value); if (next.has(date)) next.delete(date); else next.add(date); flowExpandedDays.value = next }

function formatFlowTime(value) { if (!value) return '-'; const text = String(value); return text.replace('T', ' ').replace(/(\.\d+)?([+-]\d\d:?\d\d|Z)?$/, '') }
function flowPointClass(point) { const type = point.finalMatchType === null || point.finalMatchType === undefined ? Number(point.matchType) : Number(point.finalMatchType); return { 'flow-point-collected': type === 0, 'flow-point-through': type === 1, 'flow-point-repeat-2': Number(point.visitCount || point.totalCount || 0) === 2, 'flow-point-repeat-3': Number(point.visitCount || point.totalCount || 0) >= 3 } }
function flowTripPointPhase(point, day) {
  if (Number(point.matchType) !== 1 || !point.facilityId) return 'none'
  const samePointEvents = []
  for (const trip of (day?.trips || [])) {
    for (const event of (trip.points || [])) {
      if (String(event.facilityId) === String(point.facilityId)) samePointEvents.push(event)
    }
  }
  samePointEvents.sort((a, b) => {
    const timeCompare = String(a.time || '').localeCompare(String(b.time || ''))
    return timeCompare || Number(a.eventId || 0) - Number(b.eventId || 0)
  })
  const currentIndex = samePointEvents.findIndex((event) => String(event.eventId) === String(point.eventId))
  if (currentIndex < 0) return samePointEvents.some((event) => Number(event.matchType) === 0) ? 'before' : 'none'
  const hasCollectedToday = samePointEvents.some((event) => Number(event.matchType) === 0)
  if (!hasCollectedToday) return 'none'
  return samePointEvents.slice(0, currentIndex).some((event) => Number(event.matchType) === 0) ? 'after' : 'before'
}
function flowTripPointClass(point, day) {
  const phase = flowTripPointPhase(point, day)
  return { ...flowPointClass(point), 'flow-point-through-before-collected': phase === 'before', 'flow-point-through-after-collected': phase === 'after' }
}
function flowPointDisplayLabel(point, day) {
  const phase = flowTripPointPhase(point, day)
  return phase === 'before' ? '收运前途经' : phase === 'after' ? '收运后途经' : (point.matchLabel || '未知')
}
function flowDailyPointClass(point) { return { 'flow-point-collected': Number(point.finalMatchType) === 0, 'flow-point-through': Number(point.finalMatchType) === 1, 'flow-point-repeat-2': Number(point.totalCount || 0) === 2, 'flow-point-repeat-3': Number(point.totalCount || 0) >= 3 } }
function flowPointOccurrenceLabel(point) { const index = Number(point.typeVisitIndex || 0); const total = Number(point.typeVisitTotal || 0); return index > 0 && total > 0 ? ` · 第${index}/${total}次` : '' }

function flowMapPoints(group) {
  const points = (group?.points || []).map((point, index) => ({ ...point, order: index + 1, role: 'MIDDLE' }))
  if (group?.typicalEnd) points.push({ ...group.typicalEnd, order: points.length + 1, role: 'END' })
  return points
}

function openSaveFlowAnalysis() {
  if (!flowAnalysisResult.value?.groups?.length) return
  const company = companies.value.find((item) => String(item.id) === String(flowAnalysisCompanyId.value))
  const routes = flowAnalysisResult.value.groups.map((group, index) => ({
    routeNo: index + 1,
    routeName: `${flowAnalysisJobName()} · 流水分堆${index + 1}`,
    vehicleName: flowAnalysisCarCode.value,
    tripNo: index + 1,
    points: flowMapPoints(group),
    distance: null,
    travelDurationMinutes: null,
    totalDurationMinutes: null,
    estimatedWeightKg: null,
    loadRate: null,
    sourceType: 'FLOW_ANALYSIS',
    flowGroup: group
  }))
  openSaveDialog('保存流水归纳草案', {
    mode: 'group', groupName: `${flowAnalysisJobName()} · 流水归纳草案`, sourceType: 'FLOW_ANALYSIS', operationType: 'ANALYZE', versionStatus: 'DRAFT',
    unitId: company?.id || flowAnalysisCompanyId.value, unitName: company?.depName, originRouteId: flowAnalysisRouteId.value || null, originRouteName: flowAnalysisJobName(),
    defaultDisplayMode: 'DIRECT', routes, summary: flowAnalysisResult.value,
    request: { analysisVersion: flowAnalysisResult.value.analysisVersion, unitId: flowAnalysisCompanyId.value, carCode: flowAnalysisCarCode.value, routeId: flowAnalysisRouteId.value || null, startDate: flowAnalysisFilters.startDate, endDate: flowAnalysisFilters.endDate, jaccardThreshold: flowAnalysisThreshold.value }
  })
}

function formatOdCacheElapsed(ms) { const totalSeconds = Math.floor(Math.max(0, Number(ms) || 0) / 1000); const hours = Math.floor(totalSeconds / 3600); const minutes = Math.floor((totalSeconds % 3600) / 60); const seconds = totalSeconds % 60; return hours > 0 ? `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}` : `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}` }
function stopOdCacheClock() { if (odCacheClockTimer.value) { clearInterval(odCacheClockTimer.value); odCacheClockTimer.value = null } }
function startOdCacheClock() { stopOdCacheClock(); odCacheClockNow.value = Date.now(); odCacheClockTimer.value = window.setInterval(() => { odCacheClockNow.value = Date.now() }, 1000) }
function resetOdCacheTask() { if (odCachePollTimer.value) { clearInterval(odCachePollTimer.value); odCachePollTimer.value = null }; stopOdCacheClock(); Object.assign(odCacheTask, { visible: false, taskId: '', status: 'IDLE', phase: 'PREPARE', message: '请选择公司和节点', percent: 0, totalPairs: 0, cachedPairs: 0, completedPairs: 0, successPairs: 0, failedPairs: 0, currentPair: '', failures: [], startedAt: 0, elapsedMs: 0, finishedAt: 0 }) }
function resetOdCacheSelection() { odCachePointIds.value = new Set(); odCacheParkingKeys.value = new Set(); odCacheFacilityKeys.value = new Set(); odCacheImportSummary.value = ''; resetOdCacheTask() }
async function selectOdCacheCompany(company) { odCacheCompany.value = company; resetOdCacheSelection(); await withLoading(async () => { const [points, anchors] = await Promise.all([api(`/api/companies/${company.id}/facilities`), api(`/api/companies/${company.id}/route-anchors`)]); odCachePoints.value = points || []; odCachePointIds.value = new Set(odCachePoints.value.map((point) => String(point.facilityId))); odCacheParkingOptions.value = (anchors?.parkingLots || []).map((point) => ({ ...point, key: `parking:${point.facilityId}` })); odCacheFacilityOptions.value = [...(anchors?.disposalSites || []), ...(anchors?.transferStations || [])].map((point) => ({ ...point, key: `facility:${point.facilityId}` })); }) }
function toggleOdCachePoint(id) { const next = new Set(odCachePointIds.value); const key = String(id); next.has(key) ? next.delete(key) : next.add(key); odCachePointIds.value = next; resetOdCacheTask() }
function selectAllOdCachePoints() { odCachePointIds.value = new Set(odCachePoints.value.map((point) => String(point.facilityId))); resetOdCacheTask() }
function clearOdCachePoints() { odCachePointIds.value = new Set(); resetOdCacheTask() }
function toggleOdCacheAnchor(key, anchor) { const isParking = key.startsWith('parking:'); const current = isParking ? new Set(odCacheParkingKeys.value) : new Set(odCacheFacilityKeys.value); current.has(key) ? current.delete(key) : current.add(key); if (isParking) odCacheParkingKeys.value = current; else odCacheFacilityKeys.value = current; resetOdCacheTask() }
function triggerOdCacheImport() { odCacheImportInput.value?.click() }
async function importOdCacheNames(event) { const file = event.target.files?.[0]; event.target.value = ''; if (!file || !odCachePoints.value.length) return; const form = new FormData(); form.append('file', file); await withLoading(async () => { const response = await fetch('/api/import/facility-names', { method: 'POST', body: form }); if (!response.ok) throw new Error(await response.text()); const result = await response.json(); const names = new Set((result.names || []).map(normalizeFacilityName).filter(Boolean)); const selected = new Set(); odCachePoints.value.forEach((point) => { if (names.has(normalizeFacilityName(point.facilityName))) selected.add(String(point.facilityId)) }); odCachePointIds.value = selected; odCacheShowSelectedOnly.value = true; odCacheImportSummary.value = `导入 ${names.size} 个名称，匹配并选中 ${selected.size} 个点位`; resetOdCacheTask() }) }
function applyOdCacheTask(task) { Object.assign(odCacheTask, { visible: true, ...task, percent: Number(task.percent || 0), failures: task.failures || [] }) }
async function startOdCacheTask() { if (odCachePayload.value.length < 2) { error.value = '至少选择两个有坐标的节点'; return } resetOdCacheTask(); odCacheTask.visible = true; odCacheTask.status = 'QUEUED'; odCacheTask.message = '正在提交缓存计算任务'; try { const response = await api('/api/od-cache/tasks', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ points: odCachePayload.value }) }); applyOdCacheTask(response); startOdCacheClock(); if (odCachePollTimer.value) clearInterval(odCachePollTimer.value); odCachePollTimer.value = setInterval(() => pollOdCacheTask(response.taskId), 1000); await pollOdCacheTask(response.taskId) } catch (e) { odCacheTask.status = 'FAILED'; odCacheTask.phase = 'FAILED'; odCacheTask.message = e?.message || '缓存计算任务提交失败'; odCacheTask.finishedAt = Date.now(); stopOdCacheClock(); error.value = odCacheTask.message } }
async function pollOdCacheTask(taskId) { try { const task = await api(`/api/od-cache/tasks/${taskId}`); applyOdCacheTask(task); if (['DONE', 'PARTIAL', 'FAILED', 'CANCELLED', 'NOT_FOUND'].includes(task.status)) { clearInterval(odCachePollTimer.value); odCachePollTimer.value = null; stopOdCacheClock() } } catch (e) { odCacheTask.message = e.message } }
async function stopOdCacheTask() { if (!odCacheTask.taskId) return; const task = await api(`/api/od-cache/tasks/${odCacheTask.taskId}/cancel`, { method: 'POST' }); applyOdCacheTask(task); if (odCachePollTimer.value) { clearInterval(odCachePollTimer.value); odCachePollTimer.value = null }; stopOdCacheClock() }
function odCacheStepClass(index) { const phase = odCacheTask.phase; const done = (index === 1 && ['CHECK_CACHE','CALCULATE','DONE'].includes(phase)) || (index === 2 && ['CALCULATE','DONE'].includes(phase)) || (index === 3 && phase === 'DONE') || (index === 4 && ['DONE','CANCELLED','FAILED'].includes(phase)); const active = (index === 1 && phase === 'PREPARE') || (index === 2 && phase === 'CHECK_CACHE') || (index === 3 && phase === 'CALCULATE') || (index === 4 && phase === 'DONE'); return { 'progress-step': true, done, active } }
async function reloadCurrent() {
  await loadRouteMapStatus()
  if (currentView.value === 'saved') {
    await loadSavedGroups()
    if (selectedSavedGroup.value) {
      await selectSavedGroup(selectedSavedGroup.value)
    }
    return
  }
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
  if (currentView.value === 'od-cache') {
    await loadCompanies()
    return
  }
  if (currentView.value === 'flow-analysis') {
    if (flowAnalysisCompanyId.value) await loadFlowVehicles()
    else await loadCompanies()
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

function coordinateDistance(a, b) {
  const longitudeA = Number(a?.longitude)
  const latitudeA = Number(a?.latitude)
  const longitudeB = Number(b?.longitude)
  const latitudeB = Number(b?.latitude)
  if (![longitudeA, latitudeA, longitudeB, latitudeB].every(Number.isFinite)) return 0
  const earthRadius = 6371000
  const lat1 = (latitudeA * Math.PI) / 180
  const lat2 = (latitudeB * Math.PI) / 180
  const deltaLat = ((latitudeB - latitudeA) * Math.PI) / 180
  const deltaLng = ((longitudeB - longitudeA) * Math.PI) / 180
  const sinLat = Math.sin(deltaLat / 2)
  const sinLng = Math.sin(deltaLng / 2)
  const h = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLng * sinLng
  return earthRadius * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h))
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
    // Normalize total minutes before splitting hours and minutes.
    const totalMinutes = Math.max(0, Math.round(n))
    const hours = Math.floor(totalMinutes / 60)
    const minutes = totalMinutes % 60
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
















