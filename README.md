# Neural Gateway

**Neural Gateway by Alak** is an enterprise-grade, high-performance LLM routing gateway built with Spring Boot, Spring WebFlux, and Redis. It provides intelligent load balancing, dynamic failover, context-aware payload routing, tool call normalization, and real-time observability across multiple AI providers — including NVIDIA NIM, **Experiential Labs**, and **Antseed**.

---

## 🚀 Key Features

### 1. Intelligent Pipeline Routing
Neural Gateway organizes models into dedicated, purpose-tuned pipelines accessible through the standard `/v1/chat/completions` endpoint:
- **Reasoning Pipeline (`model: "reasoning"`)**: Routes complex multi-step reasoning tasks across frontier reasoning models (e.g., Nemotron-3 Ultra 550B, Kimi K3, GLM-5.3, DeepSeek v4.1).
- **Coding Pipeline (`model: "coding"`)**: Prioritizes low-latency, code-specialized models (e.g., Nemotron-3 Super 120B, GLM-5.3, antseed).
- **Vision Pipeline (`model: "vision"`)**: Routes multimodal text + image queries to vision-instruct models (e.g., Kimi K3, DeepSeek v4.1 Flash, GLM-5.3 Flash) with intelligent image token budgeting.
- **Dynamic Auto-Detection (`model: "auto"`)**: Multi-tier capability resolution inspects message structure (multimodal images, IDE tool calls, code blocks) to automatically dispatch to the optimal pipeline.

### 2. Low-Latency Load Balancing & Telemetry
- **In-Memory O(1) Routing Score**: Combines an Exponential Moving Average (EMA) latency calculation with an active-connection penalty (`score = emaLatency + (activeConnections * 300ms)`).
- **Zero-Latency Request Path**: Health ping results update EMA in-memory, avoiding synchronous database queries during request routing.
- **Context-Aware Window Validation**: Automatically filters out models whose context windows cannot accommodate the estimated payload tokens (preventing truncation and 400 Bad Request errors).

### 3. High Availability & Circuit Breaking
- **Resilience4j Circuit Breaker**: Models returning consecutive server errors (5xx, timeouts, or premature close exceptions) automatically trip an isolated sliding-window circuit breaker. Circuit state transitions are intercepted and synced globally to Redis.
- **Dynamic API Key Cooldown**: When an upstream provider responds with HTTP 429 Too Many Requests, the offending API key is immediately isolated with a 30-second cooldown, rotating traffic instantly to healthy keys.
- **Emergency Degraded Mode**: If all model circuits in a pipeline trip during upstream provider incidents, the gateway automatically falls back to highest-priority models ordered by lowest latency, eliminating 100% gateway blackouts and enabling traffic-driven self-healing.
- **Safe 4xx Handling**: Client payload mistakes (400 Bad Request, 422 Unprocessable Entity) are immediately returned to the client and never falsely trip model circuit breakers.
- **Multi-Provider Failover**: Requests are routed across **all configured providers** (NVIDIA NIM, Experiential Labs, Antseed, etc.) as a single logical fleet. Provider-specific failures — including upstream `401`/`403`/`404` responses and quota errors such as `token_quota_exceeded` — trigger transparent failover to the next candidate model, which may live on a different provider entirely.
- **Request Sanitization for Cross-Provider Compatibility**: Non-standard client fields are normalised before dispatch — `thinking_effort` and Anthropic-style `thinking` blocks are translated to `reasoning_effort`, and `reasoning_effort` is coerced to the OpenAI-standard set (`none`, `low`, `medium`, `high`). This prevents `400 wrong_api_format` rejections from stricter providers and lets the gateway fail over instead of surfacing a spurious client error.
- **Auto-Recovery**: Tripped circuit breakers automatically reset to closed as soon as background health checks succeed. A successful probe is treated as authoritative: `HealthCheckService` calls `CircuitBreakerService.markHealthy()` to force-close any stale OPEN/HALF_OPEN circuit (including one restored from Redis) and syncs the CLOSED state back to Redis, so a recovered model becomes routable immediately instead of waiting out the passive half-open timeout.
- **Smart Model Recovery Backoff**: Models marked unhealthy by a transient routed failure are re-probed by a dedicated recovery sweep with exponential backoff (30s → 120s cap, ±20% jitter) that is shared across gateway replicas via Redis. A single successful probe clears the backoff and restores the model immediately — no need to wait for the full health-check cycle. Provider-wide outages (upstream `401`/`403`/`404`, quota errors, rate limits) never falsely flag an individual model as DOWN, so failover and the dashboard stay accurate.
- **Zero Cold-Start Lag (Redis Bootstrapping)**: Restores previous health states, latencies, circuit status, and token usage from Redis on startup so the gateway immediately routes to proven healthy models without waiting for health checks.
- **Resilient Fallback Routing**: During cold-starts or temporary upstream outages, candidate models are sorted by lowest historical EMA score and tried with up to 3 fallback attempts.
- **Fail-Fast Failover**: Transparently retries candidate models on server-side failures with strict attempt caps to eliminate cascading delays.

