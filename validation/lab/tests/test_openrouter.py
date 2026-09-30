"""OpenRouter 客户端：不发真实请求，只验证请求体与响应解析。"""

import io
import json
import os
import unittest
import urllib.error
from unittest import mock

from tests.support import DS  # noqa: F401  (确保 sys.path)

from models import OpenRouterClient, TransportError, parse_response


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

    def test_parse_cost(self):
        comp = parse_response(payload())
        self.assertEqual(comp.cost, 0.00123)
        self.assertEqual(comp.finish_reason, "stop")
        self.assertEqual(comp.usage["total_tokens"], 15)

    def test_missing_cost_is_none(self):
        self.assertIsNone(parse_response(payload(cost=None)).cost)

    def test_null_content_is_empty_text(self):
        self.assertEqual(parse_response(payload(content=None)).text, "")

    def test_error_payload_raises(self):
        with self.assertRaises(TransportError):
            parse_response({"error": {"code": 400, "message": "bad"}})

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

    def test_http_error_raises_transport_error(self):
        c = OpenRouterClient("vendor/model", api_key="k")

        def fail(req, timeout):
            raise urllib.error.HTTPError(req.full_url, 429, "rate", {}, io.BytesIO(b"slow down"))

        with mock.patch("urllib.request.urlopen", fail):
            with self.assertRaises(TransportError):
                c.complete([], 100, {"kind": "opening"})


if __name__ == "__main__":
    unittest.main()
