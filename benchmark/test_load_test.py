"""Fast standard-library checks for synthetic response/fault validation."""

import unittest
import json
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from threading import Thread
from http.server import ThreadingHTTPServer

import load_test
import mock_openai


class ResponseModelValidationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.previous_admin_secret = mock_openai._admin_secret
        cls.previous_fault_mode = mock_openai._fault_mode
        mock_openai._admin_secret = "test-private-admin-key"
        mock_openai._fault_mode = "none"
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), mock_openai.Handler)
        cls.server.daemon_threads = True
        cls.server_thread = Thread(target=cls.server.serve_forever, daemon=True)
        cls.server_thread.start()
        cls.base_url = "http://127.0.0.1:" + str(cls.server.server_address[1])

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.server_thread.join(timeout=2)
        mock_openai._admin_secret = cls.previous_admin_secret
        mock_openai._fault_mode = cls.previous_fault_mode

    def tearDown(self):
        mock_openai._fault_mode = "none"

    def post(self, path, payload, headers=None):
        request = Request(self.base_url + path, data=json.dumps(payload).encode("utf-8"),
                          headers={"Content-Type": "application/json", **(headers or {})})
        try:
            with urlopen(request, timeout=3) as response:
                return response.status, response.read()
        except HTTPError as error:
            return error.code, error.read()

    def test_mock_requires_private_admin_key_to_enable_fault_mode(self):
        status, _body = self.post("/admin/fault", {"mode": "http-503"})
        self.assertEqual(403, status)
        status, _body = self.post("/admin/fault", {"mode": "http-503"},
                                  {"X-Synthetic-Admin-Key": "test-private-admin-key"})
        self.assertEqual(200, status)
        self.assertEqual("http-503", mock_openai._fault_mode)

    def test_fault_mode_can_be_reset_without_clearing_synthetic_request_counters(self):
        mock_openai._fault_mode = "model-mismatch"
        mock_openai._total_requests = 4
        mock_openai._model_requests["provider/model-a"] = 4

        status, _body = self.post("/admin/fault", {})
        self.assertEqual(403, status)
        status, body = self.post("/admin/fault", {"mode": "none"},
                                 {"X-Synthetic-Admin-Key": "test-private-admin-key"})
        self.assertEqual(200, status)
        self.assertEqual("none", mock_openai._fault_mode)
        self.assertEqual(4, mock_openai._total_requests)
        self.assertEqual({"provider/model-a": 4}, mock_openai._model_requests)
        self.assertEqual("none", json.loads(body)["fault_mode"])

    def test_mock_returns_synthetic_http_failure_and_can_be_reset(self):
        self.post("/admin/fault", {"mode": "http-503"},
                  {"X-Synthetic-Admin-Key": "test-private-admin-key"})
        status, body = self.post("/v1/chat/completions", {
            "model": "provider/model-a", "messages": [], "stream": False,
        })
        self.assertEqual(503, status)
        self.assertIn(b"synthetic fault", body)

        self.post("/admin/fault", {"mode": "none"},
                  {"X-Synthetic-Admin-Key": "test-private-admin-key"})
        status, body = self.post("/v1/chat/completions", {
            "model": "provider/model-a", "messages": [], "stream": False,
        })
        self.assertEqual(200, status)
        self.assertEqual("provider/model-a", json.loads(body)["model"])

    def test_mock_reports_mismatched_model_in_json_and_stream_chunks(self):
        self.post("/admin/fault", {"mode": "model-mismatch"},
                  {"X-Synthetic-Admin-Key": "test-private-admin-key"})
        expected_reported_model = "synthetic-unexpected-model"
        for streaming in (False, True):
            status, body = self.post("/v1/chat/completions", {
                "model": "provider/model-a", "messages": [], "stream": streaming,
            })
            self.assertEqual(200, status)
            actual_models, valid = load_test.parse_response_model_ids(body, streaming)
            self.assertTrue(valid)
            self.assertEqual([expected_reported_model], actual_models)

    def test_non_streaming_extracts_the_reported_model(self):
        body = b'{"model":"provider/model-a","choices":[{"message":{"content":"ok"}}]}'

        model_ids, valid = load_test.parse_response_model_ids(body, streaming=False)

        self.assertEqual(["provider/model-a"], model_ids)
        self.assertTrue(valid)

    def test_streaming_extracts_every_reported_sse_model(self):
        body = (
            b'data: {"model":"provider/model-a","choices":[{"delta":{"content":"a"}}]}\n\n'
            b'data: {"model":"provider/model-a","choices":[{"delta":{},"finish_reason":"stop"}]}\n\n'
            b'data: [DONE]\n\n'
        )

        model_ids, valid = load_test.parse_response_model_ids(body, streaming=True)

        self.assertEqual(["provider/model-a"], model_ids)
        self.assertTrue(valid)

    def test_streaming_missing_model_or_done_marker_is_invalid(self):
        missing_model = b'data: {"choices":[{"delta":{}}]}\n\ndata: [DONE]\n\n'
        missing_done = b'data: {"model":"provider/model-a","choices":[{"delta":{}}]}\n\n'

        self.assertEqual(([], False), load_test.parse_response_model_ids(missing_model, streaming=True))
        self.assertEqual((["provider/model-a"], False), load_test.parse_response_model_ids(missing_done, streaming=True))

    def test_mismatched_models_in_any_stream_chunk_are_visible(self):
        body = (
            b'data: {"model":"provider/model-a","choices":[{"delta":{}}]}\n\n'
            b'data: {"model":"provider/unexpected","choices":[{"delta":{}}]}\n\n'
            b'data: [DONE]\n\n'
        )

        model_ids, valid = load_test.parse_response_model_ids(body, streaming=True)

        self.assertTrue(valid)
        self.assertEqual(["provider/model-a", "provider/unexpected"], model_ids)
        self.assertTrue(any(actual_model != "provider/model-a" for actual_model in model_ids))

    def test_fault_control_rejects_host_targets_for_injection(self):
        with self.assertRaisesRegex(ValueError, "only from the isolated"):
            load_test.set_synthetic_fault("http://127.0.0.1:19090", "http-503")

    def test_fault_control_targets_only_the_private_mock(self):
        with patch.object(load_test, "request_synthetic_control",
                          return_value=(200, b'{"fault_mode":"model-mismatch"}', {})) as request:
            self.assertTrue(load_test.set_synthetic_fault("http://gateway:8080", "model-mismatch"))
        self.assertEqual("http://mock-upstream:8377/admin/fault", request.call_args.args[0])
        self.assertEqual("benchmark-private-fault-control",
                         request.call_args.kwargs["headers"]["X-Synthetic-Admin-Key"])

    def test_gateway_state_reset_targets_only_the_private_gateway_and_selected_breakers(self):
        with patch.object(load_test, "request_synthetic_control", return_value=(200, b"", {})) as request:
            load_test.reset_synthetic_gateway_state("http://gateway:8080", ["provider/model-a"])
        self.assertEqual("http://gateway:8080/api/models/circuit-reset?model=provider%2Fmodel-a",
                         request.call_args.args[0])

    def test_gateway_state_reset_rejects_host_targets(self):
        with self.assertRaisesRegex(ValueError, "only on gateway:8080 or localhost:19090"):
            load_test.reset_synthetic_gateway_state("http://127.0.0.1:9090", [])

    def test_expected_upstream_failure_is_reported_as_a_valid_fault_pass(self):
        with patch.object(load_test, "request_json", return_value=(500, b'{"error":"synthetic failure"}', {})):
            report = load_test.run("http://gateway:8080", 1, 1, 8, 1, False,
                                   "provider/model-a", "provider/model-a", "http-503")

        self.assertTrue(report["validation_passed"])
        self.assertEqual(1, report["faults_observed"])
        self.assertEqual(0, report["success_rate"])
        self.assertEqual({"500": 1}, report["status_counts"])

    def test_mock_admin_http_control_disables_redirects_and_bypasses_proxy(self):
        with patch.object(load_test.urllib.request, "build_opener") as build_opener:
            opener = build_opener.return_value
            response = type("Response", (), {
                "status": 200,
                "read": lambda self: b"{}",
                "headers": {},
                "__enter__": lambda self: self,
                "__exit__": lambda self, *_args: None,
            })()
            opener.open.return_value = response

            status, body, _headers = load_test.request_synthetic_control(
                "http://mock-upstream:8377/admin/fault", 5, b"{}", {})

        self.assertEqual(200, status)
        self.assertEqual(b"{}", body)
        handlers = build_opener.call_args.args
        self.assertTrue(any(isinstance(handler, load_test.RejectRedirectHandler) for handler in handlers))
        self.assertTrue(any(isinstance(handler, load_test.urllib.request.ProxyHandler) for handler in handlers))

    def test_expected_stream_model_mismatch_is_reported_without_hiding_actual_model(self):
        response = (
            b'data: {"model":"synthetic-unexpected-model","choices":[{"delta":{"content":"fault"}}]}\n\n'
            b'data: {"model":"synthetic-unexpected-model","choices":[{"delta":{},"finish_reason":"stop"}]}\n\n'
            b'data: [DONE]\n\n'
        )
        with patch.object(load_test, "request_json", return_value=(200, response, {})):
            report = load_test.run("http://gateway:8080", 1, 1, 8, 1, True,
                                   "provider/model-a", "provider/model-a", "model-mismatch")

        self.assertTrue(report["validation_passed"])
        self.assertEqual(1, report["faults_observed"])
        self.assertEqual(0, report["success_rate"])
        self.assertEqual({"synthetic-unexpected-model": 1}, report["actual_model_counts"])


if __name__ == "__main__":
    unittest.main()
