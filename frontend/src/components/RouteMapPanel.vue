<template>
  <div class="route-map-panel" :class="{ expanded: expanded }">
    <div class="route-map-head">
      <div>
        <strong>路线地图</strong>
        <small>{{ captureStatus || mapStatus }}</small>
      </div>
      <div class="route-map-actions">
        <label v-if="props.showOriginal" class="route-line-toggle original" :class="{ muted: !showOriginalLine }">
          <input v-model="showOriginalLine" type="checkbox" />
          <span class="line-swatch"></span>
          {{ originalLabel }}
        </label>
        <label class="route-line-toggle optimized" :class="{ muted: !showOptimizedLine }" :style="{ color: props.optimizedColor, borderColor: props.optimizedColor }">
          <input v-model="showOptimizedLine" type="checkbox" />
          <span class="line-swatch" :style="{ background: props.optimizedColor }"></span>
          {{ optimizedLabel }}
        </label>
        <button v-if="!expanded" class="map-expand-button" type="button" @click="expanded = true">最大化</button>
        <button type="button" class="map-expand-button" :disabled="captureSaving || !routePlot" title="保存当前视野中的地图为 PNG" @click="saveMapScreenshot">{{ captureSaving ? '保存中…' : '保存截图' }}</button>
        <label class="plot-toggle">
          <input v-model="labelsVisible" type="checkbox" />
          序号
        </label>
        <button v-if="expanded" class="map-close-button" type="button" aria-label="关闭地图弹窗" @click="expanded = false">×</button>
      </div>
    </div>

    <div v-if="props.showPointSemantics" class="route-point-legend" aria-label="候选点位颜色图例">
      <span><i class="normal"></i>普通收运</span>
      <span><i class="multi"></i>多次收运</span>
      <span><i class="shared"></i>共享</span>
      <span><i class="low"></i>低频</span>
    </div>
    <div v-if="overviewLayers.length" class="route-point-legend" aria-label="趟次颜色图例" @mouseleave="clearLegendFocus">
      <span v-for="layer in overviewLayers" :key="layer.key" :class="{ 'legend-focusable': props.focusLegendOnHover, 'legend-focused': focusedLayerKey === String(layer.key), 'legend-dimmed': focusedLayerKey !== null && focusedLayerKey !== String(layer.key) }" :tabindex="props.focusLegendOnHover ? 0 : undefined" @mouseenter="focusLegendLayer(layer)" @focus="focusLegendLayer(layer)" @blur="clearLegendFocus"><i :style="layer.gpsActual ? { background: 'transparent', borderTop: `3px dashed ${layer.color}` } : { background: layer.color }"></i>{{ layer.label }}</span>
    </div>

    <p v-if="pendingAllPoints.length" :style="{ color: props.pendingColor }">● 待插入时间窗点（{{ pendingAllPoints.length }}个，不参与连线）</p>
    <div v-if="props.showPlayback" class="route-playback-bar">
      <div class="route-playback-main">
        <div class="route-playback-choice" aria-label="选择播放路线">
          <span>播放路线</span>
          <template v-if="hasCustomPlaybackChoices">
            <button v-for="choice in props.playbackChoices" :key="choice.key" class="route-choice-button optimized"
              :class="{ active: playbackRoute === choice.key }" type="button" :disabled="!choice.paths?.length"
              @click="setPlaybackRoute(choice.key)">{{ choice.label }}</button>
          </template>
          <template v-else>
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
          </template>
        </div>
        <button class="route-playback-button primary" type="button" :disabled="!canPlay" @click="togglePlayback">
          {{ playbackRunning ? '暂停' : '播放' }}
        </button>
        <button class="route-playback-button" type="button" :disabled="!canPlay" @click="resetPlayback">重置</button>
        <select v-model.number="playbackSpeed" class="route-playback-select compact">
          <option :value="0.1">0.1x</option>
          <option :value="0.25">0.25x</option>
          <option :value="0.5">0.5x</option>
          <option :value="1">1x</option>
          <option :value="2">2x</option>
          <option :value="4">4x</option>
        </select>
      </div>
      <div class="route-playback-progress">
        <span class="route-playback-label">
          <span>{{ playbackLabel }}</span>
          <span
            class="route-playback-help"
            tabindex="0"
            aria-label="播放距离口径说明"
            :data-tooltip="hasCustomPlaybackChoices ? '播放进度按当前可见折线坐标测距；分段之间不补虚构连线，不等同车辆日报里程。' : '该数值由地图道路折线坐标二次测距，仅用于动画播放进度；正式路线距离以上方 OD 缓存或百度接口统计为准。'"
          >!</span>
        </span>
        <div class="route-progress-track">
          <div class="route-progress-fill" :style="{ width: playbackPercent + '%' }"></div>
          <input type="range" class="route-progress-input" min="0" :max="Math.max(1, Math.ceil(playbackTotalDistance))" :value="Math.round(playbackDistance)" :disabled="!canPlay" aria-label="拖动定位车辆位置" @pointerdown="beginSeek" @input="seekPlayback" @change="endSeek" @pointerup="endSeek" @pointercancel="endSeek" />
        </div>
      </div>
    </div>

    <div v-show="baiduReady" ref="mapEl" class="baidu-route-map"></div>

    <div v-if="!baiduReady && routePlot" class="route-plot fallback-plot">
      <div ref="fallbackMapEl" class="plot-card wide">
        <div class="plot-title">坐标预览</div>
        <svg :viewBox="fallbackFocusViewBox" role="img" aria-label="路线坐标预览叠加对比">
          <polyline
            v-if="showOriginalLine && routePlot.compare.originalLine"
            :points="routePlot.compare.originalLine"
            class="plot-line original"
          />
          <polyline
            v-if="showOptimizedLine && !overviewLayers.length && routePlot.compare.optimizedLine"
            :points="routePlot.compare.optimizedLine"
            class="plot-line optimized"
            :style="{ stroke: props.optimizedColor }"
          />
          <polyline v-for="layer in fallbackOverviewLayers" v-if="showOptimizedLine" :key="`overview-${layer.key}`" :points="layer.line" class="plot-line optimized" :style="{ stroke: layer.color, strokeDasharray: layer.gpsActual ? '8 5' : undefined, strokeWidth: focusedLayerKey === String(layer.key) ? 2.3 : undefined, opacity: focusedLayerKey === null || focusedLayerKey === String(layer.key) ? 1 : 0.14 }" />
          <polyline v-for="(line, index) in fallbackHighlightLines" v-if="focusedLayerKey === null" :key="`highlight-${index}`" :points="line" class="plot-line optimized" style="stroke:#8b5cf6;stroke-width:1.5" />
          <text v-if="fallbackPlaybackPoint && hasCustomPlaybackChoices" :x="fallbackPlaybackPoint.x" :y="fallbackPlaybackPoint.y" text-anchor="middle" dominant-baseline="central" font-size="6">🚚</text>
          <circle
            v-else-if="fallbackPlaybackPoint"
            :cx="fallbackPlaybackPoint.x"
            :cy="fallbackPlaybackPoint.y"
            r="2.8"
            class="plot-vehicle"
          />
          <g v-for="point in routePlot.pending.points" :key="`pending-${point.key}`">
            <circle :cx="point.x" :cy="point.y" r="2.5" :style="{ fill: props.pendingColor, stroke: '#fff' }"><title>待插入：{{ pointTitle(point) }}</title></circle>
            <text :x="point.x + 2.8" :y="point.y - 2" :style="{ fill: props.pendingColor }">待{{ point.label }}</text>
          </g>
          <g v-for="point in routePlot.compare.points" :key="point.key">
            <circle :cx="point.x" :cy="point.y" r="2.2" :style="fallbackMarkerStyle(point, props.showOriginal ? '#475569' : point.routeColor || props.markerColor)">
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
              <circle :cx="point.x" :cy="point.y" r="2.2" class="plot-point-original" :style="fallbackMarkerStyle(point, '#dc2626')">
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
          <svg :viewBox="fallbackFocusViewBox" role="img" aria-label="优化后路线坐标预览">
            <polyline v-if="!overviewLayers.length" :points="routePlot.optimized.line" class="plot-line optimized" :style="{ stroke: props.optimizedColor }" />
            <polyline v-for="layer in fallbackOverviewLayers" v-if="showOptimizedLine" :key="`overview-detail-${layer.key}`" :points="layer.line" class="plot-line optimized" :style="{ stroke: layer.color, strokeDasharray: layer.gpsActual ? '8 5' : undefined, strokeWidth: focusedLayerKey === String(layer.key) ? 2.3 : undefined, opacity: focusedLayerKey === null || focusedLayerKey === String(layer.key) ? 1 : 0.14 }" />
            <polyline v-for="(line, index) in fallbackHighlightLines" v-if="focusedLayerKey === null" :key="`optimized-highlight-${index}`" :points="line" class="plot-line optimized" style="stroke:#8b5cf6;stroke-width:1.5" />
            <g v-for="point in routePlot.optimized.points" :key="point.key">
              <circle :cx="point.x" :cy="point.y" r="2.2" class="plot-point-optimized" :style="fallbackMarkerStyle(point, point.routeColor || props.markerColor)">
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
import { distanceAtSlider, playbackMetersPerSecond, playbackPathsDistance, playbackPointAtDistance, wheelZoomTarget } from '../routeMapMotion.js'
import { drawProjectedRoute, mapCaptureCrop, mapScreenshotName } from '../mapScreenshot.js'

