/**
 * 把后端返回的时间字符串格式化成人看的样子。
 *
 * 后端返回的是 ISO 格式（2026-09-14T20:30:00），直接显示又长又难认。
 * 注意不要用 new Date(str).toLocaleString() —— 那会按浏览器所在时区转换，
 * 而用户看到的应该就是服务端记录的时间。
 */
export function formatDateTime(value) {
  if (!value) return '-'
  return String(value).replace('T', ' ').slice(0, 19)
}

/** 只要日期部分。 */
export function formatDate(value) {
  if (!value) return '-'
  return String(value).slice(0, 10)
}

/**
 * 大数字加千分位。访问量上万之后不加分隔符很难一眼看出量级。
 */
export function formatNumber(value) {
  if (value === null || value === undefined) return '0'
  return Number(value).toLocaleString('zh-CN')
}
