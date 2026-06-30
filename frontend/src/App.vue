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
                <label class="route-mode-toggle route-preview-toggle">
                  <span>算路方式</span>
                  <input v-model="optimizeOptions.useRoadPath" type="checkbox" />
                  <i></i>
                  <small>{{ optimizeOptions.useRoadPath ? '实际路线距离' : '直线距离' }}</small>
                </label>
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
                  <span>{{ optimization.distanceMode === 'ROAD' ? '道路耗时' : '预计耗时' }} {{ formatDuration(optimization.pathDurationMinutes) }}</span>
                  <span>预计重量 {{ formatWeight(optimization.estimatedWeightKg) }}</span>
                  <span>预计体积 {{ formatVolume(optimization.estimatedVolumeLiter) }}</span>
                  <span>装载率 {{ formatLoadRate(optimization.loadRate) }}</span>
                  <span>路径 {{ pathSourceSummary(optimization.segments) }}</span>
                </div>
                <div class="sequence">
                  <span v-for="point in optimization.optimizedSequence" :key="point">{{ point }}</span>
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
              <label class="route-mode-toggle">
                <span>算路方式</span>
                <input v-model="optimizeOptions.useRoadPath" type="checkbox" />
                <i></i>
                <small>{{ optimizeOptions.useRoadPath ? '实际路线距离' : '直线距离' }}</small>
              </label>
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
                </select>
              </label>
              <label>
                终点筛选
                <select v-model="selectedEndAnchorKey" :disabled="endAnchorMode === 'manual'" @change="applySelectedEndAnchor">
                  <option value="">请选择</option>
                  <option v-for="anchor in endAnchorOptions" :key="anchor.key" :value="anchor.key">
                    {{ anchor.label }}
                  </option>
                </select>
              </label>
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
              <div v-else class="dispatch-table">
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
                  <input v-model.number="vehicle.tripCount" @input="clearMultiOptimization" type="number" min="1" step="1" />
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
                        {{ formatDistance(route.distance) }} · {{ formatDuration(route.durationMinutes) }} · 装载率 {{ formatLoadRate(route.loadRate) }} ·
                        {{ pathSourceSummary(route.segments) }}
                      </small>
                    </div>
                    <div class="sequence route-point-sequence">
                      <span v-for="point in route.points" :key="`${route.routeNo}-${point.order}-${point.facilityId}`">
                        {{ point.facilityName || point.facilityId }}
                      </span>
                    </div>
                  </article>
                </div>
                <RouteMapPanel
                  v-if="selectedMultiRoute"
                  :original-points="[]"
                  :optimized-points="selectedMultiRoute.points || []"
                  :optimized-segments="selectedMultiRoute.segments || []"
                  :show-original="false"
                  optimized-label="生成路线"
                />
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
import RouteMapPanel from './components/RouteMapPanel.vue'

