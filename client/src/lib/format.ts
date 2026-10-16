// 时间与数字的说法。时间一律按东八区写，和人物看到的“现在”一致。

const zone = 'Asia/Shanghai'

function parts(iso: string) {
  const d = new Date(iso)
  const f = new Intl.DateTimeFormat('zh-CN', {
    timeZone: zone,
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).formatToParts(d)
  const get = (t: string) => Number(f.find((p) => p.type === t)?.value ?? 0)
  return { y: get('year'), m: get('month'), d: get('day'), h: get('hour') % 24, min: get('minute') }
}

/** 列表里的时间：今天写几点，昨天写“昨天”，今年写几月几日，更早写年份。 */
export function shortTime(iso: string, now = new Date()): string {
  const t = parts(iso)
  const n = parts(now.toISOString())
  const hm = `${t.h}:${String(t.min).padStart(2, '0')}`
  if (t.y === n.y && t.m === n.m && t.d === n.d) return hm
  const yesterday = parts(new Date(now.getTime() - 86400000).toISOString())
  if (t.y === yesterday.y && t.m === yesterday.m && t.d === yesterday.d) return `昨天 ${hm}`
  if (t.y === n.y) return `${t.m}月${t.d}日`
  return `${t.y}年${t.m}月${t.d}日`
}

/** 私聊里的时间分隔：10月16日 晚上9:05 */
export function chatTime(iso: string): string {
  const t = parts(iso)
  const part = t.h < 5 ? '凌晨' : t.h < 8 ? '清早' : t.h < 11 ? '上午' : t.h < 13 ? '中午' : t.h < 18 ? '下午' : '晚上'
  const h12 = t.h > 12 ? t.h - 12 : t.h
  return `${t.m}月${t.d}日 ${part}${h12}:${String(t.min).padStart(2, '0')}`
}

export function fullTime(iso: string): string {
  const t = parts(iso)
  return `${t.y}年${t.m}月${t.d}日 ${t.h}:${String(t.min).padStart(2, '0')}`
}

export function usd(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  if (value === 0) return '$0'
  if (value < 0.01) return `$${value.toFixed(4)}`
  return `$${value.toFixed(2)}`
}

export function ms(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  return value < 1000 ? `${value} 毫秒` : `${(value / 1000).toFixed(1)} 秒`
}

/** 两条消息之间隔了半小时以上，就插一行时间。 */
export function needsTimeSeparator(previous: string | null, current: string): boolean {
  if (!previous) return true
  return new Date(current).getTime() - new Date(previous).getTime() >= 30 * 60 * 1000
}
