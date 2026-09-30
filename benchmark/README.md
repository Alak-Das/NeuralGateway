# Isolated gateway benchmark

This is a **synthetic-only** baseline. The Compose project runs a separate copy
of the gateway, a disposable Redis instance, and a deterministic fake
OpenAI-compatible upstream. All three configured providers are redirected to
the fake upstream; all benchmark containers share a Docker network marked
`internal: true`. Neither benchmark application port is published to the host.
A short-lived Python runner container executes inside the internal network and
posts only to the synthetic gateway service. It does not use the application's
Compose project, live gateway on port 9090, live Redis, `.env`, or real provider
credentials.

The Python driver also refuses any target other than the synthetic service
`http://gateway:8080` or `http://127.0.0.1:19090`, making it unsuitable for
real-provider testing. It applies caps of 8 RPS, 16 workers, and 240 requests
per model-run as defense in depth.

## Prerequisites

- Docker Desktop / Docker Compose.
- `mvn`, Python 3, and Java 21.
- Locally available images `neuralgateway-neural-gateway:latest`,
  `redis:7-alpine`, and `python:3.11-slim`. The two benchmark runtime images
  are set to `pull_policy: never`; no image pull is needed or attempted.

### Driver scripts (tracked in this repo, not ignored by `.gitignore`)

- **`load_test.py`** — Conservative, dependency-free HTTP load driver (`benchmark-runner` container). Sends bounded synthetic chat-completion traffic to the isolated gateway, enforces hard caps (8 RPS, 16 workers, 240 requests/model-run), validates provider-reported model IDs, and emits JSON latency/error reports. Refuses to target anything other than the synthetic service (`gateway:8080` or `127.0.0.1:19090`).
- **`mock_openai.py`** — Deterministic OpenAI-compatible upstream (`mock-upstream` container). Serves `/v1/chat/completions`, `/v1/models`, `/health`, `/stats`, and an admin `/admin/fault` endpoint for injecting HTTP 503 or model-ID mismatches on the isolated network only. Protects fault controls with a synthetic admin key.
- **`provider_smoke.py`** — Low-volume live-provider smoke check. Runs outside Compose against the running gateway at `127.0.0.1:9090`. Uses the single-model health-ping endpoint (no failover), validates all twelve configured models appear in `/v1/models` and `/api/models/status` before pinging, enforces strict pacing (≤28.6 RPM NVIDIA, ≤4 RPS others), and stops on any rate-limit/quota/auth error or two consecutive provider failures. Writes a status/latency summary JSON.

Build the gateway artifact first (tests are deliberately not invoked here):

```powershell
mvn -DskipTests package
```

Start the separate stack from the repository root:

```powershell
docker compose -p neuralgateway-benchmark -f benchmark/docker-compose.yml up -d
docker compose -p neuralgateway-benchmark -f benchmark/docker-compose.yml ps
```

Wait until the gateway starts, then run a low-rate baseline:

```powershell
docker compose -p neuralgateway-benchmark -f benchmark/docker-compose.yml --profile runner run --rm benchmark-runner --base-url http://gateway:8080 --requests 24 --concurrency 2 --rps 2 --output /reports/baseline.json
```

By default, `--model` is used both as the request ID and as the expected
provider-reported model ID. Use `--request-model` to select a virtual alias
such as `coding`, `vision`, or `auto` and continue validating the provider's
physical response ID against `--model`.

The harness sends bounded, identical short-prompt requests. It reports achieved
requests/second, HTTP status counts, successful-response ratio, all observed
provider-reported model IDs, and min/mean/p50/p95/p99/max end-to-end latency. An
error, malformed response, or upstream model mismatch stops the current model's
run; `--all-models` walks the ten configured model IDs sequentially and stops on
the first failure.

## Synthetic concurrency ramp

First exercise just one model with a fixed arrival-rate cap and gradually
increase concurrency. Save each run separately so a slower tail can be
compared, without changing request mix:

```powershell
docker compose -p neuralgateway-benchmark -f benchmark/docker-compose.yml --profile runner run --rm benchmark-runner --base-url http://gateway:8080 --requests 32 --concurrency 1 --rps 4 --output /reports/c1.json
docker compose -p neuralgateway-benchmark -f benchmark/docker-compose.yml --profile runner run --rm benchmark-runner --base-url http://gateway:8080 --requests 32 --concurrency 2 --rps 4 --output /reports/c2.json
docker compose -p neuralgateway-benchmark -f benchmark/docker-compose.yml --profile runner run --rm benchmark-runner --base-url http://gateway:8080 --requests 32 --concurrency 4 --rps 4 --output /reports/c4.json
```

For coverage of every configured model, after the single-model smoke run:

```powershell
docker compose -p neuralgateway-benchmark -f benchmark/docker-compose.yml --profile runner run --rm benchmark-runner --base-url http://gateway:8080 --all-models --requests 4 --concurrency 1 --rps 2 --output /reports/all-models.json
```

Streaming is a separate optional check:

```powershell
docker compose -p neuralgateway-benchmark -f benchmark/docker-compose.yml --profile runner run --rm benchmark-runner --base-url http://gateway:8080 --stream --requests 4 --concurrency 1 --rps 1 --output /reports/streaming.json
```

