// 会跑模型的动作用 POST + SSE。服务端在页面断开后照常跑完并落库，所以连接中断时页面只需重新读取。

export type StreamEvent =
  | { event: 'status'; data: { step: string; speakerId?: string | null; name?: string } }
  | { event: 'draft'; data: { speakerId: string; kind: string; text: string } }
  | { event: 'discard'; data: Record<string, never> }
  | { event: 'saved'; data: Record<string, unknown> }
  | { event: 'notice'; data: { message: string } }
  | { event: 'failed'; data: { message: string; runId?: string } }
  | { event: 'closed'; data: Record<string, never> }
  | { event: 'done'; data: Record<string, never> }
  | { event: 'error'; data: { error: string; status: number; runId?: string } }
  | { event: 'end'; data: Record<string, never> }

/** 把一段 SSE 文本拆成事件；返回解析出的事件和没有结束的残余。 */
export function parseEvents(buffer: string): { events: { event: string; data: unknown }[]; rest: string } {
  const events: { event: string; data: unknown }[] = []
  const blocks = buffer.replace(/\r\n/g, '\n').split('\n\n')
  const rest = blocks.pop() ?? ''
  for (const block of blocks) {
    let name = 'message'
    const data: string[] = []
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) name = line.slice(6).trim()
      else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
    }
    if (data.length === 0) continue
    try {
      events.push({ event: name, data: JSON.parse(data.join('\n')) })
    } catch {
      events.push({ event: name, data: {} })
    }
  }
  return { events, rest }
}

/**
 * 发起一个流式动作。返回 true 表示收到了完整的结尾（end）；false 表示连接中途断了，
 * 页面应当重新读取数据（服务端可能还在跑）。
 */
export async function stream(path: string, body: unknown, onEvent: (e: StreamEvent) => void): Promise<boolean> {
  let response: Response
  try {
    response = await fetch(`/api${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify(body ?? {}),
    })
  } catch {
    onEvent({ event: 'error', data: { error: '连不上服务，确认它还在运行', status: 0 } })
    return true
  }
  if (!response.ok || !response.body) {
    const text = await response.text().catch(() => '')
    let message = `请求失败（${response.status}）`
    try {
      message = JSON.parse(text).error ?? message
    } catch {
      // 不是 JSON
    }
    onEvent({ event: 'error', data: { error: message, status: response.status } })
    return true
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let ended = false
  try {
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const parsed = parseEvents(buffer)
      buffer = parsed.rest
      for (const e of parsed.events) {
        if (e.event === 'end') ended = true
        onEvent(e as StreamEvent)
      }
    }
  } catch {
    return false
  }
  return ended
}
