"""补跑：沿用原代号，只跑原运行里没完成的场景版本；盲评页合并成完整的一套。
另含盲评页不泄露模型 ID 的测试。"""

import contextlib
import io
import json
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

from tests.support import DS, RecordingFake

import render
import run
from models import OpenRouterClient, RequestError
from scene import Ledger, SceneRun


def fake_run(tmp: Path, *extra) -> Path:
    with contextlib.redirect_stderr(io.StringIO()):
        run.main(["--fake", "--model", "vendor/alpha", "--model", "vendor/beta", "--adult-model", "vendor/beta",
                  "--repeats", "1", "--out", str(tmp), *extra])
    return max(tmp.iterdir())


class ResumeTest(unittest.TestCase):
    def test_resume_reruns_only_incomplete_units_with_same_codes(self):
        with tempfile.TemporaryDirectory() as t:
            tmp = Path(t)
            orig_dir = fake_run(tmp, "--seed", "3")
            record = json.loads((orig_dir / "record.json").read_text(encoding="utf-8"))
            # 模拟原运行里的失败：甲的 A2 开场失败、A3 因此未执行；乙的 B3 开场失败
            broken = {("甲", "A2"): "failed", ("甲", "A3"): "not_run", ("乙", "B3"): "failed"}
            for u in record["units"]:
                if (u["code"], u["scene"]) in broken:
                    u.update(status=broken[(u["code"], u["scene"])], reason="模拟失败", events=[], calls=[])
            (orig_dir / "record.json").write_text(json.dumps(record, ensure_ascii=False), encoding="utf-8")

            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(run.main(["--fake", "--resume", str(orig_dir), "--out", str(tmp)]), 0)
            (resume_dir,) = [d for d in tmp.iterdir() if d.name.endswith("-resume-fake")]
            new = json.loads((resume_dir / "record.json").read_text(encoding="utf-8"))
            html = (resume_dir / "blind.html").read_text(encoding="utf-8")
            self.assertEqual((resume_dir / "key.txt").read_text(encoding="utf-8"),
                             (orig_dir / "key.txt").read_text(encoding="utf-8"))

        self.assertEqual(new["resumed_from"], record["run_id"])
        self.assertEqual({(u["code"], u["scene"]) for u in new["units"]}, set(broken))
        self.assertTrue(all(u["status"] == "completed" for u in new["units"]))
        self.assertEqual(new["codes"], record["codes"])
        self.assertEqual(new["adult_codes"], record["adult_codes"])
        self.assertEqual(new["stats"]["calls"], sum(len(u["calls"]) for u in new["units"]))
        data = json.loads(html.split('type="application/json">')[1].split("</script>")[0])
        self.assertEqual(len(data["units"]), len(record["units"]))
        self.assertTrue(all(u["status"] == "completed" for u in data["units"]))

    def test_resume_rejects_new_models(self):
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                run.parse_args(["--resume", "x", "--model", "a", "--budget", "1"])


class BlindPageLeakTest(unittest.TestCase):
    def test_client_redacts_model_id_from_errors(self):
        client = OpenRouterClient("vendor/secret-model", api_key="k")
        body = b'{"error":{"code":429,"message":"vendor/secret-model is temporarily rate-limited upstream"}}'

        def fail(req, timeout):
            raise urllib.error.HTTPError(req.full_url, 429, "rate", {}, io.BytesIO(body))

        with mock.patch("urllib.request.urlopen", fail):
            with self.assertRaises(RequestError) as ctx:
                client.complete([], 100, {"kind": "opening"})
        self.assertNotIn("vendor/secret-model", str(ctx.exception))
        self.assertIn("<模型>", str(ctx.exception))

    def test_failure_event_carries_only_status_code(self):
        def hook(purpose, n):
            if purpose["kind"] == "character":
                raise RequestError("transient", 'HTTP 502：{"message":"model at capacity, provider X"}', 502)
            return None

        result = SceneRun(DS, DS.scenes["A1"], "含蓄", RecordingFake(hook), Ledger(1.0), 1000,
                          sleep=lambda s: None).run()
        failure = next(e for e in result.events if e.kind == "failure")
        self.assertEqual(failure.text, "技术失败：请求失败：HTTP 502（已重试一次）")
        call = next(c for c in result.calls if c.kind == "character")
        self.assertIn("capacity", call.attempts[0]["detail"])

    def test_render_strips_provider_text_from_old_records(self):
        old = {"kind": "failure", "speaker": "叙事者", "round": 0,
               "text": '开场技术失败：请求失败：HTTP 429：{"error":{"message":"vendor/x is rate-limited"}}（已重试一次）'}
        self.assertEqual(render.public_event(old)["text"], "开场技术失败：请求失败：HTTP 429（已重试一次）")
        old2 = old | {"text": '开场技术失败：请求失败：接口错误：{"message": "at capacity"}（已重试一次）'}
        self.assertEqual(render.public_event(old2)["text"], "开场技术失败：请求失败：接口错误（已重试一次）")


if __name__ == "__main__":
    unittest.main()
