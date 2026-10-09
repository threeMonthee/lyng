"""解析模型输出。不合规一律抛 FormatError，由调用方记为技术失败。

输出与感知材料用同一套“标签：内容”写法：人物写“陆雪琪（行）：”“陆雪琪（说）：”“陆雪琪（心声）：”，
叙事者每轮写“叙事：”“反应：”两行，开场与收尾直接写正文。
v1 的输出用【行】一类标记、感知材料却用“陆雪琪（行）：”，两套写法并存，模型会混用，所以统一。
"""

import re
from dataclasses import dataclass

# 一行的开头：叙事： / 反应： / 判定： / 名字（行|说|心声）：
_LABEL = re.compile(r"^(?:(叙事|反应|判定)|([^（）：\s]{1,8})（(行|说|心声)）)：(.*)$")


class FormatError(ValueError):
    pass


@dataclass(frozen=True)
class Turn:
    """一个 AI 人物的一次反应。beats 按先后排列，每项为 ("行" | "说", 文本)。"""

    beats: tuple[tuple[str, str], ...]
    inner: str
    silent: bool


def _lines(text: str) -> list[tuple[str | None, str, str]]:
    """拆成 (名字或 None, 标签, 内容)。内容可以跨行，直到下一个带标签的行。"""
    if not text.strip():
        raise FormatError("空响应")
    out: list[list] = []
    for line in text.strip().splitlines():
        m = _LABEL.match(line.strip())
        if m:
            out.append([None, m.group(1), m.group(4)] if m.group(1) else [m.group(2), m.group(3), m.group(4)])
        elif not out:
            if line.strip():
                raise FormatError("标签之外有文字")
        else:
            out[-1][2] += "\n" + line
    return [(name, tag, body.strip()) for name, tag, body in out]


def parse_turn(text: str, name: str) -> Turn:
    """（行）（说）可有可无，（心声）必写且在最后；只有（心声）就是此刻不说也不做。"""
    lines = _lines(text)
    if any(n is None for n, _, _ in lines):
        raise FormatError("人物只写自己的（行）（说）（心声）")
    if others := sorted({n for n, _, _ in lines if n != name}):
        raise FormatError(f"写了别人的言行：{'、'.join(others)}")
    tags = [t for _, t, _ in lines]
    if tags.count("心声") != 1 or tags[-1] != "心声":
        raise FormatError("（心声）必须恰好一行且放在最后")
    inner = lines[-1][2]
    if not inner:
        raise FormatError("（心声）为空")
    body = tuple((t, b) for _, t, b in lines[:-1])
    if any(not b for _, b in body):
        raise FormatError("（行）或（说）为空")
    return Turn(body, inner, not body)


def parse_narration(text: str) -> str:
    """开场与收尾：直接是叙事文字，不带标签。"""
    if not text.strip():
        raise FormatError("空响应")
    if any(_LABEL.match(line.strip()) for line in text.splitlines()):
        raise FormatError("开场与收尾直接写叙事文字，不加标签")
    return text.strip()


def parse_round(text: str, candidates: list[str], require_something: bool) -> tuple[str | None, list[str]]:
    """叙事者每轮的幕后决定：恰好一行“叙事：”、一行“反应：”，没有就写“无”。"""
    lines = _lines(text)
    if any(n is not None for n, _, _ in lines):
        raise FormatError("叙事者只写“叙事：”“反应：”两行，不写人物的言行")
    if sorted(t for _, t, _ in lines) != ["反应", "叙事"]:
        raise FormatError("需要恰好一行“叙事：”和一行“反应：”")
    body = {t: b for _, t, b in lines}
    if not body["叙事"] or not body["反应"]:
        raise FormatError("“叙事：”“反应：”不能为空；没有就写“无”")
    narration = None if body["叙事"] == "无" else body["叙事"]
    raw = body["反应"].rstrip("。")
    names = [] if raw == "无" else [n for n in re.split(r"[、，,\s]+", raw) if n]
    if any(n not in candidates for n in names) or len(set(names)) != len(names):
        raise FormatError(f"“反应：”只能是 {'、'.join(candidates)} 或“无”，且不重复")
    if require_something and not narration and not names:
        raise FormatError("推进时必须叙事或至少有一人反应")
    return narration, names


def parse_consent(text: str) -> bool:
    """返回 True 表示判定为拒绝。"""
    lines = _lines(text)
    if len(lines) != 1 or lines[0][:2] != (None, "判定") or lines[0][2] not in ("拒绝", "未拒绝"):
        raise FormatError("只写一行“判定：拒绝”或“判定：未拒绝”")
    return lines[0][2] == "拒绝"