const props = defineProps({
  viewLink: { type: Object, default: null },
  changedPointColor: { type: String, default: '#8b5cf6' },
  optimizedColor: { type: String, default: '#1f6fca' },
  markerColor: { type: String, default: '#16a34a' },
  pendingPoints: { type: Array, default: () => [] },
  pendingColor: { type: String, default: '#8b5cf6' },
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
  routeLayers: { type: Array, default: () => [] },
  focusLegendOnHover: { type: Boolean, default: false },
  playbackChoices: { type: Array, default: () => [] },
  optimizedMarkerPoints: { type: Array, default: null },
  showPlayback: { type: Boolean, default: true },
  highlightSegments: { type: Array, default: () => [] },
  optimizedFacilityIds: {
    type: Array,
    default: null
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
  },
  showPointSemantics: {
    type: Boolean,
    default: false
  }
})

const BAIDU_MAP_AK = import.meta.env.VITE_BAIDU_MAP_AK || ''
const BAIDU_SCRIPT_TIMEOUT_MS = 8000
let baiduMapLoader = null

const mapEl = ref(null)
const fallbackMapEl = ref(null)
const baiduReady = ref(false)
const mapStatus = ref('未配置百度地图 AK 时使用坐标预览')
const captureSaving = ref(false)
const captureStatus = ref('')
let captureStatusTimer = null
const labelsVisible = ref(true)
const showOriginalLine = ref(props.showOriginal)
const showOptimizedLine = ref(true)
const expanded = ref(false)
const focusedLayerKey = ref(null)
let savedLegendViewport = null
const playbackRoute = ref('optimized')
const playbackSpeed = ref(props.playbackChoices.length ? 0.1 : 0.5)
const playbackRunning = ref(false)
const playbackDistance = ref(0)
let mapInstance = null
let unlinkView = null
let mapViewportFitted = false
let mapControlsAdded = false
let playbackMarker = null
let playbackAnimationFrame = null
let playbackFrameTime = 0
let lastWheelZoomAt = -Infinity
let seeking = false
let resumeAfterSeek = false