Both streaming and non-streaming responses are checked against their actual
provider-reported model IDs. Streaming validates every SSE JSON chunk's `model`
field and requires a model ID and the terminal `[DONE]` marker. An optional
synthetic-only fault pass checks that HTTP failures and model-ID mismatches are
reported (and still fails the normal response-validity metric):

```powershell
docker compose -p neuralgateway-benchmark -f benchmark/docker-compose.yml --profile runner run --rm benchmark-runner --base-url http://gateway:8080 --fault-mode http-503 --requests 2 --concurrency 1 --rps 1 --output /reports/fault-503.json
docker compose -p neuralgateway-benchmark -f benchmark/docker-compose.yml --profile runner run --rm benchmark-runner --base-url http://gateway:8080 --stream --fault-mode model-mismatch --requests 2 --concurrency 1 --rps 1 --output /reports/fault-model-mismatch.json
```

The mock exposes fault configuration only on the isolated Compose network; the
driver refuses to configure faults on the host-published localhost target and
requires the configured synthetic admin key on that internal network. The key
is passed only to the isolated runner and mock containers. Fault controls go
only to `mock-upstream:8377` from the allow-listed `gateway:8080` runner, use no
proxy, and never follow redirects. Before and after each run the runner resets
only selected models' circuit breakers through the gateway's existing reset
endpoint. Neither benchmark service port is published on the host.

The baseline does not exercise vision payloads; vision requires separate real
provider validation and is not part of the synthetic throughput run.

## Resource and Redis observations

Capture a before/after sample while a run is active, without touching the
existing Compose project's containers:

```powershell
docker stats --no-stream neuralgateway-benchmark-gateway-1 neuralgateway-benchmark-benchmark-redis-1 neuralgateway-benchmark-mock-upstream-1
docker compose -p neuralgateway-benchmark -f benchmark/docker-compose.yml exec -T benchmark-redis redis-cli INFO memory
docker compose -p neuralgateway-benchmark -f benchmark/docker-compose.yml exec -T benchmark-redis redis-cli INFO stats
```

Container names are Compose-version-dependent; if a name differs, use the names
shown by the benchmark project's `docker compose ... ps`. Record CPU/memory,
Redis `used_memory`, `total_commands_processed` and `instantaneous_ops_per_sec`
alongside the JSON latency/error report.

## Stop and cleanup

Stop the synthetic stack immediately if the mock is unhealthy or any response
does not identify the requested model. Remove only this named benchmark
project when finished:

```powershell
docker compose -p neuralgateway-benchmark -f benchmark/docker-compose.yml down
```

## Bounded live-provider smoke (optional, calls real providers)

The synthetic suite never sends real-provider traffic. To perform the next
approved stage, run the separate driver against the existing live gateway only
after reviewing its source. It is pinned to `127.0.0.1:9090`, checks that all
twelve agreed configured model IDs appear in the live `/v1/models` and
`/api/models/status` responses before sending anything, and invokes exactly one
`POST /api/models/ping?model=...` per selected model. It sends no chat prompt
to the completion endpoint, does not retry or fail over, sends no API
credentials, and writes only a model/status/latency summary. As an additional
baseline safeguard, it refuses to send any pings if even one of the twelve models
already has a status entry. The current live gateway does have such history, so
the script is expected to exit at preflight without creating a summary; do not
clear or replace that state just to force the smoke run. A small ping can
nevertheless update live model-health, circuit-breaker, Redis, and UI state.
`application.yml` currently configures thirteen models; the driver validates the
twelve agreed IDs (the explabs `gpt-6-luna` is not part of the smoke list). The
status endpoint may additionally list historical model IDs, which the driver
deliberately ignores.

The driver tests healthy models first, serializes all calls (maximum one in
flight), waits at least 2.1 seconds between starts to NVIDIA (28.6 RPM, below
the 30 RPM aggregate budget), and spaces other-provider calls by at least 0.25
seconds (at most 4 RPS). It stops immediately on a reported rate limit, quota,
or authentication error, or after two consecutive failures from one provider.
There is no override for provider origin or pacing.

```powershell
python -B benchmark/provider_smoke.py --output benchmark/results/provider-smoke.json
```

The gateway's health-ping endpoint targets the requested physical model
directly (without completion failover) and rejects missing or mismatched
upstream `model` IDs. AntSeed models are configured under their
provider-qualified canonical IDs (e.g. `openai/gpt-oss-120b`,
`Qwen/Qwen3-235B-A22B-Instruct-2507`, `zai-org/GLM-5.3-Flash`), which the
aggregator echoes back verbatim, so matching is exact for them. As a safety
net, `allow-qualified-model-ids: true` additionally tolerates qualified/alias
IDs (e.g. a bare `gpt-oss-120b` in the response for requested
`openai/gpt-oss-120b`) while still rejecting a missing `model` field. A
successful ping therefore confirms the provider served the requested model.
The previous 4 RPS approved proposal applies only to a later synthetic or
separately controlled per-provider measurement, not a simultaneous multi-model
mix against one provider.