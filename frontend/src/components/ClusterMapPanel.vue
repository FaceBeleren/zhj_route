<template>
  <div class="route-map-panel cluster-map-panel" :class="{ expanded }">
    <div class="route-map-head">
      <div>
        <strong>区域地图</strong>
        <small>{{ mapStatus }}</small>
      </div>
      <div class="route-map-actions">
        <button v-if="!expanded" class="map-expand-button" type="button" @click="expanded = true">最大化</button>
        <button v-if="expanded" class="map-close-button" type="button" aria-label="关闭地图弹窗" @click="expanded = false">×</button>
      </div>
    </div>

    <div v-show="baiduReady" ref="mapEl" class="baidu-route-map cluster-baidu-map"></div>

    <div v-if="!baiduReady && plotPoints.length" class="cluster-map-fallback">
      <svg viewBox="0 0 100 100" role="img" aria-label="区域划分坐标预览">
        <rect x="0" y="0" width="100" height="100" rx="3" />
        <g v-for="group in normalizedGroups" :key="group.groupId">
          <circle
            v-for="point in group.points"
            :key="`${group.groupId}-${point.facilityId}`"
            :cx="projectX(point.longitude)"
            :cy="projectY(point.latitude)"
            :r="group.groupId === selectedGroupId ? 2.7 : 1.5"
            :fill="group.color"
            :opacity="group.groupId === selectedGroupId || !selectedGroupId ? 1 : 0.22"
            :stroke="group.groupId === selectedGroupId ? '#111827' : 'transparent'"
            stroke-width="0.8"
            @click="$emit('select-group', group.groupId)"
          />
        </g>
      </svg>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({
  groups: { type: Array, default: () => [] },
  selectedGroupId: { type: String, default: '' },
  stageLabel: { type: String, default: '聚类后' }
})

const emit = defineEmits(['select-group'])
const BAIDU_MAP_AK = import.meta.env.VITE_BAIDU_MAP_AK || ''
const BAIDU_SCRIPT_TIMEOUT_MS = 8000
let baiduMapLoader = null
let mapInstance = null

const mapEl = ref(null)
const baiduReady = ref(false)
const mapStatus = ref('未配置百度地图 AK 时使用坐标预览')
const expanded = ref(false)