const originalAllPoints = computed(() => (props.showOriginal ? normalizeRoutePoints(props.originalPoints) : []))
const optimizedAllPoints = computed(() => normalizeRoutePoints(props.optimizedPoints))
const optimizedMarkerAllPoints = computed(() => props.optimizedMarkerPoints == null ? optimizedAllPoints.value : normalizeRoutePoints(props.optimizedMarkerPoints))
const overviewLayers = computed(() => props.routeLayers.map((layer, index) => {
  const points = normalizeRoutePoints(layer.points || [])
  const segments = layer.segments || []
  return { key: layer.key ?? index, label: layer.label || `第${index + 1}趟`, color: layer.color || '#1f6fca', points, segments,
    gpsActual: Boolean(layer.gpsActual),
    geometry: pathCoordinatesFromSegments(segments, points) }
}))
const focusedLayer = computed(() => overviewLayers.value.find(layer => String(layer.key) === focusedLayerKey.value) || null)
const fallbackFocusViewBox = computed(() => {
  if (!focusedLayer.value?.geometry?.length || !routePlot.value) return '0 0 100 100'
  const bounds = boundsFor([...originalAllPoints.value, ...optimizedAllPoints.value, ...pendingAllPoints.value])
  const points = focusedLayer.value.geometry.map(point => projectPoint(point, bounds))
  const xs = points.map(point => point.x), ys = points.map(point => point.y)
  const minX = Math.min(...xs), maxX = Math.max(...xs), minY = Math.min(...ys), maxY = Math.max(...ys)
  const padding = 6
  return `${minX - padding} ${minY - padding} ${Math.max(16, maxX - minX + padding * 2)} ${Math.max(16, maxY - minY + padding * 2)}`
})
const fallbackOverviewLayers = computed(() => {
  if (!routePlot.value) return []
  const bounds = boundsFor([...originalAllPoints.value, ...optimizedAllPoints.value, ...pendingAllPoints.value])
  return overviewLayers.value.map(layer => ({ ...layer, line: projectRoute(layer.geometry, bounds).line }))
})
const pendingAllPoints = computed(() => normalizeRoutePoints(props.pendingPoints))
const originalLinePoints = computed(() => (showOriginalLine.value ? originalAllPoints.value : []))
const optimizedLinePoints = computed(() => (showOptimizedLine.value ? optimizedAllPoints.value : []))
const originalGeometryPoints = computed(() => pathCoordinatesFromSegments(props.originalSegments, originalLinePoints.value))
const optimizedGeometryPoints = computed(() => pathCoordinatesFromSegments(props.optimizedSegments, optimizedLinePoints.value))
const optimizedFacilityIdSet = computed(() => new Set((props.optimizedFacilityIds || []).map((id) => String(id))))
const markerPoints = computed(() => {
  const optimizedActive = showOptimizedLine.value && optimizedAllPoints.value.length && (playbackRoute.value === 'optimized' || !showOriginalLine.value)
  if (optimizedActive && props.focusLegendOnHover && focusedLayer.value) return focusedLayer.value.points
  return optimizedActive ? optimizedMarkerAllPoints.value : originalAllPoints.value
})
const extentPoints = computed(() => uniquePoints([...markerPoints.value, ...originalGeometryPoints.value, ...optimizedGeometryPoints.value, ...overviewLayers.value.flatMap(layer => layer.geometry), ...pendingAllPoints.value]))
const allPoints = computed(() => extentPoints.value)
const routePlot = computed(() => buildRoutePlot(originalAllPoints.value, optimizedMarkerAllPoints.value, pendingAllPoints.value))
const fallbackHighlightLines = computed(() => {
  if (!showOptimizedLine.value || !routePlot.value) return []
  const bounds = boundsFor([...originalAllPoints.value, ...optimizedAllPoints.value, ...pendingAllPoints.value])
  return props.highlightSegments.map(segment => projectRoute(normalizeRoutePoints(segment.path || []), bounds).line).filter(Boolean)
})
const playbackPoints = computed(() => {
  if (playbackRoute.value === 'original') {
    return originalGeometryPoints.value.length >= 2 ? originalGeometryPoints.value : originalAllPoints.value
  }
  return optimizedGeometryPoints.value.length >= 2 ? optimizedGeometryPoints.value : optimizedAllPoints.value
})
const hasCustomPlaybackChoices = computed(() => props.playbackChoices.length > 0)
const playbackPaths = computed(() => hasCustomPlaybackChoices.value
  ? (props.playbackChoices.find(choice => choice.key === playbackRoute.value)?.paths || []).map(normalizeRoutePoints)
  : [playbackPoints.value])
