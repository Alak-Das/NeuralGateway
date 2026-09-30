"""Low-volume, direct-model health smoke checks through the running gateway.

This tool deliberately uses the gateway's single-model health-ping endpoint,
not its chat-completion endpoint: a completion failure can trigger automatic
cross-model/cross-provider failover and make one client request fan out into
many upstream calls. Health pings target exactly one configured model and do
not retry or fail over. They still call the real provider and update that
model's health/Redis state.
"""

import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit


GATEWAY = "http://127.0.0.1:9090"
NVIDIA_MIN_INTERVAL_SECONDS = 2.1  # 28.6 RPM, below the 30 RPM aggregate cap.
OTHER_PROVIDER_MIN_INTERVAL_SECONDS = 0.25  # At most 4 requests/second/provider.
REQUEST_TIMEOUT_SECONDS = 125  # Longer than the gateway's 120-second upstream timeout.
MAX_MODELS = 32
PROVIDERS = {
    "moonshotai/kimi-k3": "nvidia",
    "z-ai/glm-5.3": "nvidia",
    "nvidia/nemotron-3-ultra-550b-a55b": "nvidia",
    "deepseek-ai/deepseek-v4.1-flash": "nvidia",
    "nvidia/nemotron-3-super-120b-a12b": "nvidia",
    "z-ai/glm-5.3-flash": "nvidia",
    "meta/llama-3.2-11b-vision-instruct": "nvidia",
    "mimo-v2.6-pro": "explabs",
    "deepseek-v4-flash": "antseed",
    "zai-org/GLM-5.3-Flash": "antseed",
    "openai/gpt-oss-120b": "antseed",
    "Qwen/Qwen3-235B-A22B-Instruct-2507": "antseed",
}
CONFIGURED_MODELS = (
    "moonshotai/kimi-k3",
    "z-ai/glm-5.3",
    "nvidia/nemotron-3-ultra-550b-a55b",
    "deepseek-ai/deepseek-v4.1-flash",
    "nvidia/nemotron-3-super-120b-a12b",
    "z-ai/glm-5.3-flash",
    "meta/llama-3.2-11b-vision-instruct",
    "mimo-v2.6-pro",
    "deepseek-v4-flash",
    "zai-org/GLM-5.3-Flash",
    "openai/gpt-oss-120b",
    "Qwen/Qwen3-235B-A22B-Instruct-2507",
)


def get_json(opener, path):
    request = urllib.request.Request(GATEWAY + path, headers={"Accept": "application/json"})
    with opener.open(request, timeout=10) as response:
        body = response.read()
    return json.loads(body)


def error_category(message, http_status=None):
    if http_status == 429:
        return "rate_limit"
    if http_status == 401 or http_status == 403:
        return "authentication"
    text = (message or "").lower()
    if "429" in text or "rate limit" in text or "too many requests" in text:
        return "rate_limit"
    if "quota" in text or "billing" in text:
        return "quota"
    if "401" in text or "403" in text or "authentication" in text or "unauthorized" in text:
        return "authentication"
    if "timeout" in text or "timed out" in text:
        return "timeout"
    if "503" in text or "502" in text or "capacity" in text or "overload" in text:
        return "upstream_unavailable"
    return "upstream_failure"


