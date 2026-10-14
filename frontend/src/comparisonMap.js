// One link per comparison dialog: no global coupling between unrelated maps.
export function createMapViewLink() {
  const members = new Set()
  let updating = false
  function align(target, source) {
    const center = source.map.getCenter(), current = target.map.getCenter()
    const zoom = source.map.getZoom()
    if (zoom === target.map.getZoom() && Math.abs(center.lng - current.lng) < 1e-7 && Math.abs(center.lat - current.lat) < 1e-7) return
    target.map.centerAndZoom(target.makePoint(center.lng, center.lat), zoom)
  }
  return {
    register(map, makePoint) {
      const member = { map, makePoint }
      const events = ['moving', 'moveend', 'zoomend']
      const sync = () => {
        if (updating) return
        updating = true
        try { for (const other of members) if (other !== member) align(other, member) }
        finally { updating = false }
      }
      updating = true
      try { if (members.size) align(member, members.values().next().value) }
      finally { updating = false }
      members.add(member)
      events.forEach(event => map.addEventListener(event, sync))
      return () => {
        members.delete(member)
        events.forEach(event => map.removeEventListener(event, sync))
      }
    }
  }
}

export function markSelectedPoints(points, tokens) {
  const selected = new Set(tokens)
  return points.map(point => ({
    ...point,
    comparisonSelected: point.manualToken != null && selected.has(point.manualToken)
  }))
}
