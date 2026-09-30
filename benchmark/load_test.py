"""Conservative, dependency-free HTTP load driver for the isolated gateway."""

import argparse
import concurrent.futures
import json
import math
import os
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlencode, urlsplit


class RejectRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


MAX_RUN_SECONDS = 120
MAX_TOTAL_REQUESTS = 240
MAX_WORKERS = 16
MAX_REQUESTS_PER_SECOND = 8
ALLOWED_TARGETS = {("127.0.0.1", 19090), ("localhost", 19090), ("gateway", 8080)}
ALLOWED_FAULT_MODES = {"none", "http-503", "model-mismatch"}
SYNTHETIC_ADMIN_KEY_HEADER = "X-Synthetic-Admin-Key"
DEFAULT_MODELS = (
    "moonshotai/kimi-k3",
    "z-ai/glm-5.3",
    "nvidia/nemotron-3-ultra-550b-a55b",
    "deepseek-ai/deepseek-v4.1-flash",
    "nvidia/nemotron-3-super-120b-a12b",
    "z-ai/glm-5.3-flash",
    "meta/llama-3.2-11b-vision-instruct",
    "mimo-v2.6-pro",
    "step-3.7-flash",
    "deepseek-v4-flash",
)


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(fraction * len(ordered)) - 1))
    return round(ordered[index], 2)


def request_json(url, timeout, data=None, headers=None):
    request = urllib.request.Request(url, data=data, headers=headers or {}, method="POST" if data is not None else "GET")
    try:
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=timeout) as response:
            return response.status, response.read(), dict(response.headers.items())
    except urllib.error.HTTPError as error:
        return error.code, error.read(), dict(error.headers.items())


def request_synthetic_control(url, timeout, data, headers):
    """Send mock-admin POSTs without carrying their secret through redirects."""
    request = urllib.request.Request(url, data=data, headers=headers, method="POST")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), RejectRedirectHandler())
    try:
        with opener.open(request, timeout=timeout) as response:
            return response.status, response.read(), dict(response.headers.items())
    except urllib.error.HTTPError as error:
        return error.code, error.read(), dict(error.headers.items())


def list_gateway_models(base_url):
    status, body, _headers = request_json(base_url + "/v1/models", 5)
    if status != 200:
        raise RuntimeError("gateway model listing returned HTTP " + str(status))
    return {item["id"] for item in json.loads(body).get("data", []) if isinstance(item, dict) and "id" in item}


def make_payload(model, streaming):
    return json.dumps({
        "model": model,
        "messages": [{"role": "user", "content": "Respond with a short synthetic benchmark acknowledgement."}],
        "max_tokens": 16,
        "temperature": 0,
        "stream": streaming,
    }, separators=(",", ":")).encode("utf-8")


def parse_response_model_ids(response_body, streaming):
    """Return every model ID reported in a JSON response or SSE data chunk."""
    if not streaming:
        try:
            response_json = json.loads(response_body)
        except (ValueError, UnicodeDecodeError):
            return [], False
        if not isinstance(response_json, dict) or not response_json.get("choices"):
            return [], False
        actual_model = response_json.get("model")
        if not isinstance(actual_model, str) or not actual_model:
            return [], False
        return [actual_model], True

    actual_models = []
    response_models = set()
    saw_chunk = False
    saw_done = False
    valid_chunks = True
    for line in response_body.decode("utf-8", "replace").splitlines():
        if not line.startswith("data:"):
            continue
        payload = line[5:].strip()
        if payload == "[DONE]":
            saw_done = True
            continue
        if not payload:
            continue
        try:
            chunk = json.loads(payload)
        except ValueError:
            valid_chunks = False
            continue
        if not isinstance(chunk, dict) or not chunk.get("choices"):
            valid_chunks = False
            continue
        saw_chunk = True
        chunk_model = chunk.get("model")
        if not isinstance(chunk_model, str) or not chunk_model:
            valid_chunks = False
        elif chunk_model not in response_models:
            response_models.add(chunk_model)
            actual_models.append(chunk_model)
    return actual_models, saw_done and saw_chunk and bool(actual_models) and valid_chunks


def reset_synthetic_gateway_state(base_url, models=()):
    """Reset only requested breakers on an allow-listed isolated gateway target."""
    target = urlsplit(base_url)
    if target.hostname == "gateway" and target.port == 8080:
        gateway_url = "http://gateway:8080"
    elif target.hostname in {"127.0.0.1", "localhost"} and target.port == 19090:
        gateway_url = "http://" + target.hostname + ":19090"
    else:
        raise ValueError("synthetic circuit reset is available only on gateway:8080 or localhost:19090")
    for model in models:
        query = urlencode({"model": model})
        status, response_body, _headers = request_synthetic_control(
            gateway_url + "/api/models/circuit-reset?" + query, 5,
            data=b"", headers={},
        )
        if status != 200:
            raise RuntimeError("synthetic circuit reset returned HTTP " + str(status)
                               + ": " + response_body[:200].decode("utf-8", "replace"))