const currentView = ref('workbench')
const companies = ref([])
const routes = ref([])
const records = ref([])
const planPoints = ref([])
const recordPoints = ref([])
const companyPoints = ref([])
const facilityImportInput = ref(null)
const facilityImportSummary = ref('')
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
const routeProgress = reactive({
  visible: false,
  activeIndex: -1,
  done: false,
  failed: false,
  message: '',
  steps: []
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
const selectedRoute = ref(null)
const selectedRecord = ref(null)
const selectedMultiRouteNo = ref(null)
const startAnchorMode = ref('parking')
const endAnchorMode = ref('facility')
const selectedStartAnchorKey = ref('')
const selectedEndAnchorKey = ref('')
const selectedCompanyPointIds = ref(new Set())
const dispatchVehicles = ref([])

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
  useRoadPath: false,
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
const selectedMultiRoute = computed(() => {
  if (!multiOptimization.value?.routes?.length) {
    return null
  }
  return multiOptimization.value.routes.find((route) => route.routeNo === selectedMultiRouteNo.value)
    || multiOptimization.value.routes[0]
})
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
const startAnchorOptions = computed(() => anchorOptionsByMode(startAnchorMode.value))
const endAnchorOptions = computed(() => anchorOptionsByMode(endAnchorMode.value))
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
  clearMultiOptimization()
}

function selectAllCompanyPoints() {
  selectedCompanyPointIds.value = new Set(companyPoints.value.map((point) => String(point.facilityId)))
  clearMultiOptimization()
}

function clearCompanyPointSelection() {
  selectedCompanyPointIds.value = new Set()
  clearMultiOptimization()
}

function selectVisibleCompanyPoints() {
  const next = new Set(selectedCompanyPointIds.value)
  companyPointVisibleList.value.forEach((point) => next.add(String(point.facilityId)))
  selectedCompanyPointIds.value = next
  clearMultiOptimization()
}

function unselectVisibleCompanyPoints() {
  const next = new Set(selectedCompanyPointIds.value)
  companyPointVisibleList.value.forEach((point) => next.delete(String(point.facilityId)))
  selectedCompanyPointIds.value = next
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
    applyImportedFacilityNames(result.names || [])
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
  applyAnchorToOptions(null, 'end')
}

function applySelectedStartAnchor() {
  applyAnchorToOptions(findAnchor(selectedStartAnchorKey.value, startAnchorMode.value), 'start')
}

function applySelectedEndAnchor() {
  applyAnchorToOptions(findAnchor(selectedEndAnchorKey.value, endAnchorMode.value), 'end')
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

async function generateCompanyRoutes() {
  if (!selectedMultiCompany.value) return
  startRouteProgress()
  await withLoading(async () => {
    try {
      multiOptimization.value = await api('/api/optimize/multi-preview', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          unitId: selectedMultiCompany.value.id,
          facilityIds: Array.from(selectedCompanyPointIds.value),
          dispatchMode: 'USER_ORDER',
          vehicles: normalizedDispatchVehicles(),
          ...optimizeOptions
        })
      })
      selectedMultiRouteNo.value = multiOptimization.value?.routes?.[0]?.routeNo || null
      finishRouteProgress()
    } catch (err) {
      failRouteProgress(err)
      throw err
    }
  })
}

function buildRouteProgressSteps() {
  const steps = [
    {
      key: 'prepare',
      title: '整理本批点位',
      detail: selectedCompanyPointCount.value + ' 个候选点，' + (dispatchEnabled.value ? dispatchTripCount.value + ' 趟排班' : '使用默认最大趟数')
    }
  ]
  if (optimizeOptions.useRoadPath) {
    steps.push(
      { key: 'cache-check', title: '调取 OD 缓存', detail: '批量读取本批点位已有道路点对' },
      { key: 'pair-resolve', title: '处理缺失点对', detail: '缓存未命中时按配置百度补算或直线回退' }
    )
  } else {
    steps.push({ key: 'direct-distance', title: '计算直线距离', detail: '使用点位经纬度距离进行快速规划' })
  }
  steps.push(
    { key: 'route-build', title: '生成多趟路线', detail: '按装载目标逐趟插入点位' },
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
  routeProgressTimer.value = window.setInterval(() => {
    if (!routeProgress.visible || routeProgress.done || routeProgress.failed) return
    if (routeProgress.activeIndex < routeProgress.steps.length - 2) {
      routeProgress.activeIndex += 1
      routeProgress.message = routeProgress.steps[routeProgress.activeIndex]?.title || '正在规划路线'
    } else {
      routeProgress.message = '后端仍在计算，请稍候'
    }
  }, 1400)
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

function resetRouteProgress() {
  stopRouteProgressTimer()
  routeProgress.visible = false
  routeProgress.activeIndex = -1
  routeProgress.done = false
  routeProgress.failed = false
  routeProgress.message = ''
  routeProgress.steps = []
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
    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `多路线生成结果-${toDateInput(new Date())}.xlsx`
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
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
  await loadRouteMapStatus()
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
  if (n >= 60) {
    const hours = Math.floor(n / 60)
    const minutes = Math.round(n % 60)
    return `${hours} h ${minutes} min`
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

function formatLoadRate(value) {
  const n = Number(value || 0)
  if (!n) return '-'
  return `${(n * 100).toFixed(1)}%`
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
