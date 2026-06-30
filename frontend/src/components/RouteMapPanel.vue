<template>
  <div class="route-map-panel" :class="{ expanded: expanded }">
    <div class="route-map-head">
      <div>
        <strong>路线地图</strong>
        <small>{{ mapStatus }}</small>
      </div>
      <div class="route-map-actions">
        <label v-if="props.showOriginal" class="route-line-toggle original" :class="{ muted: !showOriginalLine }">
          <input v-model="showOriginalLine" type="checkbox" />
          <span class="line-swatch"></span>
          {{ originalLabel }}
        </label>
        <label class="route-line-toggle optimized" :class="{ muted: !showOptimizedLine }">
          <input v-model="showOptimizedLine" type="checkbox" />
          <span class="line-swatch"></span>
          {{ optimizedLabel }}
        </label>
        <button v-if="!expanded" class="map-expand-button" type="button" @click="expanded = true">最大化</button>
        <label class="plot-toggle">
          <input v-model="labelsVisible" type="checkbox" />
          序号
        </label>
        <button v-if="expanded" class="map-close-button" type="button" aria-label="关闭地图弹窗" @click="expanded = false">×</button>
      </div>
    </div>

    <div class="route-playback-bar">
      <div class="route-playback-main">
        <div class="route-playback-choice" aria-label="选择播放路线">
          <span>播放路线</span>
          <button
            v-if="props.showOriginal"
            class="route-choice-button original"
            :class="{ active: playbackRoute === 'original' }"
            type="button"
            @click="setPlaybackRoute('original')"
          >
            {{ originalLabel }}
          </button>
          <button
            class="route-choice-button optimized"
            :class="{ active: playbackRoute === 'optimized' }"
            type="button"
            @click="setPlaybackRoute('optimized')"
          >
            {{ optimizedLabel }}
          </button>
        </div>
        <button class="route-playback-button primary" type="button" :disabled="!canPlay" @click="togglePlayback">
          {{ playbackRunning ? '暂停' : '播放' }}
        </button>
        <button class="route-playback-button" type="button" :disabled="!canPlay" @click="resetPlayback">重置</button>
        <select v-model.number="playbackSpeed" class="route-playback-select compact">
          <option :value="0.5">0.5x</option>
          <option :value="1">1x</option>
          <option :value="2">2x</option>
          <option :value="4">4x</option>
        </select>
      </div>
      <div class="route-playback-progress">
        <span>{{ playbackLabel }}</span>
        <div class="route-progress-track">
          <div class="route-progress-fill" :style="{ width: playbackPercent + '%' }"></div>
        </div>
      </div>
    </div>

    <div v-show="baiduReady" ref="mapEl" class="baidu-route-map"></div>

    <div v-if="!baiduReady && routePlot" class="route-plot fallback-plot">
      <div class="plot-card wide">
        <div class="plot-title">坐标预览</div>
        <svg viewBox="0 0 100 100" role="img" aria-label="路线坐标预览叠加对比">
          <polyline
            v-if="showOriginalLine && routePlot.compare.originalLine"
            :points="routePlot.compare.originalLine"
            class="plot-line original"
          />
          <polyline
            v-if="showOptimizedLine && routePlot.compare.optimizedLine"
            :points="routePlot.compare.optimizedLine"
            class="plot-line optimized"
          />
          <circle
            v-if="fallbackPlaybackPoint"
            :cx="fallbackPlaybackPoint.x"
            :cy="fallbackPlaybackPoint.y"
            r="2.8"
            class="plot-vehicle"
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
        <div v-if="showOriginalLine" class="plot-card">
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
        <div v-if="showOptimizedLine" class="plot-card">
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
const labelsVisible = ref(true)
const showOriginalLine = ref(props.showOriginal)
const showOptimizedLine = ref(true)
const expanded = ref(false)
const playbackRoute = ref('optimized')
const playbackSpeed = ref(1)
const playbackRunning = ref(false)
const playbackDistance = ref(0)
let mapInstance = null
let playbackMarker = null
let playbackAnimationFrame = null
let playbackFrameTime = 0

