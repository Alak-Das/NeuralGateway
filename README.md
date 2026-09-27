# Neural Gateway

**Neural Gateway by Alak** is an enterprise-grade, high-performance LLM routing gateway built with Spring Boot, Spring WebFlux, and Redis. It provides intelligent load balancing, dynamic failover, context-aware payload routing, tool call normalization, and real-time observability across multiple NVIDIA NIM AI models.

---

## 🚀 Key Features

### 1. Intelligent Pipeline Routing
Neural Gateway organizes models into dedicated, purpose-tuned pipelines:
- **Reasoning Pipeline (`/api/reasoning/chat/completions`)**: Routes complex multi-step reasoning tasks across frontier reasoning models (e.g., Nemotron-3 Ultra 550B, Kimi K3, GLM-5.3, DeepSeek v4.1, Gemma 4).
- **Coding Pipeline (`/api/coding/chat/completions`)**: Prioritizes low-latency, code-specialized models (e.g., GLM-5.3-Flash, Nemotron-3 Super 120B, Laguna-XS, Mistral-Nemotron).
- **Vision Pipeline (`/api/vision/chat/completions`)**: Routes multimodal text + image queries to vision-instruct models (e.g., Llama 3.2 11B/90B Vision Instruct) with intelligent image token budgeting.

### 2. Low-Latency Load Balancing & Telemetry
- **In-Memory O(1) Routing Score**: Combines an Exponential Moving Average (EMA) latency calculation with an active-connection penalty (`score = emaLatency + (activeConnections * 300ms)`).
- **Zero-Latency Request Path**: Health ping results update EMA in-memory, avoiding synchronous database queries during request routing.
- **Context-Aware Window Validation**: Automatically filters out models whose context windows cannot accommodate the estimated payload tokens (preventing truncation and 400 Bad Request errors).

### 3. High Availability & Circuit Breaking
- **Resilience4j Circuit Breaker**: Models returning consecutive server errors (5xx, timeouts, or premature close exceptions) automatically trip an isolated sliding-window circuit breaker. Circuit state transitions are intercepted and synced globally to Redis.
- **Dynamic API Key Cooldown**: When an upstream provider responds with HTTP 429 Too Many Requests, the offending API key is immediately isolated with a 30-second cooldown, rotating traffic instantly to healthy keys.
- **Emergency Degraded Mode**: If all model circuits in a pipeline trip during upstream provider incidents, the gateway automatically falls back to highest-priority models ordered by lowest latency, eliminating 100% gateway blackouts and enabling traffic-driven self-healing.
- **Safe 4xx Handling**: Client payload mistakes (400 Bad Request, 422 Unprocessable Entity) are immediately returned to the client and never falsely trip model circuit breakers.
- **Auto-Recovery**: Tripped circuit breakers automatically reset to closed as soon as background health checks succeed.
- **Zero Cold-Start Lag (Redis Bootstrapping)**: Restores previous health states, latencies, circuit status, and token usage from Redis on startup so the gateway immediately routes to proven healthy models without waiting for health checks.
- **Resilient Fallback Routing**: During cold-starts or temporary upstream outages, candidate models are sorted by lowest historical EMA score and tried with up to 3 fallback attempts.
- **Fail-Fast Failover**: Transparently retries candidate models on server-side failures with strict attempt caps to eliminate cascading delays.

### 4. Resilient Distributed Health Checker
- **ShedLock Distributed Scheduling**: Prevents redundant health check sweeps across horizontally scaled gateway instances by utilizing a Redis-backed distributed lock.
- **3-Minute Sweep Frequency**: Automated health check sweeps run every 3 minutes (`fixedDelay = 180000ms`), refreshing model statuses without placing continuous load on upstream providers.
- **Prioritized Ping Ordering**: Models are sorted by historical EMA latency (fastest first), ensuring the most responsive models are verified earliest during each sweep.
- **Jittered Concurrency**: Each sweep pings up to 10 models in parallel with configurable jitter (`±5s`) to prevent thundering herd patterns against providers.
- **Redis State Persistence**: Health results (UP/DOWN, latency, failure counts, circuit state) are persisted to Redis and published via Pub/Sub for real-time dashboard updates.
- **Per-Model Keys with Configurable Data TTL**: All telemetry is stored under individual per-model Redis keys (`gateway:<type>:<modelId>`) instead of monolithic hash keys, allowing each key to expire independently. The retention period is fully configurable via `LLM_DATA_RETENTION_TTL_HOURS` (default: 24 hours) and `LLM_DATA_RETENTION_CLEANUP_INTERVAL_MINUTES` (default: 60 minutes), preventing memory bloat and ensuring the system never relies on stale data.

### 5. Requester Telemetry & Observability
- **Per-Requester Analytics**: Tracks request counts, token usage (prompt/completion/total), and latency percentiles (p50/p95/p99) grouped by the `X-Requester` header.
- **Real-Time SSE Dashboard**: Live Server-Sent Events stream at `/api/models/status/stream` and `/api/requesters/status/stream` push updates to the React frontend without polling.
- **Enhanced Dashboard Visualization**: React-based frontend now includes **Error Percentage History** charts alongside latency and usage metrics, enabling operators to monitor model reliability trends over time.
- **Structured Logging with MDC**: Every request carries a transaction ID and requester identity through MDC (Mapped Diagnostic Context) for end-to-end traceability.
- **Background Probe Identification**: Health check results now distinguish between automated background sweeps and user-initiated pings, allowing for filtered analytics and cleaner observability data.
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

