export function planningProgressView(task, now = Date.now()) {
  const progress = task.progress || {}
  const sinceReceived = progress.receivedAt ? Math.max(0, now - progress.receivedAt) : 0
  const active = task.running && !task.applying && !['WAITING_INSERTION', 'DONE', 'FAILED', 'CANCELLED'].includes(task.status)
  const elapsedMs = Math.max(0, Number(progress.elapsedMs || 0)) + (active ? sinceReceived : 0)
  const updatedAgoMs = Math.max(0, Number(progress.updatedAgoMs || 0)) + (active ? sinceReceived : 0)
  const roads = progress.roads || {}
  const total = Number(roads.total || 0), completed = Number(roads.completed || 0)
  return {
    phase: ['WAITING_INSERTION', 'DONE', 'FAILED', 'CANCELLED'].includes(task.status) ? task.status : (progress.phase || task.status || 'QUEUED'), roads,
    roadPercent: total > 0 ? Math.min(100, Math.floor(completed / total * 100)) : null,
    elapsed: `${Math.floor(elapsedMs / 60000)}分${Math.floor(elapsedMs / 1000) % 60}秒`,
    updatedSeconds: Math.floor(updatedAgoMs / 1000),
    stale: active && updatedAgoMs >= 15000,
    best: progress.bestCandidate || null,
    capacityPruned: progress.capacityPruned || 0
  }
}
