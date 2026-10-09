"""双人戏（只有一个 AI 人物）不做调度：每一轮她都能接戏，接不接由她决定；群戏才由叙事者决定轮到谁。"""

import unittest

from tests.support import DS, RecordingFake, ok

import prompts
from scene import Ledger, SceneRun


def joined(messages):
    return "\n".join(m["content"] for m in messages)


class PairSceneTest(unittest.TestCase):
    def test_pair_scenes_and_group_scenes(self):
        pairs = sorted(s.id for s in DS.scenes.values() if len(s.ai_characters) == 1)
        self.assertEqual(pairs, ["A1", "A3", "A4", "A5", "B1", "B2", "C1"])
        for sid in pairs:
            text = joined(prompts.narrator_round_messages(DS, DS.scenes[sid], "含蓄", [], DS.scenes[sid].script[0]))
            self.assertNotIn("轮到：", text, sid)
            self.assertIn("只写下面一行", text, sid)
        for sid in ("A2", "B3"):
            text = joined(prompts.narrator_round_messages(DS, DS.scenes[sid], "含蓄", [], DS.scenes[sid].script[0]))
            self.assertIn("轮到：", text, sid)

    def test_she_gets_every_round_even_when_narrator_says_nothing(self):
        model = RecordingFake()
        result = SceneRun(DS, DS.scenes["A1"], "含蓄", model, Ledger(1.0), 1000).run()
        rounds = len(DS.scenes["A1"].script)
        self.assertEqual(result.status, "completed")
        self.assertEqual(len(model.prompts_for("character", "陆雪琪")), rounds)
        self.assertEqual(sorted({e.round for e in result.events if e.kind == "character"}), list(range(1, rounds + 1)))

    def test_she_still_gets_her_turn_when_narrator_round_fails(self):
        def hook(purpose, n):
            return ok("一、不需要。") if purpose["kind"] == "round" else None

        model = RecordingFake(hook)
        result = SceneRun(DS, DS.scenes["A1"], "含蓄", model, Ledger(1.0), 1000).run()
        rounds = len(DS.scenes["A1"].script)
        failures = [e for e in result.events if e.kind == "failure"]
        self.assertEqual(len(failures), rounds)
        self.assertTrue(all(f.speaker == "叙事者" for f in failures))
        self.assertEqual(len(model.prompts_for("character", "陆雪琪")), rounds)

    def test_group_scene_still_scheduled_by_narrator(self):
        def hook(purpose, n):
            if purpose["kind"] == "round":
                return ok("叙事：无\n轮到：碧瑶")
            return None

        model = RecordingFake(hook)
        SceneRun(DS, DS.scenes["A2"], "含蓄", model, Ledger(1.0), 1000).run()
        self.assertEqual(model.prompts_for("character", "陆雪琪"), [])
        self.assertEqual(len(model.prompts_for("character", "碧瑶")), len(DS.scenes["A2"].script))


if __name__ == "__main__":
    unittest.main()
