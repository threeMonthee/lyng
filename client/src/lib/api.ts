// JSON 接口。错误统一是 { error, runId? }，抛成 ApiError，页面直接展示 message。

export class ApiError extends Error {
  readonly status: number
  readonly runId: string | null

  constructor(message: string, status: number, runId: string | null = null) {
    super(message)
    this.status = status
    this.runId = runId
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  let response: Response
  try {
    response = await fetch(`/api${path}`, {
      method,
      headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    throw new ApiError('连不上服务，确认它还在运行', 0)
  }
  const text = await response.text()
  const data = text ? safeJson(text) : null
  if (!response.ok) {
    const message =
      data && typeof data === 'object' && 'error' in data ? String((data as { error: unknown }).error) : `请求失败（${response.status}）`
    const runId = data && typeof data === 'object' && 'runId' in data ? String((data as { runId: unknown }).runId) : null
    throw new ApiError(message, response.status, runId)
  }
  return data as T
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body ?? {}),
  put: <T>(path: string, body: unknown) => request<T>('PUT', path, body),
  delete: <T>(path: string) => request<T>('DELETE', path),
}

/** 离开页面时也要送达的请求（私聊的“离开”）。 */
export function beacon(path: string) {
  try {
    void fetch(`/api${path}`, { method: 'POST', keepalive: true, headers: { 'Content-Type': 'application/json' }, body: '{}' })
  } catch {
    // 页面正在关闭，送不到也没关系：下次进入时会补上
  }
}
