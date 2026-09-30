"""按实际费用累计，到上限就停；拿不到费用立刻停。"""

import contextlib
import io
import unittest

from tests.support import DS, RecordingFake

import run
from models import Completion, TransportError
from scene import Ledger, RunStop, SceneRun


class BudgetTest(unittest.TestCase):
    def test_stops_when_budget_reached(self):
        model = RecordingFake(cost_per_call=0.01)
        ledger = Ledger(0.03)
        result = SceneRun(DS, DS.scenes["A1"], "含蓄", model, ledger, 1000).run()
        self.assertEqual(result.status, "stopped")
        self.assertIn("预算上限", result.reason)
        self.assertEqual(len(model.log), 3)
        self.assertAlmostEqual(ledger.spent, 0.03)
        self.assertEqual(result.calls[-1].outcome, "stopped")
        self.assertEqual(result.calls[-1].attempts, [])

    def test_stops_when_cost_missing(self):
        model = RecordingFake(lambda p, n: Completion("【叙事】夜。", "stop", None))
        result = SceneRun(DS, DS.scenes["A1"], "含蓄", model, Ledger(1.0), 1000).run()
        self.assertEqual(result.status, "stopped")
        self.assertIn("未返回费用", result.reason)
        self.assertEqual(len(model.log), 1)

    def test_stops_on_transport_error(self):
        def hook(p, n):
            raise TransportError("HTTP 500")

        model = RecordingFake(hook)
        result = SceneRun(DS, DS.scenes["A1"], "含蓄", model, Ledger(1.0), 1000).run()
        self.assertEqual(result.status, "stopped")
        self.assertEqual(len(model.log), 1)

    def test_retry_is_also_charged_and_checked(self):
        model = RecordingFake(lambda p, n: Completion("", "stop", 0.02))
        ledger = Ledger(0.02)
        result = SceneRun(DS, DS.scenes["A1"], "含蓄", model, ledger, 1000).run()
        self.assertEqual(result.status, "stopped")
        self.assertEqual(len(model.log), 1)

    def test_run_stops_all_remaining_units(self):
        ids = ["A1", "A2"]
        models = {"甲": "m1", "乙": "m2"}
        units = run.plan_units(DS, ids, models, set(), 1)
        clients = {"甲": RecordingFake(cost_per_call=0.01), "乙": RecordingFake(cost_per_call=0.01)}
        with contextlib.redirect_stderr(io.StringIO()):
            stop = run.run_units(DS, units, clients, Ledger(0.05), 1000)
        self.assertIn("预算上限", stop)
        self.assertEqual([u.status for u in units], ["stopped", "not_run", "not_run", "not_run"])
        self.assertEqual(len(clients["乙"].log), 0)

    def test_ledger_rejects_non_positive_budget(self):
        with self.assertRaises(ValueError):
            Ledger(0)

    def test_check_raises_at_limit(self):
        ledger = Ledger(0.1)
        ledger.charge(0.1)
        with self.assertRaises(RunStop):
            ledger.check()


if __name__ == "__main__":
    unittest.main()
