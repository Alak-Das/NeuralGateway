# Neural Gateway

**Neural Gateway by Alak** is an Enterprise-grade, high-performance LLM routing proxy built with Spring Boot and Redis. It dynamically routes reasoning and coding requests across multiple Nvidia-hosted AI models, providing zero-downtime failover, real-time telemetry, and a sleek monitoring dashboard.

## 🚀 Key Features

### Intelligent Routing v2.0
- **Context-Aware Payload Filtering:** Automatically estimates token payload sizes and strips incompatible models from the routing queue (e.g., preventing a 100K token payload from routing to an 8K model).
- **Transparent Failover:** If a routed model fails (e.g., 429 Rate Limit, 502 Bad Gateway), the proxy instantly and transparently retries the next best model. Upstream agents never see the failure.
- **24-Hour Reliability Scoring:** Blends real-time Latency (TTFT) with a 24-hour Uptime Percentage pulled from Redis to calculate an optimal routing score.
- **Exponential Load Balancing:** Applies an exponential penalty to models under heavy concurrent load, forcing aggressive traffic spillover to secondary models.
- **Circuit Breaker:** Automatically trips and isolates models that return consecutive errors, resetting automatically when the background health-check succeeds.

### Enterprise Dashboard
- **Real-Time Telemetry:** A responsive UI powered by Bootstrap 5 and Chart.js, fed by Server-Sent Events (SSE) for zero-latency metric updates.
- **Persistent Analytics:** Token usage by requester, historical TPS, and 24-hour latency graphs are persistently backed by a local Redis database.
- **Dynamic Charting & Sorting:** Instantly slice latency history (15 mins to 24 hours), sort model statuses dynamically by any column, and match visual indicators perfectly across the dashboard.

### Drop-in Compatibility
- Exposes `/v1/chat/completions` endpoints seamlessly, allowing drop-in replacement for OpenAI SDKs, LangChain, and autonomous agents.

---

## 🛠️ Architecture

- **Backend:** Spring Boot, Spring WebFlux (`WebClient`) for asynchronous HTTP proxying.
- **Database:** Redis (`redis:7-alpine`) for persistent telemetry, usage tracking, and multi-node state synchronization.
- **Frontend:** Vanilla JS, Chart.js, Bootstrap 5.
- **Deployment:** Containerized via Docker Compose.

---

## 📦 Getting Started

### Prerequisites
- Docker & Docker Compose
- Nvidia API Key

### Installation

1. **Clone the repository:**
   ```bash
   git clone https://github.com/Alak-Das/NeuralGateway.git
   cd NeuralGateway
   ```

2. **Configure your environment:**
   Open `docker-compose.yml` (or your `.env` file) and securely set your `NVIDIA_API_KEY`.

3. **Launch the gateway stack:**
   ```bash
   docker-compose up --build -d
   ```
   *This will spin up both the `NeuralGateway` backend and the `NeuralGateway-Redis` database container.*

4. **Access the dashboard:**
   Navigate to [http://localhost:9090](http://localhost:9090) in your browser.

---

## 🔌 API Usage

Neural Gateway acts as an OpenAI-compatible endpoint. Point your agent or SDK to:

- **Coding Models:** `http://localhost:9090/api/coding/v1/chat/completions`
- **Reasoning Models:** `http://localhost:9090/api/reasoning/v1/chat/completions`

### Example Request (cURL)
```bash
curl -X POST http://localhost:9090/api/coding/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ANY_STRING_OR_AGENT_NAME" \
  -d '{
    "messages": [{"role": "user", "content": "Write a python script to reverse a string."}],
    "temperature": 0.2
  }'
```
*Note: The `Authorization` header is used to track token usage by requester on the dashboard!*

---
*Created and maintained by Alak Das.*
