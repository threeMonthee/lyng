"""解析模型输出的标记文本。不合规一律抛 FormatError，由调用方记为技术失败。"""

import re
from dataclasses import dataclass

_TAG = re.compile(r"^【(.+?)】(.*)$")


class FormatError(ValueError):
    pass


@dataclass(frozen=True)
class Turn:
    """一个 AI 人物的一次反应。beats 按先后排列，每项为 ("行" | "说", 文本)。"""

    beats: tuple[tuple[str, str], ...]
    inner: str
    silent: bool


def _blocks(text: str, allowed: set[str]) -> list[tuple[str, str]]:
    if not text.strip():
        raise FormatError("空响应")
    blocks: list[list[str]] = []
    for line in text.strip().splitlines():
        m = _TAG.match(line.strip())
        if m:
            if m.group(1) not in allowed:
                raise FormatError(f"未知标记【{m.group(1)}】")
            blocks.append([m.group(1), m.group(2)])
        elif not blocks:
            if line.strip():
                raise FormatError("标记之外有文字")
        else:
            blocks[-1][1] += "\n" + line
    return [(tag, body.strip()) for tag, body in blocks]


def parse_turn(text: str) -> Turn:
    blocks = _blocks(text, {"行", "说", "心声", "不开口"})
    tags = [t for t, _ in blocks]
    if tags.count("心声") != 1 or tags[-1] != "心声":
        raise FormatError("【心声】必须恰好一次且放在最后")
    inner = blocks[-1][1]
    if not inner:
        raise FormatError("【心声】为空")
    body = blocks[:-1]
    if [t for t, _ in body] == ["不开口"]:
        if body[0][1]:
            raise FormatError("【不开口】后面不应有文字")
        return Turn((), inner, True)
    if not body or any(t == "不开口" for t, _ in body):
        raise FormatError("要么写【行】/【说】，要么只写【不开口】")
    if any(not b for _, b in body):
        raise FormatError("【行】或【说】为空")
    return Turn(tuple(body), inner, False)


def parse_narration(text: str) -> str:
    blocks = _blocks(text, {"叙事"})
    if len(blocks) != 1 or not blocks[0][1]:
        raise FormatError("需要恰好一段非空的【叙事】")
    return blocks[0][1]


def parse_round(text: str, candidates: list[str], require_something: bool) -> tuple[str | None, list[str]]:
    """叙事者每轮的幕后决定：要不要出声，谁有反应。"""
    blocks = _blocks(text, {"叙事", "反应"})
    tags = [t for t, _ in blocks]
    if tags.count("反应") != 1 or tags.count("叙事") > 1:
        raise FormatError("需要恰好一个【反应】，至多一个【叙事】")
    body = dict(blocks)
    narration = body.get("叙事")
    if narration is not None and not narration:
        raise FormatError("【叙事】为空；不需要出声时应省略这一行")
    raw = body["反应"]
    names = [] if raw == "无" else [n for n in re.split(r"[、，,\s]+", raw) if n]
    if not raw or any(n not in candidates for n in names) or len(set(names)) != len(names):
        raise FormatError(f"【反应】只能是 {'、'.join(candidates)} 或“无”，且不重复")
    if require_something and not narration and not names:
        raise FormatError("推进时必须叙事或至少有一人反应")
    return narration, names


def parse_consent(text: str) -> bool:
    """返回 True 表示判定为拒绝。"""
    blocks = _blocks(text, {"判定"})
    if len(blocks) != 1 or blocks[0][1] not in ("拒绝", "未拒绝"):
        raise FormatError("【判定】只能是“拒绝”或“未拒绝”")
    return blocks[0][1] == "拒绝"
