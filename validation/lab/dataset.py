"""读取盲测集：世界、人物档案、场景。格式见 validation/README.txt。"""

import hashlib
import re
from dataclasses import dataclass, field
from pathlib import Path

DATA_DIR = Path(__file__).resolve().parent.parent
SCALES = ("含蓄", "成人向")
TONES = ("日常相处", "冒险")


class DatasetError(ValueError):
    pass


@dataclass(frozen=True)
class Step:
    index: int
    kind: str  # 说 / 做 / 静候 / 推进
    text: str = ""
    needs_consent: bool = False


@dataclass
class Scene:
    id: str
    name: str
    possessed: str | None  # 附身的人物；旁观时为 None
    follow: str | None  # 旁观时跟随的人物
    present: list[str]
    tone: str
    scales: list[str]
    opening: str
    private: dict[str, str]
    previous: str | None  # 上场经历来自哪个场景（仅 A3）
    script: list[Step]
    notes: str

    @property
    def ai_characters(self) -> list[str]:
        return [n for n in self.present if n != self.possessed]


@dataclass
class Dataset:
    world: str
    profiles: dict[str, str]
    scenes: dict[str, Scene] = field(default_factory=dict)
    digest: str = ""


_TAG_LINE = re.compile(r"^【(.+?)】(.*)$")
_STEP_SPEECH = re.compile(r"^(\d+)\s+(〔需同意〕)?(说|做)：(.+)$")
_STEP_BARE = re.compile(r"^(\d+)\s+(静候|推进)$")


def _sections(text: str) -> tuple[dict[str, str], dict[str, str]]:
    fields, sections, current = {}, {}, None
    for line in text.splitlines():
        m = _TAG_LINE.match(line)
        if m and m.group(2).strip():
            fields[m.group(1)] = m.group(2).strip()
            current = None
        elif m:
            current = m.group(1)
            sections[current] = ""
        elif current is not None:
            sections[current] += line + "\n"
    return fields, {k: v.strip() for k, v in sections.items()}


def _parse_private(body: str, present: list[str], scene_id: str) -> dict[str, str]:
    if body in ("", "无"):
        return {}
    blocks, name = {}, None
    for line in body.splitlines():
        head = line.strip()
        if head.endswith("：") and head[:-1] in present:
            name = head[:-1]
            blocks[name] = ""
        elif name is None:
            if head:
                raise DatasetError(f"{scene_id}：场前经历缺少“名字：”开头")
        else:
            blocks[name] += line + "\n"
    return {k: v.strip() for k, v in blocks.items()}


def _parse_script(body: str, scene_id: str) -> list[Step]:
    steps = []
    for line in filter(None, (l.strip() for l in body.splitlines())):
        if m := _STEP_SPEECH.match(line):
            steps.append(Step(int(m.group(1)), m.group(3), m.group(4).strip(), bool(m.group(2))))
        elif m := _STEP_BARE.match(line):
            steps.append(Step(int(m.group(1)), m.group(2)))
        else:
            raise DatasetError(f"{scene_id}：无法解析脚本行：{line}")
    if [s.index for s in steps] != list(range(1, len(steps) + 1)):
        raise DatasetError(f"{scene_id}：脚本轮次编号不连续")
    return steps


def parse_scene(text: str) -> Scene:
    fields, sections = _sections(text)
    scene_id = fields.get("编号", "?")
    position = fields["你的位置"]
    possessed = follow = None
    if position.startswith("附身"):
        possessed = position.removeprefix("附身")
    elif m := re.match(r"旁观，跟随(.+)$", position):
        follow = m.group(1)
    else:
        raise DatasetError(f"{scene_id}：无法解析“你的位置”：{position}")

    present = [re.sub(r"（.*?）", "", n).strip() for n in fields["在场人物"].split("、")]
    if possessed and possessed not in present:
        raise DatasetError(f"{scene_id}：附身人物不在场")
    scales = [s for s in SCALES if s in fields["内容尺度"]]
    tone = fields["基调"]
    if tone not in TONES or not scales:
        raise DatasetError(f"{scene_id}：基调或内容尺度无法识别")

    previous = None
    if prev_body := sections.get("上场经历"):
        m = re.search(r"([A-C]\d) 完整记录", prev_body)
        if not m:
            raise DatasetError(f"{scene_id}：上场经历未指明来源场景")
        previous = m.group(1)

    script = _parse_script(sections["用户脚本"], scene_id)
    allowed = {"说", "做", "静候"} if possessed else {"推进"}
    if bad := [s for s in script if s.kind not in allowed]:
        raise DatasetError(f"{scene_id}：第 {bad[0].index} 轮“{bad[0].kind}”与你的位置不符")

    return Scene(
        id=scene_id,
        name=fields["名称"],
        possessed=possessed,
        follow=follow,
        present=present,
        tone=tone,
        scales=scales,
        opening=sections["开场"],
        private=_parse_private(sections.get("场前经历", ""), present, scene_id),
        previous=previous,
        script=script,
        notes=sections.get("评估要点", ""),
    )


def load(data_dir: Path = DATA_DIR) -> Dataset:
    digest = hashlib.sha256()
    files = sorted(
        [data_dir / "world.txt"]
        + list((data_dir / "characters").glob("*.txt"))
        + list((data_dir / "scenes").glob("*.txt"))
    )
    for f in files:
        digest.update(f.relative_to(data_dir).as_posix().encode() + b"\0" + f.read_bytes())

    world = (data_dir / "world.txt").read_text(encoding="utf-8").strip()
    profiles = {}
    for f in sorted((data_dir / "characters").glob("*.txt")):
        text = f.read_text(encoding="utf-8").strip()
        profiles[text.splitlines()[0].strip()] = text

    ds = Dataset(world=world, profiles=profiles, digest=digest.hexdigest())
    for f in sorted((data_dir / "scenes").glob("*.txt")):
        scene = parse_scene(f.read_text(encoding="utf-8"))
        if missing := [n for n in scene.present if n not in profiles]:
            raise DatasetError(f"{scene.id}：缺少人物档案 {missing}")
        ds.scenes[scene.id] = scene
    for scene in ds.scenes.values():
        if scene.previous and scene.previous not in ds.scenes:
            raise DatasetError(f"{scene.id}：上场经历引用了不存在的场景 {scene.previous}")
    return ds
