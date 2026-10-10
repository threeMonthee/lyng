/** 与服务端通信：JSON 接口，以及“会跑模型的动作”返回的 SSE 流（POST，所以不用 EventSource）。 */

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

async function errorOf(res: Response): Promise<ApiError> {
  let message = `请求失败（HTTP ${res.status}）`;
  try {
    const body = await res.json();
    if (body?.error) message = body.error;
  } catch {
    // 不是 JSON：保留通用说明
  }
  return new ApiError(res.status, message);
}

export async function api<T>(path: string, method = "GET", body?: unknown): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`/api${path}`, {
      method,
      headers: body === undefined ? undefined : { "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, "连不上服务，确认它还在运行");
  }
  if (!res.ok) throw await errorOf(res);
  return (await res.json()) as T;
}

export type StreamEvent = { event: string; data: any };

/** 从缓冲里取出完整的 SSE 事件，返回事件与剩下的半截。 */
export function parseSse(buffer: string): { events: StreamEvent[]; rest: string } {
  const events: StreamEvent[] = [];
  const blocks = buffer.split("\n\n");
  const rest = blocks.pop() ?? "";
  for (const block of blocks) {
    let event = "message";
    const data: string[] = [];
    for (const line of block.split("\n")) {
      if (line.startsWith("event:")) event = line.slice(6).trim();
      else if (line.startsWith("data:")) data.push(line.slice(5).trim());
    }
    if (data.length) events.push({ event, data: JSON.parse(data.join("\n")) });
  }
  return { events, rest };
}

export async function stream(path: string, body: unknown, onEvent: (e: StreamEvent) => void): Promise<void> {
  let res: Response;
  try {
    res = await fetch(`/api${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body ?? {}),
    });
  } catch {
    throw new ApiError(0, "连不上服务，确认它还在运行");
  }
  if (!res.ok) throw await errorOf(res);
  const reader = res.body!.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const parsed = parseSse(buffer);
    buffer = parsed.rest;
    parsed.events.forEach(onEvent);
  }
}
