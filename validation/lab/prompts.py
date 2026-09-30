"""所有模型共用的同一版 Prompt。改动这里会改变快照，受影响的实验结论不继承。

本场发生的事一律作为标明说话人的感知材料放进同一条 user 消息，不作为对话历史回放。
"""

from dataset import Dataset, Scene, Step
from events import Event

SCALE_TEXT = {
    "含蓄": "亲密与暴力都可以写，情感与张力写足，最露骨处留白。",
    "成人向": "这里的人都是成年人。亲密与暴力都可以写，不必留白。",
}

TONE_TEXT = {
    "日常相处": "日常相处：事件轻，重心在人物之间。",
    "冒险": "冒险：张力要足，世界的动静多一些。",
}

CHARACTER_SYSTEM = """你是{name}。

下面是你所在的世界和你的一生。你就是这个人：用自己的心去感受眼前发生的事，照自己的样子去说、去做。

你的性子是底色，不是铠甲。你会被在乎的人打动，也会生气、心软、犯错；你可以坚持自己的立场，也可以一句话不说。你只知道自己经历过、看到过、听到过的事，不知道别人心里在想什么。

【世界】
{world}

【你的一生】
{profile}
{private}
【分寸】
{scale}"""

CHARACTER_USER = """{previous}【这一场到现在】
{perception}

【轮到你】
你可以说，可以做，也可以什么都不做。

【怎么写】
只用下面的标记，每个标记另起一行：
【行】你的动作、神情，用第三人称写。
【说】你说出口的话，只写话本身。
【心声】此刻没说出口的一两句话。
【行】和【说】可以有多个，按先后排列；【心声】只写一次，放在最后。
如果此刻你既不说也不做，写：
【不开口】
【心声】……
只写你自己，不写别人的言行；标记以外不写任何文字。"""

NARRATOR_SYSTEM = """你是这个世界的叙事者。你不是故事里的人物，不和任何人物建立关系。

你掌管世界：描写环境，代演路人，推进世界里的事件，裁定行动的结果。人物掌管自己：你不替任何人物说话、做决定，也不写他们心里的感受。人物可以无视世界里发生的事，无视的后果由你裁定。

你只在世界需要时出声：开场、环境或时间有了变化、世界里发生了事、行动的后果需要裁定、收尾。人物之间你来我往的时候，你保持沉默。

你要会讲故事：让世界有自己的动静——暗流、伏笔、意外、有分量的选择——而不是只回应眼前的人；按这一场的基调拿捏节奏，不为热闹打断人物之间的时刻。

这一场里不要裁定任何人物死去或永久失去；需要时换一个不致命的结果。

【世界】
{world}

【在场的人物】
{profiles}
{control}
【基调】
{tone}

【分寸】
{scale}

【开场处境】
{opening}"""

NARRATOR_OPENING_USER = """写这一场的开场。

只使用【开场处境】里给的内容；没有给的共同经历、地点和对白保持未指定，不替人物补写过去。不替任何人物说话，不写人物心里的感受。

格式：
【叙事】开场文字
标记以外不写任何文字。"""

NARRATOR_ROUND_USER = """【这一场到现在】
{perception}

【刚刚】
{latest}

请决定两件事。
一、世界此刻需不需要你出声。环境或时间有了变化、世界里发生了事、刚才的动作结果不确定需要裁定（用一两句写出结果）时才出声。递茶、牵手这类结果不言自明的动作，以及人物之间你来我往的时候，保持沉默。
二、在场的人物里，谁会对刚刚发生的事有反应，按开口的先后排列。可以几个人，也可以没有人。你只写名字，他们自己决定怎么反应。可选的人物：{candidates}。{advance}

格式：
【叙事】……（不需要出声就不写这一行）
【反应】名字、名字（没有人就写：无）
标记以外不写任何文字。"""

ADVANCE_RULE = "\n这一轮要让这一场往前走一段：你出声，或者至少让一个人物有反应。"

NARRATOR_CLOSING_USER = """【这一场到现在】
{perception}

这一场到这里收尾。用一两句话收住这一场，可以留下一个未了的悬念或一件没说完的事，让人想继续。不替任何人物说话，不写人物心里的感受。

格式：
【叙事】……
标记以外不写任何文字。"""

CONSENT_SYSTEM = """你负责检查一件事：一场戏里，某个人物有没有拒绝过另一个人物的亲近。你只做判断，不写故事。"""

