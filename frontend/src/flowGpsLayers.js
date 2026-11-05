const GPS_COLORS = ['#e11d48', '#f97316', '#a855f7', '#0891b2', '#65a30d', '#be123c']

export function flowTimeMillis(value) {
  if (value == null || value === '') return null
  if (typeof value === 'number' || /^\d{10,13}$/.test(String(value))) {
    const number = Number(value)
    return Number.isFinite(number) ? (number < 1e11 ? number * 1000 : number) : null
  }
  const text = String(value).trim().replace(' ', 'T')
  const hasZone = /(?:Z|[+-]\d\d:\d\d)$/i.test(text)
  const parsed = Date.parse(hasZone ? text : `${text}+08:00`)
  return Number.isFinite(parsed) ? parsed : null
}

function tripWindow(row) {
  const trip = row.trip || {}
  const first = trip.points?.[0]
  const last = trip.points?.[trip.points.length - 1]
  const start = flowTimeMillis(trip.start?.time ?? first?.time ?? trip.end?.time)
  const end = flowTimeMillis(trip.end?.time ?? last?.leaveTime ?? last?.time ?? trip.start?.time)
  return start == null || end == null || end < start ? null : { index: row.index, start, end }
}

/** Split one vehicle's daily GPS by recorded trip event times, not by inferred GPS geofences. */
export function buildFlowGpsLayers(points, tripRows) {
  const windows = (tripRows || []).map(tripWindow).filter(Boolean).sort((a, b) => a.start - b.start || a.index - b.index)
  const timed = (points || []).map((point, position) => ({ point, position, time: flowTimeMillis(point.equipmentTime) }))
    .filter(row => Number.isFinite(Number(row.point.longitude)) && Number.isFinite(Number(row.point.latitude)))
    .sort((a, b) => (a.time ?? Infinity) - (b.time ?? Infinity) || a.position - b.position)
  if (!timed.length) return []
  const firstStart = windows[0]?.start
  const lastEnd = windows[windows.length - 1]?.end
  const runs = []
  for (const row of timed) {
    let kind = 'unassigned'
    let tripIndex = null
    if (row.time != null && windows.length) {
      const window = windows.find((item, index) => row.time >= item.start && (row.time < item.end || (index === windows.length - 1 && row.time <= item.end)))
      if (window) { kind = 'trip'; tripIndex = window.index }
      else if (row.time < firstStart) kind = 'before'
      else if (row.time > lastEnd) kind = 'after'
      else kind = 'between'
    }
    const previous = runs[runs.length - 1]
    if (previous && previous.kind === kind && previous.tripIndex === tripIndex) previous.points.push(row.point)
    else runs.push({ kind, tripIndex, points: [row.point] })
  }
  return runs.map((run, position) => {
    const label = run.kind === 'trip' ? `GPS · 第${run.tripIndex + 1}趟`
      : run.kind === 'before' ? 'GPS · 首趟流水前'
      : run.kind === 'after' ? 'GPS · 末趟流水后'
      : run.kind === 'between' ? 'GPS · 趟间未归属' : 'GPS · 时间未归属'
    return {
      key: `gps-${position}`, label, kind: run.kind, tripIndex: run.tripIndex,
      color: run.kind === 'trip' ? GPS_COLORS[run.tripIndex % GPS_COLORS.length] : '#475569',
      points: run.points,
      segments: run.points.length >= 2 ? [{ pathSource: 'GPS_ACTUAL', path: run.points }] : [],
      gpsActual: true
    }
  })
}
