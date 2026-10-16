import { describe, expect, it } from 'vitest'
import { chatTime, needsTimeSeparator, shortTime, usd } from './format'

describe('时间', () => {
  const now = new Date('2026-10-16T12:00:00+08:00')

  it('今天写几点，昨天写昨天，今年写日期，更早写年份', () => {
    expect(shortTime('2026-10-16T09:05:00+08:00', now)).toBe('9:05')
    expect(shortTime('2026-10-15T21:30:00+08:00', now)).toBe('昨天 21:30')
    expect(shortTime('2026-03-02T10:00:00+08:00', now)).toBe('3月2日')
    expect(shortTime('2025-12-31T10:00:00+08:00', now)).toBe('2025年12月31日')
  })

  it('按东八区算，不随浏览器时区', () => {
    expect(shortTime('2026-10-16T01:05:00Z', now)).toBe('9:05')
  })

  it('私聊的时间分隔用时段', () => {
    expect(chatTime('2026-10-16T21:05:00+08:00')).toBe('10月16日 晚上9:05')
    expect(chatTime('2026-10-16T07:30:00+08:00')).toBe('10月16日 清早7:30')
  })

  it('隔半小时以上才插时间', () => {
    expect(needsTimeSeparator(null, '2026-10-16T10:00:00Z')).toBe(true)
    expect(needsTimeSeparator('2026-10-16T10:00:00Z', '2026-10-16T10:29:00Z')).toBe(false)
    expect(needsTimeSeparator('2026-10-16T10:00:00Z', '2026-10-16T10:30:00Z')).toBe(true)
  })
})

describe('金额', () => {
  it('小额保留四位，零写成 $0，空写成横线', () => {
    expect(usd(0)).toBe('$0')
    expect(usd(0.0012)).toBe('$0.0012')
    expect(usd(1.5)).toBe('$1.50')
    expect(usd(null)).toBe('—')
  })
})