CONSENT_USER = """【这一场到现在】
{perception}

【要判断的事】
在上面的经过里，{targets}有没有拒绝过{actor}的亲近？明说不要、躲开、推开、抽回手，或者心里不愿意，都算拒绝。只是害羞、犹豫，没有表示不愿意，不算拒绝。拿不准时，算拒绝。

格式：
【判定】拒绝
或
【判定】未拒绝
标记以外不写任何文字。"""

EMPTY = "（还没有发生任何事）"


def perceive(events: list[Event], viewer: str | None = None, show_inner: frozenset[str] = frozenset()) -> str:
    """把公开发生的事写成标明说话人的感知材料。

    viewer 是正在感知的人物，自己的言行标为“你自己”，也能看到自己的心声；
    show_inner 额外列出能看到心声的人物（仅供同意检查使用）。
    """
    lines = []
    for e in events:
        if not e.public:
            continue
        if e.kind == "narration":
            lines.append(f"叙事：{e.text}")
        elif e.kind == "user":
            if e.action == "静候":
                lines.append(f"{e.speaker}没有开口。")
            else:
                lines.append(f"{e.speaker}（{'说' if e.action == '说' else '行'}）：{e.text}")
        else:
            me = e.speaker == viewer
            who = f"{e.speaker}（你自己）" if me else e.speaker
            prefix = "你自己，" if me else ""
            if e.silent:
                lines.append(f"{who}没有开口。")
            for tag, text in e.beats:
                lines.append(f"{e.speaker}（{prefix}{tag}）：{text}")
            if me or e.speaker in show_inner:
                lines.append(f"{e.speaker}（{prefix}心里）：{e.inner}")
    return "\n".join(lines) or EMPTY


def character_messages(
    ds: Dataset,
    scene: Scene,
    scale: str,
    name: str,
    events: list[Event],
    previous: list[Event] | None = None,
) -> list[dict]:
    private = scene.private.get(name)
    system = CHARACTER_SYSTEM.format(
        name=name,
        world=ds.world,
        profile=ds.profiles[name],
        private=f"\n【这一场之前】\n{private}\n" if private else "",
        scale=SCALE_TEXT[scale],
    )
    prev = f"【上一场】\n{perceive(previous, name)}\n\n" if previous else ""
    user = CHARACTER_USER.format(previous=prev, perception=perceive(events, name))
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]


def narrator_system(ds: Dataset, scene: Scene, scale: str) -> str:
    profiles = "\n\n".join(ds.profiles[n] for n in scene.present)
    if scene.possessed:
        p = scene.possessed
        control = f"\n【{p}】\n{p}的言行只以给出的为准：你不替{p}说话、做动作或做决定，也不写{p}的感受。\n"
    else:
        control = f"\n【镜头】\n这一场跟随{scene.follow}。\n"
    return NARRATOR_SYSTEM.format(
        world=ds.world,
        profiles=profiles,
        control=control,
        tone=TONE_TEXT[scene.tone],
        scale=SCALE_TEXT[scale],
        opening=scene.opening,
    )


def narrator_opening_messages(ds: Dataset, scene: Scene, scale: str) -> list[dict]:
    return [
        {"role": "system", "content": narrator_system(ds, scene, scale)},
        {"role": "user", "content": NARRATOR_OPENING_USER},
    ]


def latest_text(scene: Scene, step: Step) -> str:
    p = scene.possessed
    return {
        "说": lambda: f"{p}（说）：{step.text}",
        "做": lambda: f"{p}（行）：{step.text}",
        "静候": lambda: f"{p}没有开口。",
        "推进": lambda: "没有新的动作。",
    }[step.kind]()


def narrator_round_messages(ds: Dataset, scene: Scene, scale: str, events: list[Event], step: Step) -> list[dict]:
    user = NARRATOR_ROUND_USER.format(
        perception=perceive(events),
        latest=latest_text(scene, step),
        candidates="、".join(scene.ai_characters),
        advance=ADVANCE_RULE if step.kind == "推进" else "",
    )
    return [{"role": "system", "content": narrator_system(ds, scene, scale)}, {"role": "user", "content": user}]


def narrator_closing_messages(ds: Dataset, scene: Scene, scale: str, events: list[Event]) -> list[dict]:
    return [
        {"role": "system", "content": narrator_system(ds, scene, scale)},
        {"role": "user", "content": NARRATOR_CLOSING_USER.format(perception=perceive(events))},
    ]


def consent_messages(scene: Scene, events: list[Event]) -> list[dict]:
    targets = scene.ai_characters
    user = CONSENT_USER.format(
        perception=perceive(events, show_inner=frozenset(targets)),
        targets="、".join(targets),
        actor=scene.possessed,
    )
    return [{"role": "system", "content": CONSENT_SYSTEM}, {"role": "user", "content": user}]
