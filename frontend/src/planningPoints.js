export function isDisposalPoint(point) {
  const type = point?.facilityType
  if (type != null && String(type).trim() !== '' && Number.isFinite(Number(type))) return Number(type) === 5
  return ['处置场', '处理厂', '垃圾处理厂', '生活垃圾处理厂', '垃圾处置场'].includes(String(point?.facilityTypeName || '').trim())
}
export function collectionPlanningPoints(points) {
  return points.filter(point => !isDisposalPoint(point))
}
