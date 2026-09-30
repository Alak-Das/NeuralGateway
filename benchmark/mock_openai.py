"""Small deterministic OpenAI-compatible upstream for isolated gateway tests."""

import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from collections import Counter


_lock = threading.Lock()
_total_requests = 0
_model_requests = Counter()
_delay_seconds = max(0.0, min(float(os.environ.get("MOCK_DELAY_MS", "5")) / 1000.0, 2.0))
_allowed_fault_modes = {"none", "http-503", "model-mismatch"}
_fault_mode = os.environ.get("MOCK_FAULT_MODE", "none")
_admin_secret = os.environ.get("MOCK_ADMIN_SECRET", "").strip()
if _fault_mode not in _allowed_fault_modes:
    raise ValueError("MOCK_FAULT_MODE must be one of: " + ", ".join(sorted(_allowed_fault_modes)))


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, _format, *_args):
        return

    def _send_json(self, status, payload, content_type="application/json"):
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.close_connection = True
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            self._send_json(200, {"status": "ok"})
            return
        if self.path == "/stats":
            with _lock:
                snapshot = {
                    "total_requests": _total_requests,
                    "requests_by_model": dict(sorted(_model_requests.items())),
                    "fault_mode": _fault_mode,
                }
            self._send_json(200, snapshot)
            return
        self._send_json(404, {"error": "not found"})

    def do_POST(self):
        global _total_requests, _fault_mode

        if self.path == "/admin/fault":
            if not _admin_secret or self.headers.get("X-Synthetic-Admin-Key") != _admin_secret:
                self._send_json(403, {"error": {"message": "synthetic fault admin access is disabled or unauthorized"}})
                return
            try:
                content_length = int(self.headers.get("Content-Length", "0"))
                request = json.loads(self.rfile.read(content_length))
            except (ValueError, json.JSONDecodeError):
                self._send_json(400, {"error": {"message": "invalid JSON"}})
                return
            mode = request.get("mode") if isinstance(request, dict) else None
            if mode not in _allowed_fault_modes:
                self._send_json(400, {"error": {"message": "unsupported synthetic fault mode"}})
                return
            with _lock:
                _fault_mode = mode
            self._send_json(200, {"fault_mode": mode})
            return

        if self.path != "/v1/chat/completions":
            self._send_json(404, {"error": "not found"})
            return

        try:
            content_length = int(self.headers.get("Content-Length", "0"))
            request = json.loads(self.rfile.read(content_length))
        except (ValueError, json.JSONDecodeError):
            self._send_json(400, {"error": {"message": "invalid JSON"}})
            return

        model = request.get("model")
        if not isinstance(model, str) or not model:
            self._send_json(400, {"error": {"message": "model is required"}})
            return

        with _lock:
            _total_requests += 1
            _model_requests[model] += 1
            fault_mode = _fault_mode

        if fault_mode == "http-503":
            self._send_json(503, {"error": {"message": "synthetic fault: upstream unavailable"}})
            return

        if _delay_seconds:
            time.sleep(_delay_seconds)

        reported_model = "synthetic-unexpected-model" if fault_mode == "model-mismatch" else model

        if request.get("stream") is True:
            content = "mock-upstream served model: " + reported_model
            chunks = [content[:16], content[16:]]
            self.close_connection = True
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "close")
            self.end_headers()
            for index, text in enumerate(chunks):
                chunk = {
                    "id": "chatcmpl-mock",
                    "object": "chat.completion.chunk",
                    "created": 1,
                    "model": reported_model,
                    "choices": [{"index": 0, "delta": {"content": text}, "finish_reason": None}],
                }
                self.wfile.write(b"data: " + json.dumps(chunk, separators=(",", ":")).encode("utf-8") + b"\n\n")
                self.wfile.flush()
            final_chunk = {
                "id": "chatcmpl-mock",
                "object": "chat.completion.chunk",
                "created": 1,
                "model": reported_model,
                "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
            }
            self.wfile.write(b"data: " + json.dumps(final_chunk, separators=(",", ":")).encode("utf-8") + b"\n\n")
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
            return

        content = "mock-upstream served model: " + reported_model
        self._send_json(200, {
            "id": "chatcmpl-mock",
            "object": "chat.completion",
            "created": 1,
            "model": reported_model,
            "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": content},
                "finish_reason": "stop",
            }],
            "usage": {"prompt_tokens": 8, "completion_tokens": 8, "total_tokens": 16},
        })


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8377), Handler).serve_forever()