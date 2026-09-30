"""请求失败分三类：
- 429 / 5xx：退避后重试一次，仍失败记为技术失败，戏继续；
- 服务商内容审核拒绝：记为“服务商拒绝”，只作废当前场景版本；
- 401、402，或响应成功却拿不到费用：整次运行停止。
"""

import contextlib
import io
import unittest

from tests.support import DS, RecordingFake, ok

import run
from models import FATAL, REFUSED, TRANSIENT, Completion, RequestError
from scene import Ledger, SceneRun


class Sleeps(list):
    def __call__(self, seconds):
        self.append(seconds)


def character_errors(errors):
    """前几次人物调用依次抛出 errors 里的异常（或返回其中的 Completion），之后恢复替身默认输出。"""
    queue = list(errors)

    def hook(purpose, n):
        if purpose["kind"] == "character" and queue:
            item = queue.pop(0)
            if isinstance(item, Exception):
                raise item
            return item
        return None

    return hook


def run_scene(scene_id, hook, scale="含蓄", ledger=None):
    sleeps = Sleeps()
    model = RecordingFake(hook)
    ledger = ledger or Ledger(1.0)
    result = SceneRun(DS, DS.scenes[scene_id], scale, model, ledger, 1000, backoff=7.5, sleep=sleeps).run()
    return model, result, sleeps, ledger


def rate_limited():
    return RequestError(TRANSIENT, "HTTP 429：slow down", 429)


def server_error():
    return RequestError(TRANSIENT, "HTTP 503：unavailable", 503)


def moderation():
    return RequestError(REFUSED, "HTTP 403：flagged", 403)


class TransientErrorTest(unittest.TestCase):
    def test_backoff_then_retry_recovers(self):
        _, result, sleeps, _ = run_scene("A1", character_errors([rate_limited()]))
        call = next(c for c in result.calls if c.kind == "character")
        self.assertEqual(call.outcome, "recovered")
        self.assertEqual(call.attempts[0]["status"], 429)
        self.assertEqual(sleeps, [7.5])
        self.assertEqual(result.status, "completed")
        self.assertFalse([e for e in result.events if e.kind == "failure"])

    def test_second_failure_is_technical_failure_and_scene_continues(self):
        model, result, sleeps, ledger = run_scene("A1", character_errors([server_error(), server_error()]))
        call = next(c for c in result.calls if c.kind == "character")
        self.assertEqual(call.outcome, "failed")
        self.assertEqual(len(call.attempts), 2)
        self.assertEqual(sleeps, [7.5])
        failures = [e for e in result.events if e.kind == "failure"]
        self.assertEqual(len(failures), 1)
        self.assertIn("HTTP 503", failures[0].text)
        self.assertEqual(result.status, "completed")
        # 戏继续：后面的轮次照常调用，收尾也有
        self.assertEqual(result.events[-1].kind, "narration")
        # 失败的请求不计费：总费用只来自成功的调用
        self.assertAlmostEqual(ledger.spent, 0.0001 * (len(model.log) - 2))

    def test_only_one_retry_in_total(self):
        _, result, sleeps, _ = run_scene("A1", character_errors([rate_limited(), ok("没有标记")]))
        call = next(c for c in result.calls if c.kind == "character")
        self.assertEqual(call.outcome, "failed")
        self.assertEqual(len(call.attempts), 2)

    def test_no_backoff_after_format_error(self):
        _, result, sleeps, _ = run_scene("A1", character_errors([ok(""), rate_limited()]))
        call = next(c for c in result.calls if c.kind == "character")
        self.assertEqual(call.outcome, "failed")
        self.assertEqual(sleeps, [])


class ProviderRefusalTest(unittest.TestCase):
    def test_moderation_voids_only_this_scene(self):
        model, result, _, _ = run_scene("C1", character_errors([moderation()]), scale="成人向")
        self.assertEqual(result.status, "refused")
        self.assertIn("服务商拒绝", result.reason)
        self.assertEqual(result.calls[-1].outcome, "refused")
        # 拒绝后本场不再发任何调用
        self.assertEqual(model.log[-1][0]["kind"], "character")

    def test_content_filter_finish_is_refusal(self):
        _, result, _, ledger = run_scene("A1", character_errors([Completion("【说】……", "content_filter", 0.002)]))
        self.assertEqual(result.status, "refused")
        self.assertIn("content_filter", result.reason)
        self.assertGreaterEqual(ledger.spent, 0.002)

    def test_run_continues_with_other_units(self):
        models = {"甲": "m1", "乙": "m2"}
        units = run.plan_units(DS, ["A2", "A3", "C1"], models, {"m1", "m2"}, 1)
        def refuse_a2(purpose, n):
            if purpose["scene"] == "A2" and purpose["kind"] == "character":
                raise moderation()
            return None

        clients = {"甲": RecordingFake(refuse_a2), "乙": RecordingFake()}
        with contextlib.redirect_stderr(io.StringIO()):
            stop = run.run_units(DS, units, clients, Ledger(1.0), 1000, sleep=Sleeps())
        self.assertIsNone(stop)
        status = {(u.code, u.scene, u.scale): u.status for u in units}
        self.assertEqual(status[("甲", "A2", "含蓄")], "refused")
        self.assertEqual(status[("甲", "A3", "含蓄")], "not_run")
        self.assertEqual(status[("甲", "C1", "含蓄")], "completed")
        self.assertEqual(status[("甲", "C1", "成人向")], "completed")
        self.assertTrue(all(s == "completed" for (c, _, _), s in status.items() if c == "乙"))


class FatalErrorTest(unittest.TestCase):
    def run_all(self, error_or_completion):
        models = {"甲": "m1", "乙": "m2"}
        units = run.plan_units(DS, ["A1", "A2"], models, set(), 1)
        clients = {"甲": RecordingFake(character_errors([error_or_completion])), "乙": RecordingFake()}
        with contextlib.redirect_stderr(io.StringIO()):
            stop = run.run_units(DS, units, clients, Ledger(1.0), 1000, sleep=Sleeps())
        return units, clients, stop

    def assert_run_stopped(self, units, clients, stop):
        self.assertIsNotNone(stop)
        self.assertEqual([u.status for u in units], ["stopped", "not_run", "not_run", "not_run"])
        self.assertEqual(clients["乙"].log, [])

    def test_401_stops_run(self):
        units, clients, stop = self.run_all(RequestError(FATAL, "HTTP 401：no auth", 401))
        self.assert_run_stopped(units, clients, stop)
        self.assertIn("401", stop)

    def test_402_stops_run(self):
        units, clients, stop = self.run_all(RequestError(FATAL, "HTTP 402：no credits", 402))
        self.assert_run_stopped(units, clients, stop)
        self.assertIn("402", stop)

    def test_success_without_cost_stops_run(self):
        units, clients, stop = self.run_all(Completion("【说】嗯。\n【心声】好。", "stop", None))
        self.assert_run_stopped(units, clients, stop)
        self.assertIn("未返回费用", stop)


if __name__ == "__main__":
    unittest.main()
