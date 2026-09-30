"""一场戏里发生的事。"""

from dataclasses import asdict, dataclass


@dataclass(frozen=True)
class Event:
    round: int
    kind: str  # narration | user | character | failure | note
    speaker: str | None = None
    action: str | None = None  # 仅 user：说 / 做 / 静候
    text: str = ""
    beats: tuple[tuple[str, str], ...] = ()
    inner: str | None = None
    silent: bool = False

    @property
    def public(self) -> bool:
        """技术失败与幕后说明不属于世界，任何人物都感知不到。"""
        return self.kind in ("narration", "user", "character")

    def to_dict(self) -> dict:
        d = {k: v for k, v in asdict(self).items() if v not in (None, "", (), False)}
        d["round"] = self.round
        if self.beats:
            d["beats"] = [list(b) for b in self.beats]
        return d

    @staticmethod
    def from_dict(d: dict) -> "Event":
        d = dict(d)
        d["beats"] = tuple(tuple(b) for b in d.get("beats", ()))
        return Event(**d)
