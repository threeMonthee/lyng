"""人物拒绝后，带〔需同意〕的轮次改为静候，脚本不再往前推。"""

import unittest

from tests.support import DS, RecordingFake, ok

from scene import Ledger, SceneRun


def run_c1(consent_answers):
    answers = list(consent_answers)

    def hook(purpose, n):
        if purpose["kind"] == "consent":
            return answers.pop(0) if answers else ok("【判定】未拒绝")
        return None

    model = RecordingFake(hook)
    result = SceneRun(DS, DS.scenes["C1"], "含蓄", model, Ledger(1.0), 1000).run()
    return model, result


def user_actions(result):
    return {e.round: (e.action, e.text) for e in result.events if e.kind == "user"}


class ConsentGateTest(unittest.TestCase):
    def test_no_refusal_plays_full_script(self):
        model, result = run_c1([])
        acts = user_actions(result)
        self.assertEqual(acts[3], ("做", "握住她的手。"))
        self.assertEqual(acts[6], ("做", "低头吻她。"))
        self.assertEqual(acts[8], ("说", "雪琪……可以吗？"))
        self.assertEqual(len(model.prompts_for("consent")), 3)

    def test_refusal_turns_every_later_gated_step_into_waiting(self):
        model, result = run_c1([ok("【判定】未拒绝"), ok("【判定】拒绝")])
        acts = user_actions(result)
        self.assertEqual(acts[3], ("做", "握住她的手。"))
        self.assertEqual(acts[6], ("静候", ""))
        self.assertEqual(acts[8], ("静候", ""))
        self.assertEqual(len(model.prompts_for("consent")), 2)
        notes = [e for e in result.events if e.kind == "note"]
        self.assertEqual([e.round for e in notes], [6, 8])
        for prompt in model.prompts_for("character", "陆雪琪"):
            self.assertNotIn("低头吻她", prompt)
            self.assertNotIn("可以吗", prompt)

    def test_consent_check_failure_is_treated_as_refusal(self):
        model, result = run_c1([ok(""), ok("随便写的")])
        acts = user_actions(result)
        self.assertEqual({acts[3][0], acts[6][0], acts[8][0]}, {"静候"})
        failures = [e for e in result.events if e.kind == "failure"]
        self.assertEqual(failures[0].speaker, "同意检查")

    def test_consent_check_sees_her_inner_voice(self):
        model, _ = run_c1([])
        second = model.prompts_for("consent")[1]
        self.assertIn("陆雪琪（心里）", second)


if __name__ == "__main__":
    unittest.main()