Neural Gateway is a drop-in replacement for OpenAI API endpoints:

| Pipeline | Direct Endpoint | OpenAI `/v1` Compatible Endpoint |
|---|---|---|
| **Coding** | `POST /api/coding/chat/completions` | `POST /v1/chat/completions`, `POST /chat/completions` |
| **Reasoning** | `POST /api/reasoning/chat/completions` | `POST /api/reasoning/v1/chat/completions` |
| **Vision** | `POST /api/vision/chat/completions` | `POST /api/vision/v1/chat/completions` |
| **Fleet Status** | `GET /api/models/status` | `GET /models/status` |
| **Status Stream** | `GET /api/models/status/stream` (SSE) | — |
| **Requester Telemetry** | `GET /api/requesters/status` | `GET /requesters/status` |
| **Manual Ping** | `POST /api/models/ping?model={name}` | — |
| **Reset Circuit** | `POST /api/models/circuit-reset?model={name}` | — |
| **Swagger UI** | `GET /swagger-ui.html` | `GET /v3/api-docs` |

**Note on endpoint routing**: The OpenAI-compatible `/v1/chat/completions` and `/chat/completions` aliases route to the **Reasoning** pipeline by default, ensuring maximum capability for general-purpose chat clients. The Coding pipeline is available via its dedicated `/api/coding/chat/completions` endpoint.

---

## 📦 Getting Started

### Prerequisites
- Docker & Docker Compose
- An NVIDIA NIM API key ([build.nvidia.com](https://build.nvidia.com/))

### Installation & Deployment

1. **Clone the repository:**
   ```bash
   git clone https://github.com/Alak-Das/NeuralGateway.git
   cd NeuralGateway
   ```

2. **Configure your API key:**
   Create a `.env` file or export `NVIDIA_API_KEY`:
   ```env
   NVIDIA_API_KEY=nvapi-your-key-here
   ```
   You can also configure multiple keys (`NVIDIA_API_KEY_1`, `NVIDIA_API_KEY_2`, `NVIDIA_API_KEY_3`) for automatic rotation and rate-limit distribution.

3. **Launch the Gateway:**
   ```bash
   docker compose up --build -d
   ```
   This starts three services:
   - **Neural Gateway** on `http://localhost:9090`
   - **Redis** on `localhost:6379`
   - **Open WebUI** on `http://localhost:3000`

4. **Access the Dashboards:**
   - **Neural Gateway Dashboard**: Open `http://localhost:9090` for the React-based fleet monitoring and telemetry dashboard.
   - **Open WebUI Chat Interface**: Open `http://localhost:3000` for a full-featured chat interface connected to Neural Gateway.
   - **Swagger API Docs**: Open `http://localhost:9090/swagger-ui.html` for interactive API exploration.

---

## 💡 Usage Examples

### 1. Coding Proxy (cURL)
```bash
curl -X POST http://localhost:9090/api/coding/chat/completions \
  -H "Content-Type: application/json" \
  -H "X-Requester: my-ide-agent" \
  -d '{
    "messages": [
      {"role": "user", "content": "Write a Python script to reverse a string."}
    ],
    "max_tokens": 100
  }'
```

### 2. Reasoning Proxy (cURL)
```bash
curl -X POST http://localhost:9090/api/reasoning/chat/completions \
  -H "Content-Type: application/json" \
  -H "X-Requester: research-assistant" \
  -d '{
    "messages": [
      {"role": "user", "content": "Solve: What is the sum of all primes under 20?"}
    ],
    "max_tokens": 200
  }'
```

### 3. Vision Proxy (Multimodal Base64 Image)
```bash
curl -X POST http://localhost:9090/api/vision/chat/completions \
  -H "Content-Type: application/json" \
  -H "X-Requester: vision-agent" \
  -d '{
    "messages": [
      {
        "role": "user",
        "content": [
          {"type": "text", "text": "What is in this image?"},
          {
            "type": "image_url",
            "image_url": {
              "url": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
            }
          }
        ]
      }
    ],
    "max_tokens": 100
  }'
```

### 4. OpenAI-Compatible Usage (via Open WebUI or any OpenAI client)
Configure your OpenAI client with:
- **Base URL**: `http://localhost:9090/v1`
- **API Key**: `neural-gateway-key` (any non-empty string)

This works with Open WebUI, Continue.dev, Cline, Cursor, and any other OpenAI-compatible client.

---

## 📚 Documentation
- **[Product Requirements Document (PRD)](PRD.md)** — Product vision, problem statement, and requirements.
- **[Design Document](DESIGN.md)** — Architecture overview, system layers, and technical design.

---

## 📄 License
This project is licensed under the MIT License.

*Created and maintained by Alak Das.*