export const NUMERIC_TYPES = ['INT8', 'INT16', 'INT32', 'INT64', 'FLOAT', 'DOUBLE', 'DECIMAL', 'UNIXTIME_MICROS']

export function isNumericType(type) {
  return NUMERIC_TYPES.includes(type)
}

export function formatBytes(bytes) {
  if (bytes === null || bytes === undefined) return '—'
  const value = Number(bytes)
  if (!isFinite(value) || value < 0) return '—'
  if (value < 1024) return value + ' B'
  const units = ['KB', 'MB', 'GB', 'TB']
  let size = value / 1024
  let index = 0
  while (size >= 1024 && index < units.length - 1) {
    size /= 1024
    index++
  }
  return (size >= 100 ? size.toFixed(0) : size.toFixed(1)) + ' ' + units[index]
}

export function formatCount(value) {
  if (value === null || value === undefined) return '—'
  return Number(value).toLocaleString('zh-CN')
}

export function pad(value) {
  return String(value).padStart(2, '0')
}

/** 微秒 → 本地时间文本 */
export function formatMicros(micros) {
  if (micros === null || micros === undefined || micros === '') return '—'
  const ms = Number(micros) / 1000
  if (!isFinite(ms)) return String(micros)
  const date = new Date(ms)
  if (isNaN(date.getTime())) return String(micros)
  return (
    date.getFullYear() +
    '-' + pad(date.getMonth() + 1) +
    '-' + pad(date.getDate()) +
    ' ' + pad(date.getHours()) +
    ':' + pad(date.getMinutes()) +
    ':' + pad(date.getSeconds()) +
    '.' + String(date.getMilliseconds()).padStart(3, '0')
  )
}

/** 微秒 → datetime-local 输入框的值 */
export function microsToLocalInput(micros) {
  if (micros === null || micros === undefined || micros === '') return ''
  const date = new Date(Number(micros) / 1000)
  if (isNaN(date.getTime())) return ''
  return (
    date.getFullYear() +
    '-' + pad(date.getMonth() + 1) +
    '-' + pad(date.getDate()) +
    'T' + pad(date.getHours()) +
    ':' + pad(date.getMinutes()) +
    ':' + pad(date.getSeconds())
  )
}

/** datetime-local 的值 → 微秒 */
export function localInputToMicros(text) {
  if (!text) return null
  const ms = new Date(text).getTime()
  if (isNaN(ms)) return null
  return ms * 1000
}

export function cellDisplay(value, type) {
  if (value === null || value === undefined) {
    return { text: 'NULL', isNull: true }
  }
  if (type === 'UNIXTIME_MICROS') {
    return { text: formatMicros(value), title: String(value), numeric: true }
  }
  if (type === 'BINARY') {
    const text = String(value)
    return {
      text: text.length > 28 ? text.slice(0, 28) + '…' : text,
      title: text,
      mono: true
    }
  }
  if (type === 'BOOL') {
    return { text: value ? 'true' : 'false', mono: true }
  }
  if (typeof value === 'object') {
    return { text: JSON.stringify(value), mono: true }
  }
  const text = String(value)
  return { text, title: text, numeric: isNumericType(type) }
}
