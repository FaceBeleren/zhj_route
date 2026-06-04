<template>
  <main class="app-shell">
    <header class="topbar">
      <div>
        <h1>路线优化工作台</h1>
        <p>公司路线、规划点位、流水点位与优化预览</p>
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

    <section class="summary-strip">
      <div>
        <span>公司</span>
        <strong>{{ companies.length }}</strong>
      </div>
      <div>
        <span>路线</span>
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
        <div class="panel-head">
          <h2>规划路线</h2>
          <span class="muted">{{ selectedCompany?.depName || '请选择公司' }}</span>
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
            <small>ID {{ route.id }}</small>
          </button>
        </div>
      </section>

      <section class="detail-stack">
        <section class="panel">
          <div class="panel-head">
            <h2>路线概览</h2>
            <button @click="previewOptimize" :disabled="!selectedRoute || loading">优化预览</button>
          </div>
          <div v-if="selectedRoute" class="route-overview">
            <div>
              <span>路线名称</span>
              <strong>{{ selectedRoute.routeName || selectedRoute.id }}</strong>
            </div>
            <div>
              <span>路线ID</span>
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
          <div v-else class="empty">选择一家公司和路线后查看详情</div>
        </section>

        <section class="panel split-panel">
          <div>
            <div class="panel-head compact">
              <h2>规划点位</h2>
            </div>
            <ol class="point-list">
              <li v-for="point in planPoints" :key="point.facilityId">
                <span>{{ point.facilityName || point.facilityId }}</span>
                <small>{{ point.facilityTypeName || '-' }}</small>
              </li>
            </ol>
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
              <div class="sequence">
                <span v-for="point in optimization.optimizedSequence" :key="point">{{ point }}</span>
              </div>
            </div>
            <div v-else class="empty">点击“优化预览”生成占位结果</div>
          </div>
        </section>
      </section>
    </section>

    <div v-if="error" class="toast error">{{ error }}</div>
    <div v-if="loading" class="toast">加载中...</div>
  </main>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'

const companies = ref([])
const routes = ref([])
const records = ref([])
const planPoints = ref([])
const recordPoints = ref([])
const optimization = ref(null)

const selectedCompany = ref(null)
const selectedRoute = ref(null)
const selectedRecord = ref(null)

const companyKeyword = ref('')
const loading = ref(false)
const error = ref('')

const today = new Date()
const start = new Date()
start.setDate(today.getDate() - 29)

const filters = reactive({
  startDate: toDateInput(start),
  endDate: toDateInput(today)
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
  selectedRoute.value = null
  selectedRecord.value = null
  routes.value = []
  records.value = []
  planPoints.value = []
  recordPoints.value = []
  optimization.value = null
  await withLoading(async () => {
    routes.value = await api(`/api/companies/${company.id}/routes`)
  })
}

async function selectRoute(route) {
  selectedRoute.value = route
  selectedRecord.value = null
  recordPoints.value = []
  optimization.value = null
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

async function previewOptimize() {
  if (!selectedRoute.value) return
  await withLoading(async () => {
    optimization.value = await api('/api/optimize/preview', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        routeId: selectedRoute.value.id,
        unitId: selectedCompany.value.id
      })
    })
  })
}

async function reloadCurrent() {
  if (selectedRoute.value) {
    await selectRoute(selectedRoute.value)
  } else if (selectedCompany.value) {
    await selectCompany(selectedCompany.value)
  } else {
    await loadCompanies()
  }
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
</script>
