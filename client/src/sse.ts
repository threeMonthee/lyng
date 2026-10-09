// POST 返回 text/event-stream：EventSource 只支持 GET，所以用 fetch 读流。

export interface SseMessage {
  event: string;
  data: unknown;
}

/** 增量解析：喂入任意分片，吐出已经完整的消息。 */
export class SseParser {
  private buffer = "";

  push(chunk: string): SseMessage[] {
    this.buffer += chunk.replace(/\r\n/g, "\n");
    const out: SseMessage[] = [];
    let end: number;
    while ((end = this.buffer.indexOf("\n\n")) >= 0) {
      const block = this.buffer.slice(0, end);
      this.buffer = this.buffer.slice(end + 2);
      const message = parseBlock(block);
      if (message) out.push(message);
    }
    return out;
  }
}

function parseBlock(block: string): SseMessage | null {
  let event = "message";
  const data: string[] = [];
  for (const line of block.split("\n")) {
    if (line.startsWith(":")) continue;
    if (line.startsWith("event:")) event = line.slice(6).trim();
    else if (line.startsWith("data:")) data.push(line.slice(5).trimStart());
  }
  if (data.length === 0) return null;
  return { event, data: JSON.parse(data.join("\n")) };
}

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}

export async function streamPost(
  path: string,
  body: unknown,
  onMessage: (message: SseMessage) => void,
  signal?: AbortSignal,
): Promise<void> {
  const response = await fetch(`/api${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body ?? {}),
    signal,
  });
  if (!response.ok || !response.body) {
    const payload = await response.json().catch(() => ({}));
    throw new ApiError(payload.error ?? `请求失败（HTTP ${response.status}）`, response.status);
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  const parser = new SseParser();
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    parser.push(decoder.decode(value, { stream: true })).forEach(onMessage);
  }
}
