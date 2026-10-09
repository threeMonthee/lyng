"""Prompt 快照测试。改动 Prompt 后用 UPDATE_SNAPSHOTS=1 重新生成，并在提交里说明改了什么。"""

import os
import unittest
from pathlib import Path

from tests.support import DS

import prompts
from dataset import Step
from events import Event

SNAP_DIR = Path(__file__).resolve().parent / "snapshots"

A2_EVENTS = [
    Event(0, "narration", text="长街上人来人往。"),
    Event(1, "user", speaker="张小凡", action="静候"),
    Event(1, "character", speaker="碧瑶", beats=(("行", "碧瑶挽住了张小凡的胳膊。"), ("说", "哟，这不是陆师姐么？")),
          inner="碧瑶的秘密心声"),
    Event(1, "character", speaker="陆雪琪", beats=(("行", "陆雪琪停住了脚步。"),), inner="陆雪琪的秘密心声"),
    Event(2, "user", speaker="张小凡", action="说", text="雪琪。"),
    Event(2, "character", speaker="陆雪琪", silent=True, inner="陆雪琪第二句心声"),
    Event(2, "failure", speaker="碧瑶", text="技术失败：空响应（已重试一次）"),
]


def dump(messages: list[dict]) -> str:
    return "".join(f"=== {m['role']} ===\n{m['content']}\n" for m in messages)


class PromptSnapshotTest(unittest.TestCase):
    def check(self, name: str, messages: list[dict]) -> None:
        path = SNAP_DIR / f"{name}.txt"
        actual = dump(messages)
        if os.environ.get("UPDATE_SNAPSHOTS") or not path.exists():
            path.write_text(actual, encoding="utf-8")
            if not os.environ.get("UPDATE_SNAPSHOTS"):
                self.fail(f"新建了快照 {path.name}，请检查后重跑")
        self.assertEqual(path.read_text(encoding="utf-8"), actual, f"快照 {path.name} 不一致")

    def test_character(self):
        self.check("character_A2_lu", prompts.character_messages(DS, DS.scenes["A2"], "含蓄", "陆雪琪", A2_EVENTS))

    def test_character_with_previous_scene(self):
        now = [Event(0, "narration", text="渡口，傍晚。")]
        self.check("character_A3_lu", prompts.character_messages(DS, DS.scenes["A3"], "含蓄", "陆雪琪", now, A2_EVENTS))

    def test_character_adult_scale(self):
        self.check("character_C1_adult", prompts.character_messages(DS, DS.scenes["C1"], "成人向", "陆雪琪", []))

    def test_narrator_opening(self):
        self.check("narrator_opening_A1", prompts.narrator_opening_messages(DS, DS.scenes["A1"], "含蓄"))

    def test_narrator_round(self):
        step = Step(3, "说", "碧瑶，你先回山海苑，我随后就到。")
        self.check("narrator_round_A2", prompts.narrator_round_messages(DS, DS.scenes["A2"], "含蓄", A2_EVENTS, step))

    def test_narrator_round_observer(self):
        self.check("narrator_round_B3", prompts.narrator_round_messages(DS, DS.scenes["B3"], "含蓄", [], Step(1, "推进")))

    def test_narrator_closing(self):
        self.check("narrator_closing_A2", prompts.narrator_closing_messages(DS, DS.scenes["A2"], "含蓄", A2_EVENTS))

    def test_consent(self):
        events = [
            Event(0, "narration", text="木屋里炉火正旺。"),
            Event(3, "user", speaker="张小凡", action="做", text="握住她的手。"),
            Event(3, "character", speaker="陆雪琪", beats=(("行", "陆雪琪没有抽回手。"),), inner="陆雪琪的心声"),
        ]
        self.check("consent_C1", prompts.consent_messages(DS.scenes["C1"], events))


class LengthRuleTest(unittest.TestCase):
    """圣经 §4.7“篇幅跟着场景走”写进了人物与叙事者的每一种 Prompt。"""

    def test_character_prompt_carries_length_rule(self):
        for scene in DS.scenes.values():
            for name in scene.ai_characters:
                text = dump(prompts.character_messages(DS, scene, scene.scales[0], name, []))
                self.assertIn("以说为主，动作一两笔带过", text)
                self.assertIn("亲密或打斗的时候，话少，动作与细节写足", text)

    def test_narrator_prompts_carry_length_rule(self):
        scene = DS.scenes["B1"]
        system = prompts.narrator_system(DS, scene, "含蓄")
        self.assertIn("每次出声只写一两句，点到为止", system)
        for messages in (prompts.narrator_opening_messages(DS, scene, "含蓄"),
                         prompts.narrator_round_messages(DS, scene, "含蓄", [], scene.script[0]),
                         prompts.narrator_closing_messages(DS, scene, "含蓄", [])):
            self.assertIn("一两句", messages[-1]["content"])


if __name__ == "__main__":
    unittest.main()