def main():
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default="benchmark/results/provider-smoke.json")
    parser.add_argument("--provider", action="append", dest="providers",
                        help="limit to a provider ID; may be repeated (default: all configured physical models)")
    args = parser.parse_args()

    parsed = urlsplit(GATEWAY)
    if parsed.scheme != "http" or parsed.hostname != "127.0.0.1" or parsed.port != 9090:
        print("Refusing to run: real-provider smoke target must remain http://127.0.0.1:9090", file=sys.stderr)
        return 2

    # Bypass ambient HTTP proxy settings: the only request destination is the
    # explicitly pinned local gateway, never a proxy or a provider URL.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        listed = get_json(opener, "/v1/models")
        statuses = get_json(opener, "/api/models/status")
    except (OSError, ValueError, urllib.error.URLError) as error:
        print("Preflight failed; no provider pings sent: " + type(error).__name__, file=sys.stderr)
        return 2

    existing_models = {item.get("model") for item in statuses if isinstance(item, dict)}
    if any(model_id in existing_models for model_id in CONFIGURED_MODELS):
        print(
            "Preflight stopped; one or more configured models already has live health history. "
            "This smoke would mix new data with a pre-existing status baseline. No provider pings sent.",
            file=sys.stderr,
        )
        return 2

    listed_ids = {
        item.get("id") for item in listed.get("data", [])
        if isinstance(item, dict) and isinstance(item.get("id"), str)
    }
    missing_listed = set(CONFIGURED_MODELS) - listed_ids
    if missing_listed:
        print("Preflight stopped; live gateway is missing configured model(s): " + ", ".join(sorted(missing_listed)), file=sys.stderr)
        return 2

    status_by_id = {
        item.get("model"): item for item in statuses
        if isinstance(item, dict) and isinstance(item.get("model"), str)
    }
    missing_status = set(CONFIGURED_MODELS) - status_by_id.keys()
    if missing_status:
        print("Preflight stopped; status is missing configured model(s): " + ", ".join(sorted(missing_status)), file=sys.stderr)
        return 2
    for model_id in CONFIGURED_MODELS:
        if status_by_id[model_id].get("provider") != PROVIDERS[model_id]:
            print(
                f"Preflight stopped; {model_id} belongs to provider "
                f"{status_by_id[model_id].get('provider')!r}, expected {PROVIDERS[model_id]!r}. No pings sent.",
                file=sys.stderr,
            )
            return 2

    models = [status_by_id[model_id] for model_id in CONFIGURED_MODELS]
    if args.providers:
        wanted = set(args.providers)
        models = [item for item in models if item.get("provider") in wanted]
        absent = wanted - {item.get("provider") for item in models}
        if absent:
            print("Preflight stopped; provider(s) are not configured: " + ", ".join(sorted(absent)), file=sys.stderr)
            return 2
    if not models or len(models) > MAX_MODELS:
        print(f"Preflight stopped; expected between 1 and {MAX_MODELS} models, found {len(models)}", file=sys.stderr)
        return 2

    started_at_utc = datetime.now(timezone.utc).isoformat()

    # Probe models that the gateway already considers healthy first. This
    # makes a fast global stop more likely if a provider is currently failing.
    models.sort(key=lambda item: (
        not bool(item.get("up", item.get("isUp", False))),
        str(item.get("provider", "")),
        str(item.get("model", "")),
    ))

    print(
        f"Read-only preflight found {len(CONFIGURED_MODELS)} configured physical model(s); "
        f"scheduled {len(models)} exact-model, one-token health ping(s). "
        "No completion/failover route will be used.",
        flush=True,
    )

    last_started_by_provider = {}
    failures_by_provider = Counter()
    results = []
    stop_reason = None

    for index, status in enumerate(models, start=1):
        model_id = status["model"]
        provider = status.get("provider")
        if not isinstance(provider, str) or not provider:
            stop_reason = "missing provider metadata for a configured model"
            break

        min_interval = NVIDIA_MIN_INTERVAL_SECONDS if provider.lower() == "nvidia" else OTHER_PROVIDER_MIN_INTERVAL_SECONDS
        previous_start = last_started_by_provider.get(provider)
        if previous_start is not None:
            wait_seconds = min_interval - (time.monotonic() - previous_start)
            if wait_seconds > 0:
                time.sleep(wait_seconds)

        query = urllib.parse.urlencode({"model": model_id})
        request = urllib.request.Request(
            GATEWAY + "/api/models/ping?" + query,
            headers={"Accept": "application/json", "X-Requester": "NeuralGatewayBoundedSmoke"},
            method="POST",
        )
        started = time.monotonic()
        last_started_by_provider[provider] = started
        print(f"[{index}/{len(models)}] ping provider={provider} requested_model={model_id}", flush=True)

        try:
            with opener.open(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
                http_status = response.status
                body = json.loads(response.read())
        except urllib.error.HTTPError as error:
            http_status = error.code
            body = {}
        except (urllib.error.URLError, TimeoutError, OSError, ValueError) as error:
            failures_by_provider[provider] += 1
            result = {
                "requested_model": model_id,
                "provider": provider,
                "http_status": None,
                "up": False,
                "latency_ms": round((time.monotonic() - started) * 1000, 2),
                "error_category": "gateway_timeout_or_connection_error" if isinstance(error, (TimeoutError, urllib.error.URLError)) else "invalid_response",
            }
            results.append(result)
            print(json.dumps(result), flush=True)
            if failures_by_provider[provider] >= 2:
                stop_reason = f"repeated failures from provider {provider}; stopping"
                break
            continue

        elapsed_ms = round((time.monotonic() - started) * 1000, 2)
        actual_model_field = body.get("model") if isinstance(body, dict) else None
        up = bool(body.get("up", body.get("isUp", False))) if isinstance(body, dict) else False
        message = body.get("errorMessage") if isinstance(body, dict) else None
        category = None if up and http_status == 200 else error_category(message, http_status)
        result = {
            "requested_model": model_id,
            "provider": provider,
            "gateway_ping_model": actual_model_field,
            "http_status": http_status,
            "up": up and http_status == 200 and actual_model_field == model_id,
            "latency_ms": elapsed_ms,
            "error_category": category,
        }
        results.append(result)
        print(json.dumps(result), flush=True)

        if result["up"]:
            failures_by_provider[provider] = 0
            continue

        failures_by_provider[provider] += 1
        if category in {"rate_limit", "quota", "authentication"}:
            stop_reason = f"{category} reported by provider {provider}; stopping immediately"
            break
        if failures_by_provider[provider] >= 2:
            stop_reason = f"repeated failures from provider {provider}; stopping"
            break

    report = {
        "benchmark": "bounded-real-provider-health-smoke",
        "started_at_utc": started_at_utc,
        "gateway": GATEWAY,
        "configured_physical_models": len(CONFIGURED_MODELS),
        "models_selected": len(models),
        "provider_limits": {
            "nvidia_minimum_seconds_between_requests": NVIDIA_MIN_INTERVAL_SECONDS,
            "other_providers_max_requests_per_second": 1 / OTHER_PROVIDER_MIN_INTERVAL_SECONDS,
            "maximum_in_flight": 1,
        },
        "request_shape": {
            "endpoint": "/api/models/ping",
            "prompt": "ping (fixed by the gateway's health-check implementation)",
            "max_output_tokens": "gateway health-check setting; application default is 1",
            "retries_or_failover": 0,
        },
        "warnings": [
            "Direct pings write model health and Redis history; the script refuses to run if any configured model already has a live status entry."
        ],
        "results": results,
        "stop_reason": stop_reason,
        "coverage_complete": len(results) == len(models),
        "actual_upstream_model_limitation": (
            "The direct ping endpoint targets exactly requested_model and returns that requested ID, but the gateway discards "
            "the upstream response's model field. This smoke therefore confirms a successful exact-target provider request, "
            "not an independently observed provider-reported model ID. Completion benchmarks require an explicit no-failover safeguard."
        ),
    }

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"Saved non-secret smoke summary to {output_path.resolve()}")
    if stop_reason:
        print("Stopped safely: " + stop_reason, file=sys.stderr)
        return 1
    return 0 if results and all(result["up"] for result in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())