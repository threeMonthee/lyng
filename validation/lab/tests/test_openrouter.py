"""OpenRouter 客户端：不发真实请求，只验证请求体与响应解析。"""

import io
import json
import os
import unittest
import urllib.error
from unittest import mock

from tests.support import DS  # noqa: F401  (确保 sys.path)

from models import (FATAL, REFUSED, TRANSIENT, OpenRouterClient, RequestError, classify, parse_participant,
                    parse_response)


def payload(content="【叙事】夜。", finish="stop", cost=0.00123):
    usage = {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
    if cost is not None:
        usage["cost"] = cost
    return {"choices": [{"message": {"content": content}, "finish_reason": finish}], "usage": usage}


class FakeResponse(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class OpenRouterTest(unittest.TestCase):
    def test_requires_explicit_model_id(self):
        with self.assertRaises(ValueError):
            OpenRouterClient("", api_key="k")

    def test_reads_key_from_environment_only(self):
        with mock.patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(RuntimeError):
                OpenRouterClient("vendor/model")

    def test_request_body(self):
        c = OpenRouterClient("vendor/model", api_key="k")
        body = c.request_body([{"role": "user", "content": "x"}], 500)
        self.assertEqual(body["model"], "vendor/model")
        self.assertEqual(body["max_tokens"], 500)
        self.assertEqual(body["usage"], {"include": True})

    def test_reasoning_setting_in_request_body(self):
        msgs = [{"role": "user", "content": "x"}]
        self.assertNotIn("reasoning", OpenRouterClient("vendor/model", api_key="k").request_body(msgs, 10))
        off = OpenRouterClient("vendor/model", api_key="k", reasoning="off").request_body(msgs, 10)
        self.assertEqual(off["reasoning"], {"enabled": False})
        low = OpenRouterClient("vendor/model", api_key="k", reasoning="low").request_body(msgs, 10)
        self.assertEqual(low["reasoning"], {"effort": "low"})
        with self.assertRaises(ValueError):
            OpenRouterClient("vendor/model", api_key="k", reasoning="turbo")

    def test_parse_participant(self):
        self.assertEqual(parse_participant("vendor/model"), ("vendor/model", None))
        self.assertEqual(parse_participant("vendor/model@off"), ("vendor/model", "off"))
        self.assertEqual(parse_participant("vendor/model@minimal"), ("vendor/model", "minimal"))
        for bad in ("vendor/model@turbo", "@off", "vendor/model@"):
            with self.assertRaises(ValueError, msg=bad):
                parse_participant(bad)

    def test_provider_is_recorded(self):
        p = payload()
        p["provider"] = "SomeHost"
        self.assertEqual(parse_response(p).provider, "SomeHost")
        self.assertIsNone(parse_response(payload()).provider)

    def test_parse_cost(self):
        comp = parse_response(payload())
        self.assertEqual(comp.cost, 0.00123)
        self.assertEqual(comp.finish_reason, "stop")
        self.assertEqual(comp.usage["total_tokens"], 15)

    def test_reasoning_tokens(self):
        p = payload()
        p["usage"]["completion_tokens_details"] = {"reasoning_tokens": 321}
        self.assertEqual(parse_response(p).usage["reasoning_tokens"], 321)
        self.assertIsNone(parse_response(payload()).usage["reasoning_tokens"])

    def test_missing_cost_is_none(self):
        self.assertIsNone(parse_response(payload(cost=None)).cost)

    def test_null_content_is_empty_text(self):
        self.assertEqual(parse_response(payload(content=None)).text, "")

    def test_error_payload_raises(self):
        with self.assertRaises(RequestError) as ctx:
            parse_response({"error": {"code": 400, "message": "bad"}})
        self.assertEqual(ctx.exception.kind, FATAL)

    def test_error_payload_moderation_is_refusal(self):
        err = {"code": 403, "message": "Input was flagged", "metadata": {"reasons": ["sexual"]}}
        with self.assertRaises(RequestError) as ctx:
            parse_response({"error": err})
        self.assertEqual(ctx.exception.kind, REFUSED)

    def test_classify(self):
        moderation = {"message": "flagged", "metadata": {"reasons": ["sexual"], "flagged_input": "..."}}
        self.assertEqual(classify(429, None), TRANSIENT)
        self.assertEqual(classify(500, None), TRANSIENT)
        self.assertEqual(classify(503, {}), TRANSIENT)
        self.assertEqual(classify(403, moderation), REFUSED)
        self.assertEqual(classify(403, {"message": "Forbidden"}), FATAL)
        self.assertEqual(classify(401, None), FATAL)
        self.assertEqual(classify(402, None), FATAL)
        self.assertEqual(classify(400, None), FATAL)
        self.assertEqual(classify(None, None), FATAL)

    def test_complete_sends_request(self):
        c = OpenRouterClient("vendor/model", api_key="secret")
        seen = {}

        def fake_urlopen(req, timeout):
            seen["auth"] = req.get_header("Authorization")
            seen["body"] = json.loads(req.data.decode())
            return FakeResponse(json.dumps(payload()).encode())

        with mock.patch("urllib.request.urlopen", fake_urlopen):
            comp = c.complete([{"role": "user", "content": "x"}], 100, {"kind": "opening"})
        self.assertEqual(seen["auth"], "Bearer secret")
        self.assertNotIn("purpose", seen["body"])
        self.assertEqual(comp.cost, 0.00123)

    def http_error(self, code, body):
        c = OpenRouterClient("vendor/model", api_key="k")

        def fail(req, timeout):
            raise urllib.error.HTTPError(req.full_url, code, "err", {}, io.BytesIO(body.encode()))

        with mock.patch("urllib.request.urlopen", fail):
            with self.assertRaises(RequestError) as ctx:
                c.complete([], 100, {"kind": "opening"})
        self.assertEqual(ctx.exception.status, code)
        return ctx.exception.kind

    def test_http_errors_are_classified(self):
        self.assertEqual(self.http_error(429, "slow down"), TRANSIENT)
        self.assertEqual(self.http_error(502, "bad gateway"), TRANSIENT)
        self.assertEqual(self.http_error(401, '{"error":{"code":401,"message":"no auth"}}'), FATAL)
        self.assertEqual(self.http_error(402, '{"error":{"code":402,"message":"no credits"}}'), FATAL)
        moderation = '{"error":{"code":403,"message":"flagged","metadata":{"reasons":["violence"]}}}'
        self.assertEqual(self.http_error(403, moderation), REFUSED)

    def test_network_error_is_fatal(self):
        c = OpenRouterClient("vendor/model", api_key="k")

        def fail(req, timeout):
            raise urllib.error.URLError("reset")

        with mock.patch("urllib.request.urlopen", fail):
            with self.assertRaises(RequestError) as ctx:
                c.complete([], 100, {"kind": "opening"})
        self.assertEqual(ctx.exception.kind, FATAL)


if __name__ == "__main__":
    unittest.main()