const playbackTotalDistance = computed(() => playbackPathsDistance(playbackPaths.value))
const canPlay = computed(() => (hasCustomPlaybackChoices.value || !overviewLayers.value.length) && playbackTotalDistance.value > 0)
const playbackPercent = computed(() => {
  if (!canPlay.value) return 0
  return Math.min(100, Math.round((playbackDistance.value / playbackTotalDistance.value) * 1000) / 10)
})
const playbackCurrentPoint = computed(() => playbackPointAtDistance(playbackPaths.value, playbackDistance.value))
const fallbackPlaybackPoint = computed(() => {
  if (!routePlot.value || !playbackCurrentPoint.value) return null
  const bounds = boundsFor([...originalAllPoints.value, ...optimizedAllPoints.value, ...pendingAllPoints.value])
  return projectPoint(playbackCurrentPoint.value, bounds)
})
const playbackLabel = computed(() => {
  if (!canPlay.value) return '暂无可播放轨迹'
  const distance = Math.round(playbackDistance.value)
  const total = Math.round(playbackTotalDistance.value)
  return '已播放 ' + distance + ' / ' + total + ' m'
})

function focusLegendLayer(layer) {
  if (!props.focusLegendOnHover || focusedLayerKey.value === String(layer.key)) return
  if (!savedLegendViewport && mapInstance) savedLegendViewport = { center: mapInstance.getCenter(), zoom: mapInstance.getZoom() }
  focusedLayerKey.value = String(layer.key)
  if (mapInstance && window.BMap && layer.geometry.length >= 2) {
    mapInstance.setViewport(layer.geometry.map(point => new window.BMap.Point(point.longitude, point.latitude)), { margins: [55, 55, 55, 55] })
  }
}
function clearLegendFocus() {
  if (!props.focusLegendOnHover || focusedLayerKey.value === null) return
  focusedLayerKey.value = null
  if (mapInstance && savedLegendViewport) mapInstance.centerAndZoom(savedLegendViewport.center, savedLegendViewport.zoom)
  savedLegendViewport = null
}

