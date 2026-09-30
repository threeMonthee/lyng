import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import dataset  # noqa: E402
from models import Completion, FakeModel  # noqa: E402

DS = dataset.load()


class RecordingFake(FakeModel):
    """替身的变体：记录每次调用的消息；hook(purpose, n) 返回 Completion 时覆盖默认输出。"""

    def __init__(self, hook=None, cost_per_call: float = 0.0001):
        super().__init__("测试替身", cost_per_call)
        self.hook = hook
        self.log: list[tuple[dict, list[dict]]] = []

    def complete(self, messages, max_tokens, purpose):
        self.log.append((purpose, messages))
        if self.hook:
            override = self.hook(purpose, len(self.log))
            if override is not None:
                self.calls += 1
                return override
        return super().complete(messages, max_tokens, purpose)

    def prompts_for(self, kind: str, name: str | None = None) -> list[str]:
        return [
            "\n".join(m["content"] for m in msgs)
            for p, msgs in self.log
            if p["kind"] == kind and (name is None or p.get("name") == name)
        ]


def ok(text: str, cost: float = 0.0001) -> Completion:
    return Completion(text, "stop", cost)
