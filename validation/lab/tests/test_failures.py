"""空响应、截断、格式不合规记为技术失败：只重试一次，绝不当作人物沉默。"""

import unittest

from tests.support import DS, RecordingFake, ok

from models import Completion
from scene import Ledger, SceneRun

GOOD_TURN = "【行】陆雪琪看了他一眼。\n【说】嗯。\n【心声】他来了。"


def first_character_call(bad_responses):
    """第一次人物调用依次返回 bad_responses，之后恢复替身默认输出。"""
    state = {"seen": 0}

    def hook(purpose, n):
        if purpose["kind"] == "character" and state["seen"] < len(bad_responses):
            state["seen"] += 1
            return bad_responses[state["seen"] - 1]
        return None

    return hook


def run_a1(hook):
    model = RecordingFake(hook)
    result = SceneRun(DS, DS.scenes["A1"], "含蓄", model, Ledger(1.0), 1000).run()
    return model, result


class TechnicalFailureTest(unittest.TestCase):
    def assert_final_failure(self, bad, reason_part):
        model, result = run_a1(first_character_call([bad, bad]))
        call = next(c for c in result.calls if c.kind == "character")
        self.assertEqual(call.outcome, "failed")
        self.assertEqual(len(call.attempts), 2)
        failures = [e for e in result.events if e.kind == "failure"]
        self.assertEqual(len(failures), 1)
        self.assertEqual(failures[0].speaker, "陆雪琪")
        self.assertIn(reason_part, failures[0].text)
        # 失败的那一轮，陆雪琪不存在任何“沉默”事件
        first_round = failures[0].round
        self.assertFalse([e for e in result.events if e.kind == "character" and e.round == first_round])
        # 之后的调用里，没有人看到她“没有开口”
        later = model.prompts_for("character", "陆雪琪")[2]
        self.assertNotIn("陆雪琪（你自己）没有开口", later)
        self.assertEqual(result.status, "completed")

    def test_empty_response(self):
        self.assert_final_failure(ok("   "), "空响应")

    def test_truncated_response(self):
        self.assert_final_failure(Completion(GOOD_TURN, "length", 0.0001), "截断")

    def test_malformed_response(self):
        self.assert_final_failure(ok("陆雪琪看了他一眼，没有说话。"), "格式不合规")

    def test_missing_inner_voice_is_malformed(self):
        self.assert_final_failure(ok("【说】嗯。"), "格式不合规")

    def test_one_retry_recovers(self):
        _, result = run_a1(first_character_call([ok("")]))
        call = next(c for c in result.calls if c.kind == "character")
        self.assertEqual(call.outcome, "recovered")
        self.assertEqual(len(call.attempts), 2)
        self.assertFalse([e for e in result.events if e.kind == "failure"])

    def test_opening_failure_ends_scene(self):
        def hook(purpose, n):
            return ok("") if purpose["kind"] == "opening" else None

        model, result = run_a1(hook)
        self.assertEqual(result.status, "failed")
        self.assertEqual(len(model.log), 2)
        self.assertEqual([e.kind for e in result.events], ["failure"])

    def test_explicit_silence_is_not_failure(self):
        _, result = run_a1(first_character_call([ok("【不开口】\n【心声】随他去。")]))
        call = next(c for c in result.calls if c.kind == "character")
        self.assertEqual(call.outcome, "ok")
        silent = [e for e in result.events if e.kind == "character" and e.silent]
        self.assertEqual(silent[0].inner, "随他去。")


if __name__ == "__main__":
    unittest.main()