function uniquePoints(points) {
  const seen = new Set()
  return points.filter((point) => {
    const key = point.facilityId == null ? `${point.longitude},${point.latitude}` : String(point.facilityId)
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

function verifyTileCanBeCaptured(src) {
  return new Promise((resolve, reject) => {
    const image = new Image()
    const timer = setTimeout(() => reject(new Error('百度底图加载超时，暂无法保存完整截图')), 10000)
    image.crossOrigin = 'anonymous'
    image.onload = () => {
      clearTimeout(timer)
      try {
        const canvas = document.createElement('canvas')
        canvas.width = canvas.height = 1
        canvas.getContext('2d').drawImage(image, 0, 0)
        canvas.toDataURL('image/png')
        resolve()
      } catch {
        reject(new Error('百度底图不允许页面直接截图，请通过 HTTPS 打开后使用标签页截图'))
      }
    }
    image.onerror = () => {
      clearTimeout(timer)
      reject(new Error('百度底图无法完整加载，暂不保存缺少底图的截图'))
    }
    image.src = src
  })
}

async function captureMapElement(target) {
  const { default: html2canvas } = await import('html2canvas')
  if (baiduReady.value) {
    const tiles = [...target.querySelectorAll('img')].filter(image => image.src.includes('/tile/') && image.complete && image.naturalWidth > 0)
    if (!tiles.length) throw new Error('百度底图尚未加载完成，请稍后再保存')
    const tilesByHost = new Map(tiles.map(image => [new URL(image.src).host, image.src]))
    await Promise.all([...tilesByHost.values()].map(verifyTileCanBeCaptured))
  }
  const captured = await html2canvas(target, {
    backgroundColor: '#ffffff',
    useCORS: true,
    allowTaint: false,
    scale: Math.min(window.devicePixelRatio || 1, 2),
    imageTimeout: 15000,
    logging: false
  })
  const canvas = document.createElement('canvas')
  canvas.width = captured.width
  canvas.height = captured.height
  canvas.getContext('2d').drawImage(captured, 0, 0)
  if (baiduReady.value) drawCapturedRouteOverlays(canvas, target)
  return canvas
}

function drawCapturedRouteOverlays(canvas, target) {
  const context = canvas.getContext('2d')
  if (!context || !mapInstance || !window.BMap) throw new Error('无法绘制截图中的路线')
  const targetRect = target.getBoundingClientRect()
  const scaleX = canvas.width / targetRect.width
  const scaleY = canvas.height / targetRect.height
  const project = point => mapInstance.pointToPixel(new window.BMap.Point(point.longitude, point.latitude))
  context.save()
  context.scale(scaleX, scaleY)
  if (showOriginalLine.value) {
    drawProjectedRoute(context, originalGeometryPoints.value, project, { color: '#d84f4f', width: 4, opacity: 0.8, dashed: true })
  }
  if (showOptimizedLine.value) {
    if (overviewLayers.value.length) overviewLayers.value.forEach(layer => drawProjectedRoute(context, layer.geometry, project, { color: layer.color, width: focusedLayerKey.value === String(layer.key) ? 7 : layer.gpsActual ? 3 : 5, opacity: focusedLayerKey.value === null || focusedLayerKey.value === String(layer.key) ? 0.9 : 0.14, dashed: layer.gpsActual }))
    else drawProjectedRoute(context, optimizedGeometryPoints.value, project, { color: props.optimizedColor, width: 5, opacity: 0.9 })
    if (focusedLayerKey.value === null) props.highlightSegments.forEach(segment => {
      drawProjectedRoute(context, normalizeRoutePoints(segment.path || []), project, { color: '#8b5cf6', width: 7, opacity: 0.95 })
    })
  }
  context.restore()
}

async function saveMapScreenshot() {
  if (captureSaving.value) return
  clearTimeout(captureStatusTimer)
  let stream
  let video
  captureSaving.value = true
  captureStatus.value = ''
  try {
    const target = baiduReady.value ? mapEl.value : fallbackMapEl.value
    if (!target) throw new Error('地图尚未显示，无法保存截图')
    let canvas
    if (navigator.mediaDevices?.getDisplayMedia) {
      mapCaptureCrop(target.getBoundingClientRect(), window.innerWidth, window.innerHeight, window.innerWidth, window.innerHeight)
      captureStatus.value = '请在浏览器弹窗中选择“当前标签页”'
      stream = await navigator.mediaDevices.getDisplayMedia({ video: { displaySurface: 'browser' }, audio: false, preferCurrentTab: true, selfBrowserSurface: 'include' })
      const track = stream.getVideoTracks()[0]
      if (track?.getSettings().displaySurface !== 'browser') throw new Error('请选择“当前标签页”，不要选择窗口或整个屏幕')
      video = document.createElement('video')
      video.muted = true
      video.playsInline = true
      video.srcObject = stream
      await video.play()
      if (typeof video.requestVideoFrameCallback === 'function') {
        await new Promise((resolve, reject) => {
          const timeout = setTimeout(() => reject(new Error('等待地图画面超时，请重试')), 5000)
          video.requestVideoFrameCallback(() => { clearTimeout(timeout); resolve() })
        })
      }
      const crop = mapCaptureCrop(target.getBoundingClientRect(), window.innerWidth, window.innerHeight, video.videoWidth, video.videoHeight)
      canvas = document.createElement('canvas')
      canvas.width = crop.width
      canvas.height = crop.height
      const context = canvas.getContext('2d')
      if (!context) throw new Error('无法创建截图画布')
      context.drawImage(video, crop.x, crop.y, crop.width, crop.height, 0, 0, crop.width, crop.height)
    } else {
      captureStatus.value = '正在生成地图截图…'
      canvas = await captureMapElement(target)
    }
    const blob = await new Promise((resolve, reject) => canvas.toBlob(value => value ? resolve(value) : reject(new Error('无法生成 PNG 图片')), 'image/png'))
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = mapScreenshotName()
    document.body.appendChild(link)
    link.click()
    link.remove()
    setTimeout(() => URL.revokeObjectURL(url), 60000)
    captureStatus.value = '截图已保存为 PNG'
  } catch (error) {
    captureStatus.value = error.name === 'NotAllowedError' || error.name === 'AbortError' ? '已取消截图' : `保存截图失败：${error.message || String(error)}`
  } finally {
    stream?.getTracks().forEach(track => track.stop())
    if (video) video.srcObject = null
    captureSaving.value = false
    captureStatusTimer = setTimeout(() => { captureStatus.value = '' }, 6000)
  }
}

onMounted(() => {
  renderMap()
})

onBeforeUnmount(() => {
  clearTimeout(captureStatusTimer)
  unlinkView?.()
  pausePlayback()
  mapEl.value?.removeEventListener('wheel', handleMapWheel)
  if (mapInstance?.clearOverlays) {
    mapInstance.clearOverlays()
  }
})

watch(
  () => [props.originalPoints, props.optimizedPoints, props.optimizedMarkerPoints, props.originalSegments, props.optimizedSegments, props.routeLayers, props.highlightSegments, props.optimizedFacilityIds, props.optimizedColor, props.markerColor, props.pendingPoints, props.pendingColor, labelsVisible.value, showOriginalLine.value, showOptimizedLine.value, playbackRoute.value, expanded.value],
  () => renderMap(),
  { deep: true }
)
watch(() => props.playbackChoices, () => { if (hasCustomPlaybackChoices.value) resetPlayback() }, { deep: true })
watch(focusedLayerKey, () => { if (baiduReady.value) renderMap() })

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
  if (!mapInstance) {
    mapInstance = new BMap.Map(mapEl.value)
    const center = centerPoint(allPoints.value)
    mapInstance.centerAndZoom(new BMap.Point(center.longitude, center.latitude), zoomLevel(allPoints.value))
    mapInstance.disableScrollWheelZoom()
    mapEl.value.addEventListener('wheel', handleMapWheel, { passive: false })
    addMapControls(BMap)
  } else if (mapInstance.clearOverlays) {
    mapInstance.clearOverlays()
    if (typeof mapInstance.checkResize === 'function') {
      mapInstance.checkResize()
    }
  }

  if (showOriginalLine.value) {
    drawPolyline(BMap, originalGeometryPoints.value, '#d84f4f', 4, 0.8, 'dashed')
  }
  if (showOptimizedLine.value) {
    if (overviewLayers.value.length) overviewLayers.value.forEach(layer => drawPolyline(BMap, layer.geometry, layer.color, focusedLayerKey.value === String(layer.key) ? 7 : layer.gpsActual ? 3 : 5, focusedLayerKey.value === null || focusedLayerKey.value === String(layer.key) ? 0.9 : 0.14, layer.gpsActual ? 'dashed' : 'solid'))
    else drawPolyline(BMap, optimizedGeometryPoints.value, props.optimizedColor, 5, 0.9, 'solid')
    if (focusedLayerKey.value === null) props.highlightSegments.forEach(segment => drawPolyline(BMap, normalizeRoutePoints(segment.path || []), '#8b5cf6', 7, 0.95, 'solid'))
  }
  const optimizedMode = showOptimizedLine.value && optimizedAllPoints.value.length && (playbackRoute.value === 'optimized' || !showOriginalLine.value)
  pendingAllPoints.value.forEach((point, index) => {
    const marker = new BMap.Marker(new BMap.Point(point.longitude, point.latitude), { icon: markerIcon(BMap, markerAppearance(point, props.pendingColor)) })
    marker.setTitle(`待插入时间窗点：${pointTitle(point)}`)
    const label = new BMap.Label(`待${index + 1} ${point.facilityName || ''}`, { offset: new BMap.Size(12, -18) })
    label.setStyle({ color: props.pendingColor, border: `1px solid ${props.pendingColor}`, backgroundColor: '#fff', fontSize: '11px' })
    marker.setLabel(label)
    mapInstance.addOverlay(marker)
  })
  markerPoints.value.forEach((point, index) => {
    const isOptimizedPoint = optimizedMode && (props.optimizedFacilityIds === null || optimizedFacilityIdSet.value.has(String(point.facilityId)))
    const appearance = markerAppearance(point, isOptimizedPoint ? point.routeColor || props.markerColor : '#dc2626')
    const marker = new BMap.Marker(new BMap.Point(point.longitude, point.latitude), { icon: markerIcon(BMap, appearance) })
    const semanticLabel = pointSemanticLabel(point)
    marker.setTitle(`${point.facilityName || String(point.facilityId || index + 1)}${semanticLabel ? `（${semanticLabel}）` : ''}${point.flowVisitTitle ? `；${point.flowVisitTitle}` : ''}${point.comparisonArrivalTime ? ` 到达${point.comparisonArrivalTime}` : ''}`)
    mapInstance.addOverlay(marker)
    if (labelsVisible.value) {
      const label = new BMap.Label(overviewLayers.value.length ? point.label : String(index + 1), {
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
  fitMapViewportOnce(BMap)
  if (focusedLayer.value?.geometry?.length >= 2) mapInstance.setViewport(focusedLayer.value.geometry.map(point => new BMap.Point(point.longitude, point.latitude)), { margins: [55, 55, 55, 55] })
  if (props.viewLink && !unlinkView) unlinkView = props.viewLink.register(mapInstance, (lng, lat) => new BMap.Point(lng, lat))
}

function handleMapWheel(event) {
  event.preventDefault()
  if (!mapInstance) return
  const now = performance.now()
  const target = wheelZoomTarget(mapInstance.getZoom(), event.deltaY, now - lastWheelZoomAt)
  if (target == null) return
  lastWheelZoomAt = now
  mapInstance.setZoom(target)
}

function markerIcon(BMap, appearance) {
  const svg = '<svg xmlns="http://www.w3.org/2000/svg" width="28" height="38" viewBox="0 0 28 38"><path d="M14 1C6.8 1 1 6.8 1 14c0 9.2 13 23 13 23s13-13.8 13-23C27 6.8 21.2 1 14 1z" fill="' + appearance.fill + '" stroke="' + appearance.stroke + '" stroke-width="' + appearance.strokeWidth + '"/><circle cx="14" cy="14" r="5" fill="' + appearance.center + '"/></svg>'
  return new BMap.Icon('data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg), new BMap.Size(28, 38), { anchor: new BMap.Size(14, 38), imageSize: new BMap.Size(28, 38) })
}

function markerAppearance(point, fallbackColor) {
  if (point.flowMapMarker) {
    const count = point.flowCollectionCount
    if (count >= 3) return { fill: '#1d4ed8', stroke: '#ffffff', strokeWidth: 2.4, center: '#ffffff' }
    if (count === 2) return { fill: '#0d9488', stroke: '#ffffff', strokeWidth: 2.4, center: '#ffffff' }
    if (count === 1) return { fill: '#22c55e', stroke: '#ffffff', strokeWidth: 2.4, center: '#ffffff' }
    if (point.flowThroughCount > 0) return { fill: '#ffffff', stroke: '#64748b', strokeWidth: 2.6, center: '#64748b' }
    return { fill: point.routeColor || fallbackColor, stroke: '#ffffff', strokeWidth: 2.4, center: '#ffffff' }
  }
  if (point.flowThrough) return { fill: '#ffffff', stroke: point.routeColor || fallbackColor, strokeWidth: 2.6, center: point.routeColor || fallbackColor }
  if (point.comparisonSelected) return { fill: props.changedPointColor, stroke: point.timeWindowViolation ? '#dc2626' : '#ffffff', strokeWidth: point.timeWindowViolation ? 3.5 : 2.4, center: '#ffffff' }
  if (point.timeWindowViolation) return { fill: '#dc2626', stroke: '#ffffff', strokeWidth: 2.4, center: '#ffffff' }
  if (point.scheduleSkipped) return { fill: '#ffffff', stroke: '#71889a', strokeWidth: 2.4, center: '#ffffff' }
  if (props.showPointSemantics) {
    if (point.candidateMultiCollection) return { fill: '#8b5fc6', stroke: '#ffffff', strokeWidth: 2, center: '#ffffff' }
    if (point.candidateShared) return { fill: '#d49a18', stroke: '#ffffff', strokeWidth: 2, center: '#ffffff' }
    if (point.candidateLowFrequency) return { fill: '#ffffff', stroke: '#71889a', strokeWidth: 2.4, center: '#71889a' }
    return { fill: '#16a34a', stroke: '#ffffff', strokeWidth: 2, center: '#ffffff' }
  }
  return { fill: fallbackColor, stroke: '#ffffff', strokeWidth: 2, center: '#ffffff' }
}

function fallbackMarkerStyle(point, fallbackColor) {
  const appearance = markerAppearance(point, fallbackColor)
  return { fill: appearance.fill, stroke: appearance.stroke, strokeWidth: appearance.strokeWidth }
}

function pointSemanticLabel(point) {
  if (point.flowMapMarker) return `收运${point.flowCollectionCount}次、途经${point.flowThroughCount}次`
  if (point.flowThrough) return '流水途经，未收运'
  if (point.comparisonSelected) return point.timeWindowViolation ? '本次选中或挪动；到达时间超出时间窗' : '本次选中或挪动'
  if (point.timeWindowViolation) return '到达时间超出时间窗'
  if (point.scheduleSkipped) return '今日不收（道路仍经过）'
  if (!props.showPointSemantics) return ''
  if (point.candidateMultiCollection) return '多次收运'
  if (point.candidateShared) return '共享点'
  if (point.candidateLowFrequency) return '低频点'
  return '普通收运点'
}

function drawPlaybackMarker(BMap) {
  playbackMarker = null
  const point = playbackCurrentPoint.value
  if (!props.showPlayback || !canPlay.value || !point) return
  const icon = hasCustomPlaybackChoices.value ? vehiclePlaybackIcon(BMap) : null
  playbackMarker = icon
    ? new BMap.Marker(new BMap.Point(point.longitude, point.latitude), { icon })
    : new BMap.Marker(new BMap.Point(point.longitude, point.latitude))
  playbackMarker.setTitle('轨迹播放位置')
  const label = hasCustomPlaybackChoices.value ? null : new BMap.Label('车', { offset: new BMap.Size(12, -22) })
  if (label) {
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
  }
  mapInstance.addOverlay(playbackMarker)
}

function vehiclePlaybackIcon(BMap) {
  const svg = '<svg xmlns="http://www.w3.org/2000/svg" width="38" height="30" viewBox="0 0 38 30"><rect x="3" y="7" width="20" height="15" rx="3" fill="#1d4ed8" stroke="white" stroke-width="2"/><path d="M23 12h7l5 6v4H23z" fill="#2563eb" stroke="white" stroke-width="2"/><circle cx="11" cy="23" r="4" fill="#172554" stroke="white" stroke-width="1.5"/><circle cx="29" cy="23" r="4" fill="#172554" stroke="white" stroke-width="1.5"/></svg>'
  return new BMap.Icon('data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg), new BMap.Size(38, 30),
    { anchor: new BMap.Size(19, 15), imageSize: new BMap.Size(38, 30) })
}

function updatePlaybackMarker() {
  if (!playbackMarker || !window.BMap || !playbackCurrentPoint.value) return
  playbackMarker.setPosition(new window.BMap.Point(playbackCurrentPoint.value.longitude, playbackCurrentPoint.value.latitude))
}

function addMapControls(BMap) {
  if (mapControlsAdded) return
  mapControlsAdded = true
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

function fitMapViewportOnce(BMap) {
  if (mapViewportFitted) return
  const viewport = allPoints.value.map((point) => new BMap.Point(point.longitude, point.latitude))
  if (viewport.length > 1) {
    mapInstance.setViewport(viewport, {
      margins: [48, 48, 48, 48]
    })
    mapViewportFitted = true
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
      comparisonSelected: Boolean(point.comparisonSelected),
      longitude: Number(point.longitude),
      latitude: Number(point.latitude),
      label: String(point.flowVisitLabel ?? point.overviewOrder ?? index + 1),
      routeColor: point.routeColor,
      flowThrough: Boolean(point.flowThrough),
      flowMapMarker: Boolean(point.flowMapMarker),
      flowCollectionCount: Number(point.flowCollectionCount || 0),
      flowThroughCount: Number(point.flowThroughCount || 0),
      flowVisitTitle: point.flowVisitTitle,
      candidateMultiCollection: Boolean(point.candidateMultiCollection),
      candidateShared: Boolean(point.candidateShared),
      candidateLowFrequency: Boolean(point.candidateLowFrequency),
      scheduleSkipped: Boolean(point.scheduleSkipped),
      timeWindowViolation: Boolean(point.timeWindowViolation),
      comparisonArrivalTime: point.comparisonArrivalTime
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

function beginSeek() {
  if (!canPlay.value || seeking) return
  resumeAfterSeek = playbackRunning.value
  seeking = true
  pausePlayback()
}

function seekPlayback(event) {
  if (!canPlay.value) return
  playbackDistance.value = distanceAtSlider(Number(event.target.value), Number(event.target.max), playbackTotalDistance.value)
  updatePlaybackMarker()
}

function endSeek() {
  if (!seeking) return
  seeking = false
  if (resumeAfterSeek && playbackDistance.value < playbackTotalDistance.value) startPlayback()
  resumeAfterSeek = false
}

function stepPlayback(now) {
  if (!playbackRunning.value) return
  const elapsed = Math.max(0, now - playbackFrameTime)
  playbackFrameTime = now
  const metersPerSecond = playbackMetersPerSecond(playbackTotalDistance.value, playbackSpeed.value)
  playbackDistance.value = Math.min(playbackTotalDistance.value, playbackDistance.value + (elapsed / 1000) * metersPerSecond)
  updatePlaybackMarker()
  if (playbackDistance.value >= playbackTotalDistance.value) {
    pausePlayback()
    return
  }
  playbackAnimationFrame = requestAnimationFrame(stepPlayback)
}


function pathSourceSummary() {
  const visibleSegments = showOptimizedLine.value
    ? (overviewLayers.value.length ? overviewLayers.value.flatMap(layer => layer.segments) : props.optimizedSegments)
    : props.originalSegments
  const sources = new Set((visibleSegments || []).map((segment) => segment.pathSource || 'DIRECT'))
  if (sources.size === 0) return '直线回退'
  return Array.from(sources).map(pathSourceLabel).join('/')
}

function pathSourceLabel(source) {
  if (source === 'OD_CACHE' || source === 'OD_PRELOAD') return 'OD缓存道路折线'
  if (source === 'BAIDU_ONLINE') return '百度在线道路折线'
  if (source === 'FLOW_POINT_LINE') return '流水点位顺序线'
  if (source === 'GPS_ACTUAL') return '车辆 GPS 实际轨迹'
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

function buildRoutePlot(originalPoints, optimizedPoints, pendingPoints = []) {
  if (originalPoints.length === 0 && optimizedPoints.length === 0 && pendingPoints.length === 0) return null
  const bounds = boundsFor([...originalPoints, ...optimizedPoints, ...pendingPoints])
  const original = projectRoute(originalPoints, bounds)
  const optimized = projectRoute(optimizedPoints, bounds)
  return {
    original,
    optimized,
    pending: projectRoute(pendingPoints, bounds),
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
      label: point.label || String(index + 1),
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
  const semanticLabel = pointSemanticLabel(point)
  return `${point.label}. ${point.facilityName || point.facilityId || '未知点位'}${semanticLabel ? `（${semanticLabel}）` : ''}${point.flowVisitTitle ? `；${point.flowVisitTitle}` : ''}`
}
</script>
