<template>
  <div class="route-map-panel">
    <div class="route-map-head">
      <div>
        <strong>路线地图</strong>
        <small>{{ mapStatus }}</small>
      </div>
      <div class="route-map-actions">
        <span v-if="showOriginal" class="legend original">{{ originalLabel }}</span>
        <span class="legend optimized">{{ optimizedLabel }}</span>
        <label class="plot-toggle">
          <input v-model="labelsVisible" type="checkbox" />
          序号
        </label>
      </div>
    </div>

    <div v-show="baiduReady" ref="mapEl" class="baidu-route-map"></div>

    <div v-if="!baiduReady && routePlot" class="route-plot fallback-plot">
      <div class="plot-card wide">
        <div class="plot-title">坐标预览</div>
        <svg viewBox="0 0 100 100" role="img" aria-label="路线坐标预览叠加对比">
          <polyline
            v-if="showOriginal && routePlot.compare.originalLine"
            :points="routePlot.compare.originalLine"
            class="plot-line original"
          />
          <polyline
            v-if="routePlot.compare.optimizedLine"
            :points="routePlot.compare.optimizedLine"
            class="plot-line optimized"
          />
          <g v-for="point in routePlot.compare.points" :key="point.key">
            <circle :cx="point.x" :cy="point.y" r="2.2">
              <title>{{ pointTitle(point) }}</title>
            </circle>
            <text v-if="point.labelAlways || labelsVisible" :x="point.x + 2.8" :y="point.y - 2">
              {{ point.label }}
            </text>
          </g>
        </svg>
      </div>
      <div class="plot-grid">
        <div v-if="showOriginal" class="plot-card">
          <div class="plot-title">{{ originalLabel }}顺序</div>
          <svg viewBox="0 0 100 100" role="img" aria-label="原路线坐标预览">
            <polyline :points="routePlot.original.line" class="plot-line original" />
            <g v-for="point in routePlot.original.points" :key="point.key">
              <circle :cx="point.x" :cy="point.y" r="2.2">
                <title>{{ pointTitle(point) }}</title>
              </circle>
              <text v-if="point.labelAlways || labelsVisible" :x="point.x + 2.8" :y="point.y - 2">
                {{ point.label }}
              </text>
            </g>
          </svg>
        </div>
        <div class="plot-card">
          <div class="plot-title">{{ optimizedLabel }}顺序</div>
          <svg viewBox="0 0 100 100" role="img" aria-label="优化后路线坐标预览">
            <polyline :points="routePlot.optimized.line" class="plot-line optimized" />
            <g v-for="point in routePlot.optimized.points" :key="point.key">
              <circle :cx="point.x" :cy="point.y" r="2.2">
                <title>{{ pointTitle(point) }}</title>
              </circle>
              <text v-if="point.labelAlways || labelsVisible" :x="point.x + 2.8" :y="point.y - 2">
                {{ point.label }}
              </text>
            </g>
          </svg>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({
  originalPoints: {
    type: Array,
    default: () => []
  },
  optimizedPoints: {
    type: Array,
    default: () => []
  },
  originalSegments: {
    type: Array,
    default: () => []
  },
  optimizedSegments: {
    type: Array,
    default: () => []
  },
  showOriginal: {
    type: Boolean,
    default: true
  },
  originalLabel: {
    type: String,
    default: '原路线'
  },
  optimizedLabel: {
    type: String,
    default: '优化后'
  }
})

const BAIDU_MAP_AK = import.meta.env.VITE_BAIDU_MAP_AK || ''
const BAIDU_SCRIPT_TIMEOUT_MS = 8000
let baiduMapLoader = null

const mapEl = ref(null)
const baiduReady = ref(false)
const mapStatus = ref('未配置百度地图 AK 时使用坐标预览')
const labelsVisible = ref(false)
let mapInstance = null

const originalLinePoints = computed(() => (props.showOriginal ? normalizeRoutePoints(props.originalPoints) : []))
const optimizedLinePoints = computed(() => normalizeRoutePoints(props.optimizedPoints))
const originalGeometryPoints = computed(() => pathCoordinatesFromSegments(props.originalSegments, originalLinePoints.value))
const optimizedGeometryPoints = computed(() => pathCoordinatesFromSegments(props.optimizedSegments, optimizedLinePoints.value))
const markerPoints = computed(() => uniquePoints([...originalLinePoints.value, ...optimizedLinePoints.value]))
const extentPoints = computed(() => uniquePoints([...markerPoints.value, ...originalGeometryPoints.value, ...optimizedGeometryPoints.value]))
const allPoints = computed(() => extentPoints.value)
const routePlot = computed(() => buildRoutePlot(originalLinePoints.value, optimizedLinePoints.value))