const originalAllPoints = computed(() => (props.showOriginal ? normalizeRoutePoints(props.originalPoints) : []))
const optimizedAllPoints = computed(() => normalizeRoutePoints(props.optimizedPoints))
const originalLinePoints = computed(() => (showOriginalLine.value ? originalAllPoints.value : []))
const optimizedLinePoints = computed(() => (showOptimizedLine.value ? optimizedAllPoints.value : []))
const originalGeometryPoints = computed(() => pathCoordinatesFromSegments(props.originalSegments, originalLinePoints.value))
const optimizedGeometryPoints = computed(() => pathCoordinatesFromSegments(props.optimizedSegments, optimizedLinePoints.value))
const markerPoints = computed(() => uniquePoints([...originalAllPoints.value, ...optimizedAllPoints.value]))
const extentPoints = computed(() => uniquePoints([...markerPoints.value, ...originalGeometryPoints.value, ...optimizedGeometryPoints.value]))
const allPoints = computed(() => extentPoints.value)
const routePlot = computed(() => buildRoutePlot(originalAllPoints.value, optimizedAllPoints.value))
const playbackPoints = computed(() => {
  if (playbackRoute.value === 'original') {
    return originalGeometryPoints.value.length >= 2 ? originalGeometryPoints.value : originalAllPoints.value
  }
  return optimizedGeometryPoints.value.length >= 2 ? optimizedGeometryPoints.value : optimizedAllPoints.value
})
const playbackTotalDistance = computed(() => routeDistance(playbackPoints.value))
const canPlay = computed(() => playbackPoints.value.length >= 2 && playbackTotalDistance.value > 0)
const playbackPercent = computed(() => {
  if (!canPlay.value) return 0
  return Math.min(100, Math.round((playbackDistance.value / playbackTotalDistance.value) * 1000) / 10)
})
const playbackCurrentPoint = computed(() => pointAtDistance(playbackPoints.value, playbackDistance.value))
const fallbackPlaybackPoint = computed(() => {
  if (!routePlot.value || !playbackCurrentPoint.value) return null
  const bounds = boundsFor([...originalAllPoints.value, ...optimizedAllPoints.value])
  return projectPoint(playbackCurrentPoint.value, bounds)
})
const playbackLabel = computed(() => {
  if (!canPlay.value) return '暂无可播放轨迹'
  const distance = Math.round(playbackDistance.value)
  const total = Math.round(playbackTotalDistance.value)
  return '已播放 ' + distance + ' / ' + total + ' m'
})

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
  pausePlayback()
  if (mapInstance?.clearOverlays) {
    mapInstance.clearOverlays()
  }
})

watch(
  () => [props.originalPoints, props.optimizedPoints, props.originalSegments, props.optimizedSegments, labelsVisible.value, showOriginalLine.value, showOptimizedLine.value, expanded.value],
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

  if (showOriginalLine.value) {
    drawPolyline(BMap, originalGeometryPoints.value, '#d84f4f', 4, 0.8, 'dashed')
  }
  if (showOptimizedLine.value) {
    drawPolyline(BMap, optimizedGeometryPoints.value, '#1f6fca', 5, 0.9, 'solid')
  }
  markerPoints.value.forEach((point, index) => {
    const marker = new BMap.Marker(new BMap.Point(point.longitude, point.latitude))
    marker.setTitle(point.facilityName || String(point.facilityId || index + 1))
    mapInstance.addOverlay(marker)
    if (labelsVisible.value) {
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
    }
  })
  drawPlaybackMarker(BMap)
  fitMapViewport(BMap)
}

function drawPlaybackMarker(BMap) {
  playbackMarker = null
  const point = playbackCurrentPoint.value
  if (!point) return
  playbackMarker = new BMap.Marker(new BMap.Point(point.longitude, point.latitude))
  playbackMarker.setTitle('轨迹播放位置')
  const label = new BMap.Label('车', { offset: new BMap.Size(12, -22) })
  label.setStyle({
    color: '#ffffff',
    border: '0',
    borderRadius: '999px',
    padding: '3px 7px',
    backgroundColor: '#111827',
    fontSize: '12px',
    fontWeight: '600'
  })
  playbackMarker.setLabel(label)
  mapInstance.addOverlay(playbackMarker)
}

