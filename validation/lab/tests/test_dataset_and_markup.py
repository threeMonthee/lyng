import unittest

from tests.support import DS

from markup import FormatError, parse_consent, parse_narration, parse_round, parse_turn


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
        t = parse_turn("【行】她转过身。\n【说】你来了。\n【行】她没有看他。\n【心声】终于。")
        self.assertEqual(t.beats, (("行", "她转过身。"), ("说", "你来了。"), ("行", "她没有看他。")))
        self.assertEqual(t.inner, "终于。")
        self.assertFalse(t.silent)

    def test_multiline_block(self):
        t = parse_turn("【说】第一句。\n第二句。\n【心声】嗯。")
        self.assertEqual(t.beats, (("说", "第一句。\n第二句。"),))

    def test_turn_rejects(self):
        for bad in (
            "",
            "前言\n【说】你好。\n【心声】嗯。",
            "【说】你好。",
            "【心声】嗯。\n【说】你好。",
            "【说】你好。\n【心声】嗯。\n【心声】又一句。",
            "【不开口】\n【说】你好。\n【心声】嗯。",
            "【想】你好。\n【心声】嗯。",
            "【说】\n【心声】嗯。",
        ):
            with self.assertRaises(FormatError, msg=bad):
                parse_turn(bad)

    def test_round(self):
        self.assertEqual(parse_round("【反应】陆雪琪、碧瑶", ["陆雪琪", "碧瑶"], False), (None, ["陆雪琪", "碧瑶"]))
        self.assertEqual(parse_round("【叙事】雨停了。\n【反应】无", ["陆雪琪"], True), ("雨停了。", []))
        for bad in ("【反应】张小凡", "【叙事】雨。", "【反应】陆雪琪、陆雪琪"):
            with self.assertRaises(FormatError, msg=bad):
                parse_round(bad, ["陆雪琪", "碧瑶"], False)
        with self.assertRaises(FormatError):
            parse_round("【反应】无", ["陆雪琪"], True)

    def test_narration_and_consent(self):
        self.assertEqual(parse_narration("【叙事】夜深了。"), "夜深了。")
        self.assertTrue(parse_consent("【判定】拒绝"))
        self.assertFalse(parse_consent("【判定】未拒绝"))
        with self.assertRaises(FormatError):
            parse_consent("【判定】也许")


if __name__ == "__main__":
    unittest.main()