function uniquePoints(points) {
  const seen = new Set()
  return points.filter((point) => {
    const key = point.facilityId == null ? `${point.longitude},${point.latitude}` : String(point.facilityId)
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

onMounted(() => {
  renderMap()
})

onBeforeUnmount(() => {
  if (mapInstance?.clearOverlays) {
    mapInstance.clearOverlays()
  }
})

watch(
  () => [props.originalPoints, props.optimizedPoints, props.originalSegments, props.optimizedSegments],
  () => renderMap(),
  { deep: true }
)

async function renderMap() {
  await nextTick()
  if (!mapEl.value || allPoints.value.length === 0) {
    baiduReady.value = false
    mapStatus.value = '没有可展示的坐标'
    return
  }
  if (!BAIDU_MAP_AK) {
    baiduReady.value = false
    mapStatus.value = `未配置百度地图 AK，当前展示坐标预览；路径来源：${pathSourceSummary()}`
    return
  }

  try {
    baiduReady.value = true
    mapStatus.value = '正在加载百度地图...'
    await nextTick()
    const BMap = await loadBaiduMap()
    drawBaiduMap(BMap)
    mapStatus.value = `百度地图展示；路径来源：${pathSourceSummary()}`
  } catch (error) {
    baiduReady.value = false
    mapStatus.value = `百度地图加载失败，已回退坐标预览：${error.message}；路径来源：${pathSourceSummary()}`
  }
}

function drawBaiduMap(BMap) {
  if (mapInstance?.clearOverlays) {
    mapInstance.clearOverlays()
  }
  mapInstance = new BMap.Map(mapEl.value)
  const center = centerPoint(allPoints.value)
  mapInstance.centerAndZoom(new BMap.Point(center.longitude, center.latitude), zoomLevel(allPoints.value))
  mapInstance.enableScrollWheelZoom(true)
  addMapControls(BMap)

  if (props.showOriginal) {
    drawPolyline(BMap, originalGeometryPoints.value, '#d84f4f', 4, 0.8, 'dashed')
  }
  drawPolyline(BMap, optimizedGeometryPoints.value, '#1f6fca', 5, 0.9, 'solid')
  markerPoints.value.forEach((point, index) => {
    const marker = new BMap.Marker(new BMap.Point(point.longitude, point.latitude))
    marker.setTitle(point.facilityName || String(point.facilityId || index + 1))
    mapInstance.addOverlay(marker)
    const label = new BMap.Label(String(index + 1), {
      offset: new BMap.Size(12, -18)
    })
    label.setStyle({
      color: '#1f2937',
      border: '1px solid #cbd5e1',
      borderRadius: '4px',
      padding: '1px 4px',
      backgroundColor: '#ffffff',
      fontSize: '11px'
    })
    marker.setLabel(label)
  })
  fitMapViewport(BMap)
}

function addMapControls(BMap) {
  const controlConstructors = [
    () => new BMap.NavigationControl(),
    () => new BMap.ScaleControl(),
    () => new BMap.MapTypeControl(),
    () => new BMap.OverviewMapControl({ isOpen: true })
  ]
  controlConstructors.forEach((createControl) => {
    try {
      mapInstance.addControl(createControl())
    } catch (error) {
      // 部分百度地图控件在受限环境下可能不可用，不影响路线本身展示。
    }
  })
}

function fitMapViewport(BMap) {
  const viewport = allPoints.value.map((point) => new BMap.Point(point.longitude, point.latitude))
  if (viewport.length > 1) {
    mapInstance.setViewport(viewport, {
      margins: [48, 48, 48, 48]
    })
  }
}

function drawPolyline(BMap, points, color, weight, opacity, style) {
  if (points.length < 2) return
  const line = new BMap.Polyline(
    points.map((point) => new BMap.Point(point.longitude, point.latitude)),
    {
      strokeColor: color,
      strokeWeight: weight,
      strokeOpacity: opacity,
      strokeStyle: style
    }
  )
  mapInstance.addOverlay(line)
}

function loadBaiduMap() {
  if (window.BMap) {
    return Promise.resolve(window.BMap)
  }
  if (baiduMapLoader) {
    return baiduMapLoader
  }
  baiduMapLoader = new Promise((resolve, reject) => {
    const callback = `initBaiduMap_${Date.now()}`
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
    const timer = window.setTimeout(() => {
      fail('脚本加载超时')
    }, BAIDU_SCRIPT_TIMEOUT_MS)
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
    script.onerror = () => {
      fail('脚本加载失败')
    }
    script.src = `https://api.map.baidu.com/api?v=3.0&ak=${encodeURIComponent(BAIDU_MAP_AK)}&callback=${callback}`
    document.head.appendChild(script)
  })
  return baiduMapLoader
}

function normalizeRoutePoints(points) {
  return points
    .map((point, index) => ({
      facilityId: point.facilityId,
      facilityName: point.facilityName,
      longitude: Number(point.longitude),
      latitude: Number(point.latitude),
      label: String(index + 1)
    }))
    .filter((point) => Number.isFinite(point.longitude) && Number.isFinite(point.latitude))
}

function pathCoordinatesFromSegments(segments, fallbackPoints) {
  const path = []
  segments.forEach((segment) => {
    const segmentPath = normalizeRoutePoints(segment.path || [])
    segmentPath.forEach((point, index) => {
      const previous = path[path.length - 1]
      if (index === 0 && previous && sameCoordinate(previous, point)) {
        return
      }
      path.push(point)
    })
  })
  return path.length >= 2 ? path : fallbackPoints
}

function sameCoordinate(a, b) {
  return Math.abs(a.longitude - b.longitude) < 0.000001 && Math.abs(a.latitude - b.latitude) < 0.000001
}

function pathSourceSummary() {
  const sources = new Set((props.optimizedSegments || []).map((segment) => segment.pathSource || 'DIRECT'))
  if (sources.size === 0) return '直线回退'
  return Array.from(sources).map(pathSourceLabel).join('/')
}

function pathSourceLabel(source) {
  if (source === 'OD_CACHE') return 'OD缓存道路折线'
  if (source === 'BAIDU_ONLINE') return '百度在线道路折线'
  return '直线回退'
}

function centerPoint(points) {
  const total = points.reduce(
    (sum, point) => ({
      longitude: sum.longitude + point.longitude,
      latitude: sum.latitude + point.latitude
    }),
    { longitude: 0, latitude: 0 }
  )
  return {
    longitude: total.longitude / points.length,
    latitude: total.latitude / points.length
  }
}

function zoomLevel(points) {
  const longitudes = points.map((point) => point.longitude)
  const latitudes = points.map((point) => point.latitude)
  const span = Math.max(Math.max(...longitudes) - Math.min(...longitudes), Math.max(...latitudes) - Math.min(...latitudes))
  if (span > 0.5) return 10
  if (span > 0.2) return 11
  if (span > 0.08) return 12
  if (span > 0.03) return 13
  if (span > 0.01) return 14
  return 15
}

function buildRoutePlot(originalPoints, optimizedPoints) {
  if (originalPoints.length === 0 && optimizedPoints.length === 0) return null
  const bounds = boundsFor([...originalPoints, ...optimizedPoints])
  const original = projectRoute(originalPoints, bounds)
  const optimized = projectRoute(optimizedPoints, bounds)
  return {
    original,
    optimized,
    compare: {
      originalLine: original.line,
      optimizedLine: optimized.line,
      points: mergeProjectedPoints(original.points, optimized.points)
    }
  }
}

function boundsFor(points) {
  const longitudes = points.map((point) => point.longitude)
  const latitudes = points.map((point) => point.latitude)
  return {
    minLng: Math.min(...longitudes),
    maxLng: Math.max(...longitudes),
    minLat: Math.min(...latitudes),
    maxLat: Math.max(...latitudes)
  }
}

function projectRoute(points, bounds) {
  const projected = points.map((point, index) => {
    const x = project(point.longitude, bounds.minLng, bounds.maxLng)
    const y = 100 - project(point.latitude, bounds.minLat, bounds.maxLat)
    return {
      ...point,
      key: `${point.facilityId || index}-${index}`,
      x,
      y,
      label: String(index + 1),
      labelAlways: index === 0 || index === points.length - 1
    }
  })
  return {
    points: projected,
    line: projected.map((point) => `${point.x},${point.y}`).join(' ')
  }
}

function project(value, min, max) {
  if (max === min) return 50
  return 8 + ((value - min) / (max - min)) * 84
}

function mergeProjectedPoints(original, optimized) {
  const merged = new Map()
  ;[...original, ...optimized].forEach((point) => {
    const key = point.facilityId == null ? `${point.x}-${point.y}` : String(point.facilityId)
    if (!merged.has(key)) {
      merged.set(key, point)
    }
  })
  return Array.from(merged.values())
}

function pointTitle(point) {
  return `${point.label}. ${point.facilityName || point.facilityId || '未知点位'}`
}
</script>