function updatePlaybackMarker() {
  if (!playbackMarker || !window.BMap || !playbackCurrentPoint.value) return
  playbackMarker.setPosition(new window.BMap.Point(playbackCurrentPoint.value.longitude, playbackCurrentPoint.value.latitude))
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

function setPlaybackRoute(route) {
  if (playbackRoute.value === route) return
  playbackRoute.value = route
  resetPlayback()
}
function togglePlayback() {
  if (playbackRunning.value) {
    pausePlayback()
    return
  }
  startPlayback()
}

function startPlayback() {
  if (!canPlay.value) return
  if (playbackDistance.value >= playbackTotalDistance.value) {
    playbackDistance.value = 0
  }
  if (playbackRoute.value === 'original') {
    showOriginalLine.value = true
  } else {
    showOptimizedLine.value = true
  }
  playbackRunning.value = true
  playbackFrameTime = performance.now()
  playbackAnimationFrame = requestAnimationFrame(stepPlayback)
}

function pausePlayback() {
  playbackRunning.value = false
  if (playbackAnimationFrame) {
    cancelAnimationFrame(playbackAnimationFrame)
    playbackAnimationFrame = null
  }
}

function resetPlayback() {
  pausePlayback()
  playbackDistance.value = 0
  updatePlaybackMarker()
}

function stepPlayback(now) {
  if (!playbackRunning.value) return
  const elapsed = Math.max(0, now - playbackFrameTime)
  playbackFrameTime = now
  const metersPerSecond = Math.max(playbackTotalDistance.value / 22, 30) * playbackSpeed.value
  playbackDistance.value = Math.min(playbackTotalDistance.value, playbackDistance.value + (elapsed / 1000) * metersPerSecond)
  updatePlaybackMarker()
  if (playbackDistance.value >= playbackTotalDistance.value) {
    pausePlayback()
    return
  }
  playbackAnimationFrame = requestAnimationFrame(stepPlayback)
}

function routeDistance(points) {
  let total = 0
  for (let index = 1; index < points.length; index += 1) {
    total += distanceBetween(points[index - 1], points[index])
  }
  return total
}

function pointAtDistance(points, distance) {
  if (points.length === 0) return null
  if (points.length === 1 || distance <= 0) return points[0]
  let remaining = distance
  for (let index = 1; index < points.length; index += 1) {
    const from = points[index - 1]
    const to = points[index]
    const segmentDistance = distanceBetween(from, to)
    if (remaining <= segmentDistance) {
      const ratio = segmentDistance === 0 ? 0 : remaining / segmentDistance
      return {
        longitude: from.longitude + (to.longitude - from.longitude) * ratio,
        latitude: from.latitude + (to.latitude - from.latitude) * ratio
      }
    }
    remaining -= segmentDistance
  }
  return points[points.length - 1]
}

function distanceBetween(a, b) {
  const earthRadius = 6371000
  const lat1 = toRadians(a.latitude)
  const lat2 = toRadians(b.latitude)
  const deltaLat = toRadians(b.latitude - a.latitude)
  const deltaLng = toRadians(b.longitude - a.longitude)
  const sinLat = Math.sin(deltaLat / 2)
  const sinLng = Math.sin(deltaLng / 2)
  const h = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLng * sinLng
  return earthRadius * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h))
}

function toRadians(value) {
  return (value * Math.PI) / 180
}

function pathSourceSummary() {
  const visibleSegments = showOptimizedLine.value ? props.optimizedSegments : props.originalSegments
  const sources = new Set((visibleSegments || []).map((segment) => segment.pathSource || 'DIRECT'))
  if (sources.size === 0) return '直线回退'
  return Array.from(sources).map(pathSourceLabel).join('/')
}

function pathSourceLabel(source) {
  if (source === 'OD_CACHE' || source === 'OD_PRELOAD') return 'OD缓存道路折线'
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
    const projectedPoint = projectPoint(point, bounds)
    const x = projectedPoint.x
    const y = projectedPoint.y
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

function projectPoint(point, bounds) {
  return {
    x: project(point.longitude, bounds.minLng, bounds.maxLng),
    y: 100 - project(point.latitude, bounds.minLat, bounds.maxLat)
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
