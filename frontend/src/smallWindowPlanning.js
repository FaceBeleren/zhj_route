const clock = value => typeof value === 'string' && /^([01]?\d|2[0-3]):[0-5]\d(?::[0-5]\d)?$/.test(value.trim())
export const hasPlanningWindow = point => clock(point.allowTimeBegin) || clock(point.allowTimeEnd) || (clock(point.barredTimeBegin) && clock(point.barredTimeEnd))
export const planningWindowCount = points => points.filter(hasPlanningWindow).length
export function smallWindowDisabledReason(points, ready, busy) {
  if (busy) return '其他规划任务正在执行'
  if (!ready) return '请先完成点位选择和周期排班'
  const count = planningWindowCount(points)
  if (!count) return '没有时间窗点，请使用普通独立生成'
  if (count > 5) return `有${count}个时间窗点，仅支持1～5个`
  return ''
}