def set_synthetic_fault(base_url, fault_mode):
    """Configure the synthetic fault only from the allow-listed gateway runner."""
    target = urlsplit(base_url)
    if target.hostname != "gateway" or target.port != 8080:
        if fault_mode != "none":
            raise ValueError("fault injection is available only from the isolated gateway:8080 runner")
        return False
    if fault_mode not in ALLOWED_FAULT_MODES:
        raise ValueError("unsupported synthetic fault mode")
    admin_secret = os.environ.get("MOCK_ADMIN_SECRET", "benchmark-private-fault-control")
    body = json.dumps({"mode": fault_mode}, separators=(",", ":")).encode("utf-8")
    status, response_body, _headers = request_synthetic_control(
        "http://mock-upstream:8377/admin/fault", 5, data=body,
        headers={"Content-Type": "application/json", SYNTHETIC_ADMIN_KEY_HEADER: admin_secret},
    )
    if status != 200:
        raise RuntimeError("synthetic mock fault configuration returned HTTP " + str(status)
                           + ": " + response_body[:200].decode("utf-8", "replace"))
    return True


def run(base_url, count, concurrency, rate, timeout, streaming, model, expected_model, fault_mode="none"):
    if count < 1 or count > MAX_TOTAL_REQUESTS:
        raise ValueError(f"--requests must be between 1 and {MAX_TOTAL_REQUESTS}")
    if concurrency < 1 or concurrency > MAX_WORKERS:
        raise ValueError(f"--concurrency must be between 1 and {MAX_WORKERS}")
    if rate <= 0 or rate > MAX_REQUESTS_PER_SECOND:
        raise ValueError(f"--rps must be greater than 0 and at most {MAX_REQUESTS_PER_SECOND}")
    if timeout <= 0 or timeout > 30:
        raise ValueError("--timeout must be greater than 0 and at most 30 seconds")
    if fault_mode not in ALLOWED_FAULT_MODES:
        raise ValueError("--fault-mode must be one of: " + ", ".join(sorted(ALLOWED_FAULT_MODES)))
    if count / rate > MAX_RUN_SECONDS:
        raise ValueError(f"configured pacing exceeds the {MAX_RUN_SECONDS}-second run budget")

    body = make_payload(model, streaming)
    headers = {"Content-Type": "application/json", "Accept": "text/event-stream" if streaming else "application/json", "X-Requester": "NeuralGatewaySyntheticBenchmark"}
    schedule_lock = threading.Lock()
    next_request = 0
    stop_event = threading.Event()

    def worker():
        nonlocal next_request
        worker_results = []
        while not stop_event.is_set():
            with schedule_lock:
                if stop_event.is_set() or next_request >= count:
                    break
                index = next_request
                next_request += 1
                due = start + index / rate
            delay = due - time.perf_counter()
            if delay > 0:
                time.sleep(delay)
            if stop_event.is_set():
                break

            started = time.perf_counter()
            try:
                status, response_body, _response_headers = request_json(
                    base_url + "/v1/chat/completions", timeout, data=body, headers=headers,
                )
                elapsed_ms = (time.perf_counter() - started) * 1000
                actual_models, valid_response = parse_response_model_ids(response_body, streaming) if status == 200 else ([], False)
                model_matched = bool(actual_models) and all(
                    not expected_model or actual_model == expected_model for actual_model in actual_models
                )
                valid = status == 200 and valid_response and model_matched
                if fault_mode == "http-503":
                    fault_observed = status >= 500
                elif fault_mode == "model-mismatch":
                    fault_observed = status == 200 and valid_response and bool(actual_models) and any(
                        actual_model != expected_model for actual_model in actual_models
                    )
                else:
                    fault_observed = False
                ok = fault_observed if fault_mode != "none" else valid
                if not ok:
                    stop_event.set()
                worker_results.append({
                    "index": index,
                    "status": status,
                    "latency_ms": round(elapsed_ms, 2),
                    "actual_models": actual_models,
                    "valid": valid,
                    "fault_observed": fault_observed,
                    "ok": ok,
                    "error": None if ok else response_body[:300].decode("utf-8", "replace"),
                })
            except (urllib.error.URLError, TimeoutError, OSError, ValueError) as error:
                stop_event.set()
                worker_results.append({
                    "index": index,
                    "status": None,
                    "latency_ms": round((time.perf_counter() - started) * 1000, 2),
                    "actual_models": [],
                    "valid": False,
                    "fault_observed": False,
                    "ok": False,
                    "error": str(error),
                })
        return worker_results

    started_at = datetime.now(timezone.utc)
    start = time.perf_counter()
    results = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
        workers = [executor.submit(worker) for _ in range(concurrency)]
        for future in workers:
            results.extend(future.result(timeout=MAX_RUN_SECONDS + timeout + 5))

    elapsed_seconds = time.perf_counter() - start
    latencies = [result["latency_ms"] for result in results]
    statuses = Counter(str(result["status"]) if result["status"] is not None else "connection_error" for result in results)
    actual_models = Counter(
        actual_model for result in results
        for actual_model in (dict.fromkeys(result["actual_models"]) if result["actual_models"] else ["unknown"])
    )
    errors = [result for result in results if not result["ok"]]
    faults_observed = sum(result["fault_observed"] for result in results)
    validation_passed = bool(results) and faults_observed == len(results) if fault_mode != "none" else bool(results) and not errors
    report = {
        "started_at_utc": started_at.isoformat(),
        "base_url": base_url,
        "requested_model": model,
        "expected_upstream_model": expected_model,
        "streaming": streaming,
        "fault_mode": fault_mode,
        "faults_observed": faults_observed,
        "validation_passed": validation_passed,
        "configured": {"requests": count, "concurrency": concurrency, "rps": rate, "timeout_seconds": timeout},
        "completed": len(results),
        "elapsed_seconds": round(elapsed_seconds, 3),
        "achieved_requests_per_second": round(len(results) / elapsed_seconds, 3) if elapsed_seconds else 0,
        "status_counts": dict(statuses),
        "actual_model_counts": dict(actual_models),
        "errors": errors[:10],
        "latency_ms": {
            "min": min(latencies) if latencies else None,
            "mean": round(statistics.fmean(latencies), 2) if latencies else None,
            "p50": percentile(latencies, 0.50),
            "p95": percentile(latencies, 0.95),
            "p99": percentile(latencies, 0.99),
            "max": max(latencies) if latencies else None,
        },
        "success_rate": round(sum(result["valid"] for result in results) / len(results), 4) if results else 0,
    }
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://gateway:8080")
    parser.add_argument("--model", default="moonshotai/kimi-k3")
    parser.add_argument("--request-model", default=None,
                        help="OpenAI model field sent to the gateway; defaults to --model, or use a virtual alias")
    parser.add_argument("--requests", type=int, default=24)
    parser.add_argument("--concurrency", type=int, default=2)
    parser.add_argument("--rps", type=float, default=2.0)
    parser.add_argument("--timeout", type=float, default=10)
    parser.add_argument("--stream", action="store_true")
    parser.add_argument("--fault-mode", choices=sorted(ALLOWED_FAULT_MODES), default="none",
                        help="opt-in mock fault validation (only on the isolated gateway:8080 runner)")
    parser.add_argument("--all-models", action="store_true", help="run each configured model sequentially with conservative defaults")
    parser.add_argument("--output", default=None, help="optional JSON report path")
    args = parser.parse_args()

    try:
        target = urlsplit(args.base_url)
        if target.scheme != "http" or (target.hostname, target.port) not in ALLOWED_TARGETS or target.path not in {"", "/"}:
            raise ValueError("synthetic benchmark target must be gateway:8080 or localhost:19090 on plain HTTP")
        models = DEFAULT_MODELS if args.all_models else (args.model,)
        configured = list_gateway_models(args.base_url)
        missing = sorted(set(models) - configured)
        if missing:
            raise RuntimeError("gateway is missing requested configured model(s): " + ", ".join(missing))

        reports = []
        reset_synthetic_gateway_state(args.base_url, models)
        fault_controlled = set_synthetic_fault(args.base_url, args.fault_mode)
        try:
            for model in models:
                request_model = args.request_model or model
                print(f"Starting {'streaming' if args.stream else 'non-streaming'} synthetic run: model={model}, request_model={request_model}, requests={args.requests}, concurrency={args.concurrency}, rps={args.rps:g}, fault={args.fault_mode}", flush=True)
                report = run(
                    args.base_url.rstrip("/"), args.requests, args.concurrency, args.rps,
                    args.timeout, args.stream, request_model, model, args.fault_mode,
                )
                reports.append(report)
                print(json.dumps(report, indent=2))
                if report["errors"]:
                    print("Stopping model sequence after first model reported errors.", file=sys.stderr)
                    break
        finally:
            if fault_controlled and args.fault_mode != "none":
                set_synthetic_fault(args.base_url, "none")
            reset_synthetic_gateway_state(args.base_url, models)

        output = {"benchmark": "isolated-synthetic-gateway", "reports": reports}
        if args.output:
            output_path = Path(args.output)
            output_path.parent.mkdir(parents=True, exist_ok=True)
            output_path.write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
            print("Saved report to " + str(output_path.resolve()))
        return 0 if reports and all(report["validation_passed"] for report in reports) else 1
    except (OSError, RuntimeError, ValueError, concurrent.futures.TimeoutError) as error:
        print("benchmark failed safely: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())