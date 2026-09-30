"""用替身端到端跑完 9 个场景，检查三份输出。"""

import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path

from tests.support import DS  # noqa: F401  (确保 sys.path)

import run


class EndToEndTest(unittest.TestCase):
    def test_full_fake_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            argv = ["--fake", "--model", "vendor/alpha", "--model", "vendor/beta",
                    "--adult-model", "vendor/beta", "--seed", "7", "--out", tmp]
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(run.main(argv), 0)
            (out,) = Path(tmp).iterdir()
            record = json.loads((out / "record.json").read_text(encoding="utf-8"))
            html = (out / "blind.html").read_text(encoding="utf-8")
            key = (out / "key.txt").read_text(encoding="utf-8")

        units = record["units"]
        # 9 个场景 × 2 个模型 × 2 次，加上 C1 成人向只给一个模型 × 2 次
        self.assertEqual(len(units), 9 * 2 * 2 + 2)
        self.assertTrue(all(u["status"] == "completed" for u in units))
        self.assertEqual(record["stats"]["final_failed"], 0)
        self.assertEqual({u["scene"] for u in units}, set(DS.scenes))
        adult = [u for u in units if u["scale"] == "成人向"]
        self.assertEqual({u["code"] for u in adult}, set(record["adult_codes"]))
        self.assertGreater(record["ledger"]["spent"], 0)

        for text in (json.dumps(record, ensure_ascii=False), html):
            self.assertNotIn("vendor/", text)
        self.assertIn("vendor/alpha", key)
        self.assertIn("vendor/beta", key)
        self.assertIn("甲", html)

    def test_real_run_requires_budget(self):
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                run.parse_args(["--model", "vendor/alpha"])

    def test_adult_model_must_be_listed(self):
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                run.parse_args(["--fake", "--model", "a", "--adult-model", "b"])

    def test_a3_without_a2_is_rejected(self):
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                run.main(["--fake", "--model", "a", "--scenes", "A3"])


if __name__ == "__main__":
    unittest.main()