const normalizedGroups = computed(() =>
  (props.groups || []).map((group) => ({
    groupId: String(group.groupId || ''),
    groupName: group.groupName || group.groupId || '未命名分堆',
    color: group.color || '#2563eb',
    points: normalizePoints(group.points || [])
  })).filter((group) => group.points.length > 0)
)
const plotPoints = computed(() => normalizedGroups.value.flatMap((group) => group.points))
const bounds = computed(() => {
  if (!plotPoints.value.length) return { minLng: 0, maxLng: 1, minLat: 0, maxLat: 1 }
  const lngs = plotPoints.value.map((point) => point.longitude)
  const lats = plotPoints.value.map((point) => point.latitude)
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

onMounted(() => renderMap())
onBeforeUnmount(() => {
  if (mapInstance?.clearOverlays) mapInstance.clearOverlays()
})
watch(() => [props.groups, props.selectedGroupId, props.stageLabel, expanded.value], () => renderMap(), { deep: true })

async function renderMap() {
  await nextTick()
  if (!mapEl.value || !plotPoints.value.length) {
    baiduReady.value = false
    mapStatus.value = '没有可展示的分堆坐标'
    return
  }
  if (!BAIDU_MAP_AK) {
    baiduReady.value = false
    mapStatus.value = `${props.stageLabel}坐标预览；未配置百度地图 AK`
    return
  }
  try {
    baiduReady.value = true
    mapStatus.value = '正在加载百度地图...'
    await nextTick()
    const BMap = await loadBaiduMap()
    drawBaiduMap(BMap)
    mapStatus.value = `${props.stageLabel}百度地图展示；点击点位可切换高亮分堆`
  } catch (error) {
    baiduReady.value = false
    mapStatus.value = `百度地图加载失败，已回退坐标预览：${error.message}`
  }
}

function drawBaiduMap(BMap) {
  if (mapInstance?.clearOverlays) mapInstance.clearOverlays()
  mapInstance = new BMap.Map(mapEl.value)
  const center = centerPoint(plotPoints.value)
  mapInstance.centerAndZoom(new BMap.Point(center.longitude, center.latitude), zoomLevel(plotPoints.value))
  mapInstance.enableScrollWheelZoom(true)
  addMapControls(BMap)
  normalizedGroups.value.forEach((group) => {
    const highlighted = !props.selectedGroupId || group.groupId === props.selectedGroupId
    group.points.forEach((point, index) => {
      const bdPoint = new BMap.Point(point.longitude, point.latitude)
      const circle = new BMap.Circle(bdPoint, highlighted ? 45 : 28, {
        strokeColor: highlighted ? '#111827' : group.color,
        strokeWeight: highlighted ? 3 : 1,
        strokeOpacity: highlighted ? 0.9 : 0.35,
        fillColor: group.color,
        fillOpacity: highlighted ? 0.78 : 0.2
      })
      circle.addEventListener('click', () => emit('select-group', group.groupId))
      mapInstance.addOverlay(circle)
      if (highlighted) {
        const label = new BMap.Label(String(index + 1), { position: bdPoint, offset: new BMap.Size(8, -22) })
        label.setStyle({ color: '#111827', border: '1px solid #cbd5e1', borderRadius: '4px', padding: '1px 4px', backgroundColor: '#ffffff', fontSize: '11px' })
        mapInstance.addOverlay(label)
      }
    })
  })
  fitMapViewport(BMap)
}

function addMapControls(BMap) {
  ;[() => new BMap.NavigationControl(), () => new BMap.ScaleControl(), () => new BMap.MapTypeControl()].forEach((createControl) => {
    try { mapInstance.addControl(createControl()) } catch (error) {}
  })
}

function fitMapViewport(BMap) {
  const viewport = plotPoints.value.map((point) => new BMap.Point(point.longitude, point.latitude))
  if (viewport.length > 1) mapInstance.setViewport(viewport, { margins: [48, 48, 48, 48] })
}

function loadBaiduMap() {
  if (window.BMap) return Promise.resolve(window.BMap)
  if (baiduMapLoader) return baiduMapLoader
  baiduMapLoader = new Promise((resolve, reject) => {
    const callback = `initClusterBaiduMap_${Date.now()}`
    const script = document.createElement('script')
    let settled = false
    const fail = (message) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      delete window[callback]
      baiduMapLoader = null
      script.remove()
      reject(new Error(message))
    }
    const timer = window.setTimeout(() => fail('脚本加载超时'), BAIDU_SCRIPT_TIMEOUT_MS)
    window[callback] = () => {
      if (settled) return
      if (!window.BMap) {
        fail('脚本加载后未发现 BMap')
        return
      }
      settled = true
      clearTimeout(timer)
      delete window[callback]
      resolve(window.BMap)
    }
    script.onerror = () => fail('脚本加载失败')
    script.src = `https://api.map.baidu.com/api?v=3.0&ak=${encodeURIComponent(BAIDU_MAP_AK)}&callback=${callback}`
    document.head.appendChild(script)
  })
  return baiduMapLoader
}

function normalizePoints(points) {
  return points.map((point) => ({
    facilityId: point.facilityId,
    facilityName: point.facilityName,
    longitude: Number(point.longitude),
    latitude: Number(point.latitude)
  })).filter((point) => Number.isFinite(point.longitude) && Number.isFinite(point.latitude))
}

function centerPoint(points) {
  const total = points.reduce((sum, point) => ({ longitude: sum.longitude + point.longitude, latitude: sum.latitude + point.latitude }), { longitude: 0, latitude: 0 })
  return { longitude: total.longitude / points.length, latitude: total.latitude / points.length }
}

function zoomLevel(points) {
  const lngs = points.map((point) => point.longitude)
  const lats = points.map((point) => point.latitude)
  const span = Math.max(Math.max(...lngs) - Math.min(...lngs), Math.max(...lats) - Math.min(...lats))
  if (span > 0.5) return 10
  if (span > 0.2) return 11
  if (span > 0.08) return 12
  if (span > 0.03) return 13
  if (span > 0.01) return 14
  return 15
}

function projectX(longitude) {
  return 5 + ((longitude - bounds.value.minLng) / (bounds.value.maxLng - bounds.value.minLng)) * 90
}

function projectY(latitude) {
  return 95 - ((latitude - bounds.value.minLat) / (bounds.value.maxLat - bounds.value.minLat)) * 90
}
</script>