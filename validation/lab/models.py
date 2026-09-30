"""模型客户端：OpenRouter 与确定性替身。

两者都实现 complete(messages, max_tokens, purpose) -> Completion。
purpose 只是给替身看的调用说明，不会发送给 OpenRouter。
"""

import json
import os
import urllib.error
import urllib.request
from dataclasses import dataclass, field

OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"


class TransportError(RuntimeError):
    """请求没有拿到正常响应，也就无法确认费用。"""


@dataclass(frozen=True)
class Completion:
    text: str
    finish_reason: str | None
    cost: float | None  # 美元；None 表示接口没有返回费用
    usage: dict = field(default_factory=dict)


class OpenRouterClient:
    def __init__(self, model_id: str, api_key: str | None = None, timeout: float = 180.0):
        if not model_id:
            raise ValueError("模型 ID 必须从命令行显式传入")
        key = api_key if api_key is not None else os.environ.get("OPENROUTER_API_KEY")
        if not key:
            raise RuntimeError("环境变量 OPENROUTER_API_KEY 未设置")
        self.model_id = model_id
        self._key = key
        self._timeout = timeout

    def request_body(self, messages: list[dict], max_tokens: int) -> dict:
        return {
            "model": self.model_id,
            "messages": messages,
            "max_tokens": max_tokens,
            "usage": {"include": True},
        }

    def complete(self, messages: list[dict], max_tokens: int, purpose: dict) -> Completion:
        req = urllib.request.Request(
            OPENROUTER_URL,
            data=json.dumps(self.request_body(messages, max_tokens), ensure_ascii=False).encode("utf-8"),
            headers={"Authorization": f"Bearer {self._key}", "Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=self._timeout) as resp:
                payload = json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", "replace")[:500]
            e.close()
            raise TransportError(f"HTTP {e.code}：{body}") from e
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
            raise TransportError(f"{type(e).__name__}：{e}") from e
        return parse_response(payload)


def parse_response(payload: dict) -> Completion:
    if "error" in payload and not payload.get("choices"):
        raise TransportError(f"接口错误：{json.dumps(payload['error'], ensure_ascii=False)[:500]}")
    usage = payload.get("usage") or {}
    cost = usage.get("cost")
    choices = payload.get("choices") or [{}]
    choice = choices[0]
    text = (choice.get("message") or {}).get("content") or ""
    return Completion(
        text=text,
        finish_reason=choice.get("finish_reason"),
        cost=float(cost) if isinstance(cost, (int, float)) else None,
        usage={k: usage[k] for k in ("prompt_tokens", "completion_tokens", "total_tokens") if k in usage},
    )


class FakeModel:
    """确定性模型替身：输出只取决于调用说明和调用次序，永远合规。"""

    def __init__(self, label: str, cost_per_call: float = 0.0001):
        self.model_id = label
        self.cost_per_call = cost_per_call
        self.calls = 0

    def complete(self, messages: list[dict], max_tokens: int, purpose: dict) -> Completion:
        self.calls += 1
        n = self.calls
        kind = purpose["kind"]
        if kind == "opening":
            text = f"【叙事】（替身开场 · {purpose['scene']}）"
        elif kind == "closing":
            text = "【叙事】（替身收尾）"
        elif kind == "consent":
            text = "【判定】未拒绝"
        elif kind == "round":
            narration = f"【叙事】（替身叙事 {n}）\n" if purpose["step"] in ("做", "推进") else ""
            text = f"{narration}【反应】{'、'.join(purpose['candidates'])}"
        elif kind == "character":
            name = purpose["name"]
            if n % 5 == 0:
                text = f"【不开口】\n【心声】（{name}的替身心声 {n}）"
            else:
                text = f"【行】{name}抬眼看了一下。\n【说】（{name}的替身台词 {n}）\n【心声】（{name}的替身心声 {n}）"
        else:
            raise ValueError(f"未知调用：{kind}")
        return Completion(text, "stop", self.cost_per_call, {"prompt_tokens": 0, "completion_tokens": 0})
