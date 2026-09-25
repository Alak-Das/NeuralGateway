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
- **Autonomous Circuit Breaker**: Models returning consecutive server errors (5xx, timeouts) trip an isolated circuit breaker in Redis.
- **Safe 4xx Handling**: Client payload mistakes (400 Bad Request, 422 Unprocessable Entity) are immediately returned to the client and never falsely trip model circuit breakers.
- **Auto-Recovery**: Tripped circuit breakers automatically reset to closed as soon as background health checks succeed.
- **Fail-Fast Failover**: Transparently retries candidate models on server-side failures with strict attempt caps to eliminate cascading delays.

### 4. Resilient Health Checker
- **Paced Concurrency**: A dedicated 3-worker fixed thread pool paces health check pings, completely eliminating burst limit exhaustion (`503 ResourceExhausted 16/16`).
- **1-Token Health Pings (`max_tokens: 1`)**: Pings request exactly 1 token to prevent reasoning models from generating heavy reasoning chains during health checks.
- **Adaptive Timeout (180s)**: Eliminates false-negative "DOWN" statuses caused by upstream cloud queue delays.

### 5. Universal Tool Call Normalizer
- Seamlessly bridges differences between IDE agent schemas (Cline, Cursor, JetBrains) and NVIDIA NIM model outputs.
- Translates camelCase properties (`newText`, `filePath`) to strict snake_case parameters (`path`, `new_text`, `old_text`, `insert_line`).
- Resolves relative file paths to absolute workspace paths automatically.

### 6. Real-Time Observability Dashboard
- **Live Fleet Health**: Real-time status cards, active concurrent connections, global TPS, and average fleet latency.
- **Server-Sent Events (SSE)**: Instant browser metric updates with zero polling overhead.
- **Interactive Latency History**: Filterable from 15 minutes to 24 hours (default: 1 hour), retaining historical performance trends.
- **Token Analytics**: Breakdown of prompt and completion token usage by model and by client requester (`X-Requester`).

---

## 🛠️ Tech Stack

- **Framework**: Spring Boot 3.3.4 (Java 17)
- **Reactive Engine**: Spring WebFlux (`WebClient`) with 16 MB in-memory buffer
- **Data & Telemetry**: Redis 7 Alpine (persistent volume)
- **Frontend**: Vanilla JS, Bootstrap 5, Chart.js, Bootstrap Icons
- **Packaging & Orchestration**: Multi-stage Docker build, Docker Compose

---

## 🔌 API Endpoints

Neural Gateway is a drop-in replacement for OpenAI API endpoints:

| Pipeline | Direct Endpoint | OpenAI `/v1` Compatible Endpoint |
|---|---|---|
| **Coding** | `POST /api/coding/chat/completions` | `POST /api/coding/v1/chat/completions` |
| **Reasoning** | `POST /api/reasoning/chat/completions` | `POST /api/reasoning/v1/chat/completions` |
| **Vision** | `POST /api/vision/chat/completions` | `POST /api/vision/v1/chat/completions` |
| **Fleet Status** | `GET /api/models/status` | — |
| **Status Stream** | `GET /api/models/status/stream` (SSE) | — |
| **Manual Ping** | `POST /api/models/ping?model={name}` | — |
| **Reset Circuit** | `POST /api/models/circuit-reset?model={name}` | — |

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

3. **Launch the Gateway:**
   ```bash
   docker compose up --build -d
   ```

4. **Access the Dashboard:**
   Open your browser and navigate to:
   ```
   http://localhost:9090
   ```

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

---

## 📄 License
This project is licensed under the MIT License.

*Created and maintained by Alak Das.*
