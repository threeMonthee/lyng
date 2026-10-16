import { describe, expect, it } from 'vitest'
import { parseEvents } from './sse'

describe('parseEvents', () => {
  it('拆出完整的事件，留下没结束的一段', () => {
    const { events, rest } = parseEvents('event:status\ndata:{"step":"narrator"}\n\nevent:draft\ndata:{"te')
    expect(events).toEqual([{ event: 'status', data: { step: 'narrator' } }])
    expect(rest).toBe('event:draft\ndata:{"te')
  })

  it('接上残余后能解析，兼容 CRLF 和 data 后的空格', () => {
    const first = parseEvents('event: saved\r\ndata: {"lines":[1')
    const second = parseEvents(first.rest + ',2]}\r\n\r\nevent:end\r\ndata:{}\r\n\r\n')
    expect(second.events).toEqual([
      { event: 'saved', data: { lines: [1, 2] } },
      { event: 'end', data: {} },
    ])
    expect(second.rest).toBe('')
  })

  it('多行 data 拼成一个 JSON，坏的 JSON 变成空对象', () => {
    const { events } = parseEvents('event:x\ndata:{"a":\ndata:1}\n\nevent:y\ndata:{oops\n\n')
    expect(events).toEqual([
      { event: 'x', data: { a: 1 } },
      { event: 'y', data: {} },
    ])
  })
})
