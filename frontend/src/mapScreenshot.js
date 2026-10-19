export function mapCaptureCrop(rect, viewportWidth, viewportHeight, frameWidth, frameHeight) {
  const values = [rect.left, rect.top, rect.width, rect.height, viewportWidth, viewportHeight, frameWidth, frameHeight]
  if (values.some(value => !Number.isFinite(value)) || rect.width <= 0 || rect.height <= 0 || viewportWidth <= 0 || viewportHeight <= 0 || frameWidth <= 0 || frameHeight <= 0) {
    throw new Error('地图尺寸无效，无法保存截图')
  }
  if (rect.left < 0 || rect.top < 0 || rect.right > viewportWidth || rect.bottom > viewportHeight) {
    throw new Error('地图未完整显示；请先滚动到地图位置，或点击“最大化”后再保存')
  }
  const scaleX = frameWidth / viewportWidth
  const scaleY = frameHeight / viewportHeight
  return {
    x: Math.round(rect.left * scaleX),
    y: Math.round(rect.top * scaleY),
    width: Math.round(rect.width * scaleX),
    height: Math.round(rect.height * scaleY)
  }
}

export function mapScreenshotName(now = new Date()) {
  const part = value => String(value).padStart(2, '0')
  return `路线地图-${now.getFullYear()}${part(now.getMonth() + 1)}${part(now.getDate())}-${part(now.getHours())}${part(now.getMinutes())}${part(now.getSeconds())}.png`
}
