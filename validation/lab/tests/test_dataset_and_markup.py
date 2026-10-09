import unittest

from tests.support import DS

from events import Event
from markup import FormatError, parse_consent, parse_narration, parse_pair_round, parse_round, parse_turn
from prompts import perceive


class DatasetTest(unittest.TestCase):
    def test_nine_scenes(self):
        self.assertEqual(sorted(DS.scenes), ["A1", "A2", "A3", "A4", "A5", "B1", "B2", "B3", "C1"])
        self.assertEqual(set(DS.profiles), {"陆雪琪", "张小凡", "碧瑶"})

    def test_scene_shapes(self):
        for scene in DS.scenes.values():
            self.assertGreaterEqual(len(scene.script), 8, scene.id)
            self.assertTrue(scene.ai_characters, scene.id)
            for name in scene.private:
                self.assertIn(name, scene.ai_characters, scene.id)
        self.assertEqual(DS.scenes["A3"].previous, "A2")
        self.assertIsNone(DS.scenes["B3"].possessed)
        self.assertEqual({s.kind for s in DS.scenes["B3"].script}, {"推进"})
        self.assertEqual(DS.scenes["C1"].scales, ["含蓄", "成人向"])
        self.assertEqual([s.index for s in DS.scenes["C1"].script if s.needs_consent], [3, 6, 8])
        self.assertEqual(set(DS.scenes["A2"].private), {"陆雪琪", "碧瑶"})


class MarkupTest(unittest.TestCase):
    def test_turn(self):
        t = parse_turn("陆雪琪（行）：她转过身。\n陆雪琪（说）：你来了。\n陆雪琪（行）：她没有看他。\n陆雪琪（心声）：终于。", "陆雪琪")
        self.assertEqual(t.beats, (("行", "她转过身。"), ("说", "你来了。"), ("行", "她没有看他。")))
        self.assertEqual(t.inner, "终于。")
        self.assertFalse(t.silent)

    def test_inner_voice_only_is_silence(self):
        t = parse_turn("陆雪琪（心声）：随他去。", "陆雪琪")
        self.assertTrue(t.silent)
        self.assertEqual(t.beats, ())

    def test_multiline_block(self):
        t = parse_turn("陆雪琪（说）：第一句。\n第二句。\n陆雪琪（心声）：嗯。", "陆雪琪")
        self.assertEqual(t.beats, (("说", "第一句。\n第二句。"),))

    def test_output_reads_back_as_the_same_perception(self):
        """人物的输出与它读到的感知材料是同一种写法：解析后再写成感知材料，一字不差。"""
        text = "陆雪琪（行）：她把剑收回鞘中。\n陆雪琪（说）：你走吧。\n陆雪琪（心声）：别走。"
        turn = parse_turn(text, "陆雪琪")
        event = Event(1, "character", speaker="陆雪琪", beats=turn.beats, inner=turn.inner, silent=turn.silent)
        self.assertEqual(perceive([event], "陆雪琪"), text)

    def test_turn_rejects(self):
        for bad in (
            "",
            "前言\n陆雪琪（说）：你好。\n陆雪琪（心声）：嗯。",
            "陆雪琪（说）：你好。",
            "陆雪琪（心声）：嗯。\n陆雪琪（说）：你好。",
            "陆雪琪（说）：你好。\n陆雪琪（心声）：嗯。\n陆雪琪（心声）：又一句。",
            "陆雪琪（心声）：",
            "陆雪琪（想）：你好。\n陆雪琪（心声）：嗯。",
            "陆雪琪（说）：\n陆雪琪（心声）：嗯。",
            "【说】你好。\n【心声】嗯。",
            "叙事：雨停了。\n陆雪琪（心声）：嗯。",
        ):
            with self.assertRaises(FormatError, msg=bad):
                parse_turn(bad, "陆雪琪")

    def test_turn_rejects_other_peoples_lines(self):
        with self.assertRaises(FormatError) as ctx:
            parse_turn("陆雪琪（说）：你说。\n碧瑶（说）：我偏不。\n陆雪琪（心声）：嗯。", "陆雪琪")
        self.assertIn("写了别人的言行：碧瑶", str(ctx.exception))

    def test_round(self):
        self.assertEqual(parse_round("叙事：无\n轮到：陆雪琪、碧瑶", ["陆雪琪", "碧瑶"], False), (None, ["陆雪琪", "碧瑶"]))
        self.assertEqual(parse_round("叙事：雨停了。\n轮到：无", ["陆雪琪", "碧瑶"], True), ("雨停了。", []))
        self.assertEqual(parse_round("轮到：碧瑶。\n叙事：无", ["陆雪琪", "碧瑶"], False), (None, ["碧瑶"]))
        for bad in ("叙事：无\n轮到：张小凡", "叙事：雨。", "轮到：陆雪琪", "叙事：无\n轮到：陆雪琪、陆雪琪",
                    "叙事：\n轮到：无", "一、不需要。\n二、陆雪琪", "叙事：无\n陆雪琪（行）：她抬眼。\n轮到：陆雪琪",
                    "叙事：无\n轮到：陆雪琪脚步一顿，没有回头", "叙事：无\n反应：陆雪琪"):
            with self.assertRaises(FormatError, msg=bad):
                parse_round(bad, ["陆雪琪", "碧瑶"], False)
        with self.assertRaises(FormatError):
            parse_round("叙事：无\n轮到：无", ["陆雪琪", "碧瑶"], True)

    def test_round_rejects_possessed_character(self):
        with self.assertRaises(FormatError) as ctx:
            parse_round("叙事：无\n轮到：张小凡、碧瑶", ["陆雪琪", "碧瑶"], False, possessed="张小凡")
        self.assertIn("张小凡是被附身的人物", str(ctx.exception))
        self.assertEqual(parse_round("叙事：无\n轮到：碧瑶", ["陆雪琪", "碧瑶"], False, possessed="张小凡"), (None, ["碧瑶"]))

    def test_pair_round(self):
        self.assertEqual(parse_pair_round("叙事：无"), "")
        self.assertEqual(parse_pair_round("叙事：雪停了。"), "雪停了。")
        for bad in ("", "无", "叙事：", "叙事：无\n轮到：陆雪琪", "叙事：无\n叙事：雪停了。", "雪停了。"):
            with self.assertRaises(FormatError, msg=bad):
                parse_pair_round(bad)

    def test_narration_and_consent(self):
        self.assertEqual(parse_narration("夜深了。\n\n雪还在下。"), "夜深了。\n\n雪还在下。")
        for bad in ("", "叙事：夜深了。", "夜深了。\n陆雪琪（行）：她抬眼。"):
            with self.assertRaises(FormatError, msg=bad):
                parse_narration(bad)
        self.assertTrue(parse_consent("判定：拒绝"))
        self.assertFalse(parse_consent("判定：未拒绝"))
        for bad in ("判定：也许", "拒绝", "判定：拒绝\n判定：未拒绝"):
            with self.assertRaises(FormatError, msg=bad):
                parse_consent(bad)


if __name__ == "__main__":
    unittest.main()
