"""人物只看到自己的档案、世界、自己的场前经历和本场公开发生的事。"""

import unittest

from tests.support import DS, RecordingFake

import prompts
from scene import Ledger, SceneRun
from tests.test_prompts import A2_EVENTS


def joined(messages):
    return "\n".join(m["content"] for m in messages)


class PromptVisibilityTest(unittest.TestCase):
    def test_character_cannot_see_others_inner_voice(self):
        text = joined(prompts.character_messages(DS, DS.scenes["A2"], "含蓄", "陆雪琪", A2_EVENTS))
        self.assertNotIn("碧瑶的秘密心声", text)
        self.assertIn("陆雪琪的秘密心声", text)
        self.assertIn("哟，这不是陆师姐么？", text)

    def test_character_cannot_see_others_private_or_profile(self):
        scene = DS.scenes["A2"]
        text = joined(prompts.character_messages(DS, scene, "含蓄", "陆雪琪", A2_EVENTS))
        self.assertIn(scene.private["陆雪琪"], text)
        self.assertNotIn(scene.private["碧瑶"], text)
        self.assertNotIn(DS.profiles["碧瑶"], text)
        self.assertNotIn(DS.profiles["张小凡"], text)

    def test_character_cannot_see_raw_opening(self):
        scene = DS.scenes["A1"]
        text = joined(prompts.character_messages(DS, scene, "含蓄", "陆雪琪", []))
        self.assertNotIn(scene.opening, text)

    def test_technical_failure_is_invisible_not_silence(self):
        text = joined(prompts.character_messages(DS, DS.scenes["A2"], "含蓄", "陆雪琪", A2_EVENTS))
        self.assertNotIn("技术失败", text)
        self.assertNotIn("碧瑶没有开口", text)

    def test_narrator_sees_no_inner_voice_or_private(self):
        scene = DS.scenes["A5"]
        for messages in (
            prompts.narrator_opening_messages(DS, scene, "含蓄"),
            prompts.narrator_round_messages(DS, scene, "含蓄", A2_EVENTS, scene.script[0]),
            prompts.narrator_closing_messages(DS, scene, "含蓄", A2_EVENTS),
        ):
            text = joined(messages)
            self.assertNotIn("心声", text.replace("【心声】", ""))
            self.assertNotIn(scene.private["陆雪琪"], text)


class SceneVisibilityTest(unittest.TestCase):
    def test_whole_scene_never_leaks_inner_voice(self):
        """跑完整的 A2 群戏：每个人物的每次调用里都没有另一人的心声。"""
        model = RecordingFake()
        result = SceneRun(DS, DS.scenes["A2"], "含蓄", model, Ledger(1.0), 1000).run()
        self.assertEqual(result.status, "completed")
        inner = {n: [e.inner for e in result.events if e.kind == "character" and e.speaker == n] for n in ("陆雪琪", "碧瑶")}
        self.assertTrue(inner["陆雪琪"] and inner["碧瑶"])
        for me, other in (("陆雪琪", "碧瑶"), ("碧瑶", "陆雪琪")):
            for prompt in model.prompts_for("character", me):
                for secret in inner[other]:
                    self.assertNotIn(secret, prompt)
        for kind in ("round", "closing"):
            for prompt in model.prompts_for(kind):
                for secret in inner["陆雪琪"] + inner["碧瑶"]:
                    self.assertNotIn(secret, prompt)

    def test_previous_scene_carries_only_own_inner_voice(self):
        a2 = SceneRun(DS, DS.scenes["A2"], "含蓄", RecordingFake(), Ledger(1.0), 1000).run()
        bi_inner = [e.inner for e in a2.events if e.speaker == "碧瑶" and e.kind == "character"]
        lu_inner = [e.inner for e in a2.events if e.speaker == "陆雪琪" and e.kind == "character"]
        model = RecordingFake()
        SceneRun(DS, DS.scenes["A3"], "含蓄", model, Ledger(1.0), 1000, previous=a2.events).run()
        prompt = model.prompts_for("character", "陆雪琪")[0]
        self.assertIn("【上一场】", prompt)
        for s in lu_inner:
            self.assertIn(s, prompt)
        for s in bi_inner:
            self.assertNotIn(s, prompt)


if __name__ == "__main__":
    unittest.main()
