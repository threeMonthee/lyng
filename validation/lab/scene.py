"""一场戏的循环：叙事者开场 → 按脚本逐轮（用户输入 → 叙事者幕后决定 → 人物反应）→ 叙事者收尾。

技术失败（空响应、截断、格式不合规）只重试一次，最终失败记成失败事件，绝不当作人物沉默。
拿不到费用或到达预算上限时抛 RunStop，整次运行立即停止。
"""

import hashlib
import json
from dataclasses import asdict, dataclass, field

import prompts
from dataset import Dataset, Scene, Step
from events import Event
from markup import FormatError, parse_consent, parse_narration, parse_round, parse_turn
from models import TransportError

FINISH_ERRORS = {"length": "截断", "content_filter": "内容过滤", "error": "生成出错"}


class RunStop(Exception):
    pass


class Ledger:
    """按接口返回的实际费用累计。每次调用前检查，已花费达到上限就停。"""

    def __init__(self, budget: float):
        if budget <= 0:
            raise ValueError("预算上限必须大于 0")
        self.budget = budget
        self.spent = 0.0

    def check(self) -> None:
        if self.spent >= self.budget:
            raise RunStop(f"已达预算上限 {self.budget} 美元（已花 {self.spent:.6f}）")

    def charge(self, cost: float) -> None:
        self.spent += cost


@dataclass
class CallRecord:
    kind: str
    round: int
    speaker: str | None
    prompt_sha256: str
    attempts: list[dict] = field(default_factory=list)
    outcome: str = "failed"  # ok | recovered | failed | stopped


@dataclass
class SceneResult:
    scene: str
    scale: str
    status: str  # completed | failed | stopped
    reason: str | None
    events: list[Event]
    calls: list[CallRecord]
    spent: float

    def to_dict(self) -> dict:
        return {
            "scene": self.scene,
            "scale": self.scale,
            "status": self.status,
            "reason": self.reason,
            "spent": round(self.spent, 8),
            "events": [e.to_dict() for e in self.events],
            "calls": [asdict(c) for c in self.calls],
        }


class SceneRun:
    def __init__(self, ds: Dataset, scene: Scene, scale: str, client, ledger: Ledger, max_tokens: int,
                 previous: list[Event] | None = None):
        self.ds, self.scene, self.scale = ds, scene, scale
        self.client, self.ledger, self.max_tokens = client, ledger, max_tokens
        self.previous = previous
        self.events: list[Event] = []
        self.calls: list[CallRecord] = []
        self.round = 0

    def _call(self, purpose: dict, messages: list[dict], parse):
        digest = hashlib.sha256(json.dumps(messages, ensure_ascii=False).encode()).hexdigest()
        rec = CallRecord(purpose["kind"], self.round, purpose.get("name"), digest)
        self.calls.append(rec)
        for attempt in range(2):
            try:
                self.ledger.check()
            except RunStop:
                rec.outcome = "stopped"
                raise
            try:
                comp = self.client.complete(messages, self.max_tokens, purpose)
            except TransportError as e:
                rec.attempts.append({"error": f"请求失败：{e}", "cost": None})
                rec.outcome = "stopped"
                raise RunStop(f"请求失败，拿不到费用：{e}") from e
            att = {"text": comp.text, "finish_reason": comp.finish_reason, "cost": comp.cost, "usage": comp.usage}
            rec.attempts.append(att)
            if comp.cost is None:
                att["error"] = "接口未返回费用"
                rec.outcome = "stopped"
                raise RunStop("接口未返回费用")
            self.ledger.charge(comp.cost)

            error = FINISH_ERRORS.get(comp.finish_reason)
            if error is None and not comp.text.strip():
                error = "空响应"
            if error is None:
                try:
                    parsed = parse(comp.text)
                    rec.outcome = "ok" if attempt == 0 else "recovered"
                    return parsed
                except FormatError as e:
                    error = f"格式不合规：{e}"
            att["error"] = error
        return None

    def _fail(self, who: str, what: str) -> None:
        reason = self.calls[-1].attempts[-1].get("error", "")
        self.events.append(Event(self.round, "failure", speaker=who, text=f"{what}：{reason}（已重试一次）"))

    def _consent_refused(self) -> bool:
        refused = self._call(
            {"kind": "consent", "scene": self.scene.id},
            prompts.consent_messages(self.scene, self.events),
            parse_consent,
        )
        if refused is None:
            self._fail("同意检查", "技术失败，按拒绝处理")
            return True
        return refused

    def run(self) -> SceneResult:
        start = self.ledger.spent
        try:
            status, reason = self._run()
        except RunStop as e:
            status, reason = "stopped", str(e)
        return SceneResult(self.scene.id, self.scale, status, reason, self.events, self.calls, self.ledger.spent - start)

    def _run(self) -> tuple[str, str | None]:
        ds, scene, scale = self.ds, self.scene, self.scale
        opening = self._call(
            {"kind": "opening", "scene": scene.id},
            prompts.narrator_opening_messages(ds, scene, scale),
            parse_narration,
        )
        if opening is None:
            self._fail("叙事者", "开场技术失败")
            return "failed", "叙事者开场技术失败"
        self.events.append(Event(0, "narration", text=opening))

        refused = False
        for step in scene.script:
            self.round = step.index
            if step.needs_consent:
                if not refused:
                    refused = self._consent_refused()
                if refused:
                    self.events.append(Event(step.index, "note", text=f"〔需同意〕此前已拒绝，原脚本“{step.kind}：{step.text}”改为静候"))
                    step = Step(step.index, "静候")
            self._play(step)

        self.round = len(scene.script) + 1
        closing = self._call(
            {"kind": "closing", "scene": scene.id},
            prompts.narrator_closing_messages(ds, scene, scale, self.events),
            parse_narration,
        )
        if closing is None:
            self._fail("叙事者", "收尾技术失败")
        else:
            self.events.append(Event(self.round, "narration", text=closing))
        return "completed", None

    def _play(self, step: Step) -> None:
        ds, scene, scale = self.ds, self.scene, self.scale
        before = list(self.events)
        if step.kind != "推进":
            self.events.append(Event(step.index, "user", speaker=scene.possessed, action=step.kind, text=step.text))

        decided = self._call(
            {"kind": "round", "scene": scene.id, "step": step.kind, "candidates": scene.ai_characters},
            prompts.narrator_round_messages(ds, scene, scale, before, step),
            lambda t: parse_round(t, scene.ai_characters, require_something=step.kind == "推进"),
        )
        if decided is None:
            self._fail("叙事者", "本轮幕后决定技术失败，本轮无人反应")
            return
        narration, reactors = decided
        if narration:
            self.events.append(Event(step.index, "narration", text=narration))
        for name in reactors:
            turn = self._call(
                {"kind": "character", "scene": scene.id, "name": name},
                prompts.character_messages(ds, scene, scale, name, self.events, self.previous),
                parse_turn,
            )
            if turn is None:
                self._fail(name, "技术失败")
            else:
                self.events.append(Event(step.index, "character", speaker=name, beats=turn.beats,
                                         inner=turn.inner, silent=turn.silent))