### 4. Resilient Distributed Health Checker
- **ShedLock Distributed Scheduling**: Prevents redundant health check sweeps across horizontally scaled gateway instances by utilizing a Redis-backed distributed lock.
- **4-Minute Sweep Frequency**: Automated health check sweeps run every 4 minutes (`fixedDelay = 240000ms`, configurable via `llm.health-check.intervalMs`), refreshing model statuses without placing continuous load on upstream providers. Increase this value (or the initial delay) while testing to minimise upstream token consumption.
- **Prioritized Ping Ordering**: Models are sorted by historical EMA latency (fastest first), ensuring the most responsive models are verified earliest during each sweep.
- **Jittered Concurrency**: Each sweep pings up to 10 models in parallel with configurable jitter (`±5s`) to prevent thundering herd patterns against providers.
- **Redis State Persistence**: Health results (UP/DOWN, latency, failure counts, circuit state) are persisted to Redis and published via Pub/Sub for real-time dashboard updates.
- **Per-Model Keys with Configurable Data TTL**: All telemetry is stored under individual per-model Redis keys (`gateway:<type>:<modelId>`) instead of monolithic hash keys, allowing each key to expire independently. The retention period is fully configurable via `LLM_DATA_RETENTION_TTL_HOURS` (default: 24 hours) and `LLM_DATA_RETENTION_CLEANUP_INTERVAL_MINUTES` (default: 60 minutes), preventing memory bloat and ensuring the system never relies on stale data.
- **Independent Recovery Sweep**: A dedicated low-cost sweep (default every 5s, max 2 models per sweep, configurable via `llm.health-check.recoveryIntervalMs` / `recoveryMaxModelsPerSweep`) probes only models flagged unhealthy by routed failures, honouring each model's exponential backoff schedule. Recovery begins in seconds instead of waiting for the next full 4-minute sweep, while skipped providers are never pinged during an active outage.
- **Configurable Ping Token Budget & Provider ID Matching**: Health probes use a small, configurable token budget (`llm.health-check.ping-max-tokens`, default 16) so stricter models that reject tiny probes (e.g. explabs `gpt-6-luna`) still pass. Providers that echo qualified canonical IDs (e.g. Antseed's `openai/gpt-oss-120b`) can opt in via `allow-qualified-model-ids: true` so probes accept the qualified/alias ID while still rejecting a missing `model` field.
- **Enabled by Default**: Health checks are ON out of the box (`llm.health-check.enabled`, default `true`, override via `LLM_HEALTH_CHECK_ENABLED`). Without them, models that go down would never be re-probed and the dashboard would keep showing stale statuses and stale OPEN circuits forever.
- **Fast NVIDIA Recovery Pacing**: The NVIDIA fleet re-probes with a 2-minute interval (`health-check-interval-ms: 120000`, models probed round-robin) and caps the exponential overload backoff at 10 minutes (`health-check-max-backoff-ms: 600000`), so a down model is re-probed within minutes instead of waiting up to an hour.

### 5. Requester Telemetry & Observability
- **Per-Requester Analytics**: Tracks request counts, token usage (prompt/completion/total), and latency percentiles (p50/p95/p99) grouped by the `X-Requester` header.
- **Real-Time SSE Dashboard**: Live Server-Sent Events stream at `/api/models/status/stream` and `/api/requesters/status/stream` push updates to the React frontend without polling.
- **Enhanced Dashboard Visualization**: React-based frontend now includes **Success Rate History** charts alongside latency and usage metrics, enabling operators to monitor model reliability trends over time.
- **Structured Logging with MDC**: Every request carries a transaction ID and requester identity through MDC (Mapped Diagnostic Context) for end-to-end traceability.
- **Background Probe Identification**: Health check results now distinguish between automated background sweeps and user-initiated pings, allowing for filtered analytics and cleaner observability data.
- **Status Freshness Indicators**: Each model row shows a `FRESH` / `STALE` / `NO PROBE` badge based on the last probe timestamp (24-hour freshness window, exposed as `statusFresh` on the model status payload), plus a `PROBE DOWN` label and a circuit-block hint (`Routing blocked` / `No breaker block`) so operators can tell a stale status from a live failure at a glance. When a probe succeeds but the circuit is still OPEN, the row shows an amber **`UP · BLOCKED`** badge so it is clear the model is healthy but requests are still being blocked.
- **Swagger/OpenAPI Documentation**: Interactive API explorer available at `/swagger-ui.html` and `/v3/api-docs`.

### 6. Open WebUI Integration
- **Built-in Chat Interface**: Includes Open WebUI as a companion service in Docker Compose, providing a full-featured chat interface at `http://localhost:3000`.
- **OpenAI API Compatibility**: Open WebUI connects to Neural Gateway via the OpenAI-compatible `/v1` endpoints, enabling seamless model switching and pipeline routing through a familiar UI.
- **No Authentication Required**: Configured with `WEBUI_AUTH=False` for immediate access in development environments.
- **Persistent Data**: Chat history, settings, and uploaded files persisted in `./open-webui-data/` volume.

---

## 🛠️ Tech Stack

- **Framework**: Spring Boot 3.3.4 (Java 21 with Virtual Threads)
- **Reactive Engine**: Spring WebFlux (`WebClient`) with Connection Pooling & Keep-Alive
- **Resilience & Fault Tolerance**: Resilience4j CircuitBreaker, ShedLock Distributed Locking
- **Data & Telemetry**: Redis 7 Alpine (persistent volume, Pub/Sub SSE)
- **Frontend**: React 19, TypeScript, Vite, Chart.js, Bootstrap Icons
- **UI Integration**: Open WebUI (ghcr.io/open-webui/open-webui:main)
- **Packaging & Orchestration**: Multi-stage Docker build, Docker Compose

---

## 🔌 API Endpoints

Neural Gateway strictly implements the official OpenAI API specification for LLM consumption, combined with clean `/api/...` endpoints for telemetry and fleet administration:

### 1. OpenAI Standard Endpoints (`/v1`)
Drop-in replacement for OpenAI SDKs, IDE extensions (Cline, Cursor, Roo-Code), and AI agents:

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/v1/chat/completions` | **Universal Chat Completion**: Dynamically routes to the fastest healthy model using multi-tier capability detection (Coding, Reasoning, Vision). Fully supports streaming (SSE), tool calling, and automatic failover. |
| `GET` | `/v1/models` | **List Models**: Returns all active physical models plus virtual pipeline aliases (`coding`, `reasoning`, `vision`, `auto`). |
| `GET` | `/v1/models/{modelId}` | **Retrieve Model**: Returns metadata for a specific model ID in standard OpenAI format. |

### 2. Fleet Health, Diagnostics & Telemetry (`/api`)
Used by the React monitoring dashboard and operations tooling:

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/models/status` | Current operational status, EMA latency, active connections, and circuit breaker states across all models. |
| `GET` | `/api/models/status/stream` | Real-time Server-Sent Events (SSE) feed emitting status updates as health check sweeps complete. |
| `POST` | `/api/models/ping?model={name}` | On-demand synchronous health ping to verify a specific model's latency and availability. |
| `POST` | `/api/models/circuit-reset?model={name}` | Manually reset a tripped circuit breaker to immediately restore model traffic. |
| `GET` | `/api/requesters/status` | Request volume and token usage metrics grouped by calling client (`X-Requester`). |
| `GET` | `/swagger-ui.html` | Interactive Swagger/OpenAPI documentation and API explorer. |

---

## 📦 Getting Started

### Prerequisites
- Docker & Docker Compose
- An NVIDIA NIM API key ([build.nvidia.com](https://build.nvidia.com/))
- An Experiential Labs API key _(optional, enables `mimo-v2.6-pro`, `gpt-6-luna`)_
- An Antseed API key _(optional, enables `deepseek-v4-flash`, `zai-org/GLM-5.3-Flash`, `openai/gpt-oss-120b`, `Qwen/Qwen3-235B-A22B-Instruct-2507`)_

### Installation & Deployment

1. **Clone the repository:**
   ```bash
   git clone https://github.com/Alak-Das/NeuralGateway.git
   cd NeuralGateway
   ```

2. **Configure your API keys:**
   Create a `.env` file with your provider keys:
   ```env
   NVIDIA_API_KEY=nvapi-your-key-here
   EXPLABS_API_KEY=xpl-your-key-here
   ANTSEED_API_KEY=ant-your-key-here
   ```
   You can also configure multiple keys for automatic rotation and rate-limit distribution:
   - `NVIDIA_API_KEY_1`, `NVIDIA_API_KEY_2`, `NVIDIA_API_KEY_3`
   - `EXPLABS_API_KEY` (single key supported)
   - `ANTSEED_API_KEY` (single key supported, optional — enables the Antseed fleet)

3. **Launch the Gateway:**
   ```bash
   docker compose up --build -d
   ```
   This starts three services:
   - **Neural Gateway API & Dashboard** on `http://localhost:9090` (use `http://127.0.0.1:9090` on Windows — see **WSL Relay Note** below)
   - **Redis** on `localhost:6379`
   - **Open WebUI** on `http://localhost:3000`

> **⚠️ Windows / WSL Relay Note**  
> On Windows hosts running Docker Desktop with WSL 2 integration, the `wslrelay` process may bind to the IPv6 loopback (`::1:9090`) while the gateway listens on `0.0.0.0:9090`. This causes `localhost` (which resolves to `::1` on Windows) to hit the relay instead of the container, leading to time-outs. Always use **`127.0.0.1`** (explicit IPv4) for local API calls on Windows:
> ```bash
> curl -X POST http://127.0.0.1:9090/v1/chat/completions ...
> ```
> Configure IDE clients (Cline, Cursor, Roo Code, Continue) with **Base URL: `http://127.0.0.1:9090/v1`**.

4. **Access the Interfaces:**
   - **Neural Gateway Dashboard**: Open `http://localhost:9090` for real-time fleet health, latency, and throughput metrics.
   - **Open WebUI Chat Interface**: Open `http://localhost:3000` for a chat interface connected to Neural Gateway via `/v1`.
   - **Swagger API Docs**: Open `http://localhost:9090/swagger-ui.html` for interactive OpenAPI exploration.

---

## 💡 Usage Examples

### 1. OpenAI Standard Chat Completion (Coding Task)
```bash
curl -X POST http://127.0.0.1:9090/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "X-Requester: Cline" \
  -d '{
    "model": "coding",
    "messages": [
      {"role": "user", "content": "Write a Python function to compute Fibonacci numbers."}
    ],
    "stream": true
  }'
```

### 2. Deep Reasoning Prompt
```bash
curl -X POST http://127.0.0.1:9090/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "X-Requester: Analyst" \
  -d '{
    "model": "reasoning",
    "messages": [
      {"role": "user", "content": "Analyze the systemic implications of rising treasury yields on commercial real estate."}
    ]
  }'
```

### 3. Multimodal Vision Inspection
```bash
curl -X POST http://127.0.0.1:9090/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "X-Requester: VisionClient" \
  -d '{
    "model": "vision",
    "messages": [
      {
        "role": "user",
        "content": [
          {"type": "text", "text": "Describe the contents of this image in detail."},
          {
            "type": "image_url",
            "image_url": {
              "url": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
            }
          }
        ]
      }
    ]
  }'
```

### 4. Client IDE Setup (Cline, Cursor, Roo Code, Continue)
Configure your IDE's OpenAI-compatible provider:
- **API Provider**: `OpenAI Compatible`
- **Base URL**: `http://127.0.0.1:9090/v1`
- **API Key**: `neural-gateway` *(any string)*
- **Model ID**: `coding` *(or `auto`, or a specific model like `z-ai/glm-5.3`)*

This standard OpenAI interface works seamlessly with Open WebUI, Cline, Cursor, Roo Code, Continue.dev, and official OpenAI SDKs.

---

## 📚 Documentation
- **[Product Requirements Document (PRD)](PRD.md)** — Product vision, problem statement, and requirements.
- **[Design Document](DESIGN.md)** — Architecture overview, system layers, and technical design.
- **[Benchmark Guide](benchmark/README.md)** — Isolated synthetic-only benchmark suite (mock upstream + disposable Redis on an internal network) and a bounded live-provider smoke test.

---

## 📄 License
This project is licensed under the MIT License.

*Created and maintained by Alak Das.*