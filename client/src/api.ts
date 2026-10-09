import { ApiError } from "./sse";

export { ApiError };

/** 登录失效时通知 App 回到登录页。 */
let onUnauthorized: () => void = () => {};
export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler;
}

export async function api<T>(path: string, method = "GET", body?: unknown): Promise<T> {
  const response = await fetch(`/api${path}`, {
    method,
    headers: body === undefined ? undefined : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) {
    if (response.status === 401 && path !== "/login") onUnauthorized();
    throw new ApiError(payload.error ?? `请求失败（HTTP ${response.status}）`, response.status);
  }
  return payload as T;
}
