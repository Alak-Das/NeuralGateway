# Neural Gateway

**Neural Gateway by Alak** is a high-performance, intelligent LLM routing proxy built with Spring Boot. It dynamically routes reasoning and coding requests across multiple Nvidia-hosted AI models, providing load balancing, real-time telemetry, and a sleek Enterprise SaaS dashboard.

## Key Features

- **Intelligent Routing:** Automatically routes traffic to the healthiest, least-loaded models (e.g., `nemotron-3-ultra-550b`, `kimi-k3`, `glm-5.3`).
- **Real-Time Dashboard:** A responsive, dark-mode compatible UI powered by Bootstrap 5 and Chart.js, fed by Server-Sent Events (SSE) for zero-latency metric updates.
- **Circuit Breaker:** Automatically trips and isolates models that return consecutive errors, ensuring stable upstream client connections.
- **OpenAI Compatible:** Exposes `/v1/chat/completions` endpoints seamlessly, allowing drop-in replacement for OpenAI SDKs and agents (like Cline).
- **Concurrency & Scaling:** Uses Spring WebFlux `WebClient` for fully non-blocking asynchronous proxying with strict 180-second timeout enforcement.

## Getting Started

### Prerequisites
- Docker & Docker Compose
- Nvidia API Key

### Installation

1. Clone the repository:
   ```bash
   git clone https://github.com/Alak-Das/NeuralGateway.git
   cd NeuralGateway
   ```
2. Configure your environment:
   Update the `.env` file with your `NVIDIA_API_KEY`.
3. Launch the gateway:
   ```bash
   docker-compose up --build -d
   ```
4. Access the dashboard:
   Navigate to `http://localhost:9090` in your browser.

## API Usage

The gateway acts as an OpenAI-compatible endpoint. Simply point your agent or SDK to:
`http://localhost:9090/api/coding/v1/chat/completions`

## Telemetry
All traffic metrics (TPS, Latency, Token Usage per Requester) are stored securely in-memory and visualized instantly on the Neural Gateway dashboard.
