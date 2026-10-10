# Neural Gateway Product Requirements Document (PRD)

## Executive Summary
Neural Gateway is an enterprise-grade, high-performance LLM routing gateway built with Spring Boot, Spring WebFlux, and Redis. It provides intelligent load balancing, dynamic failover, context-aware payload routing, tool call normalization, and real-time observability across multiple AI providers — including NVIDIA NIM, Experiential Labs, Antseed, and **Anthropic-compatible API**.

## Problem Statement
Organizations face challenges when integrating multiple LLM providers:
- Manual load balancing leads to uneven distribution and underutilization
- Lack of intelligent routing causes context window overflows and failed requests
- Single points of failure result in service downtime
- Inconsistent API interfaces complicate client integration
- Limited observability hinders performance optimization
- Manual health checks create operational overhead

## Solution Overview
Neural Gateway solves these challenges by providing:
- Intelligent pipeline-based routing (Coding, Reasoning, Vision)
- Dynamic load balancing with priority-weighted latency scoring and connection penalties
- Redis-based provider cooldowns for fault tolerance and graceful degradation
- Context-aware model selection based on payload size
- Real-time health monitoring with distributed scheduling
- **OpenAI-compatible API endpoints** (`/v1/chat/completions`) **and Anthropic-compatible API endpoints** (`/v1/messages`) **for seamless integration**
- Comprehensive telemetry and observability features

## Target Users
1. **AI Application Developers** - Building applications that leverage multiple LLMs
2. **MLOps Engineers** - Managing LLM infrastructure and deployments
3. **DevOps Teams** - Ensuring high availability and performance of AI services
4. **Enterprise Architects** - Designing scalable AI platforms

## Key Features

### 1. Intelligent Pipeline Routing
Neural Gateway organizes models into dedicated, purpose-tuned pipelines accessible through the standard `/v1/chat/completions` endpoint **and the Anthropic-compatible `/v1/messages` endpoint**:
- **Reasoning Pipeline** (`model: "reasoning"`): Routes complex multi-step reasoning tasks across frontier reasoning models
- **Coding Pipeline** (`model: "coding"`): Prioritizes low-latency, code-specialized models
- **Vision Pipeline** (`model: "vision"`): Routes multimodal text + image queries to vision-instruct models
- **Auto Pipeline** (`model: "auto"`): Automatically resolves target pipeline using request structure, multimodal content, and caller headers

### 2. Low-Latency Load Balancing & Telemetry
- **In-Memory O(1) Routing Score**: Combines a latency calculation with an active-connection penalty and model priority (`score = (latency + activeConnections * 300ms) / priority`). Lower score = better; lower priority number = higher priority.
- **Zero-Latency Request Path**: Health ping results update latency in-memory, avoiding synchronous database queries during request routing
- **Context-Aware Window Validation**: Automatically filters out models whose context windows cannot accommodate the estimated payload tokens. Disabled by default; enable via `llm.routing.context-window-validation-enabled=true`

### 3. High Availability & Fault Tolerance
- **Model-Level Fault Isolation & Anti-Flapping**: Models operate independently; there is NO provider-level cooldown. One model failing never affects other models from the same provider. Real-request errors apply a 3-consecutive-error anti-flapping threshold in Redis.
- **Independent Priority Failover**: When any model is DOWN or fails during execution, the gateway immediately fails over to the next highest-priority candidate in the pipeline.
- **Dynamic API Key Pool Rotation**: Upstream 429 rate limits or authentication errors rotate across the provider's API key pool (`ApiKeyPool`) before exposing an error.
- **Emergency Degraded Mode**: If all models in a pipeline are marked down, the gateway attempts the highest-priority enabled models rather than blacking out completely.
- **Safe 4xx Handling**: Client payload mistakes (400 Bad Request, 422 Unprocessable Entity) and format rejections are handled gracefully without false health penalties.
- **Zero Cold-Start Lag (Redis Bootstrapping)**: Restores previous health states, latencies, circuit status, and token usage from Redis on startup

### 4. Resilient Distributed Health Checker
- **ShedLock Distributed Scheduling**: Prevents redundant health check sweeps across horizontally scaled gateway instances by utilizing a Redis-backed distributed lock
- **3-Minute Sweep Frequency**: Automated health check sweeps run every 3 minutes, refreshing model statuses without placing continuous load on upstream providers
- **Prioritized Ping Ordering**: Models are sorted by priority
- **Staggered Ping Timing**: Each model ping is staggered by 500ms to pace requests and avoid thundering herd problems
- **Parallel Execution**: Health checks execute concurrently using a thread pool to avoid blocking the scheduler thread
- **Independent Recovery Sweep**: A dedicated low-cost sweep (default every 5s, max 2 models/sweep) probes only models flagged unhealthy by routed failures, honouring per-model exponential backoff so recovery starts in seconds instead of waiting for the next full sweep
- **Flexible Ping Model-ID Verification**: Health probes verify the upstream-reported model ID; providers that echo qualified canonical IDs (e.g. Antseed's `openai/gpt-oss-120b`) can opt into tolerant matching via `allow-qualified-model-ids`, while a missing `model` field still fails the probe

### 5. Observability & Monitoring
- **Real-time SSE Status Stream**: `/api/models/status/stream` endpoint provides live model status updates via Server-Sent Events
- **Requester Telemetry**: Track usage per client/API key for billing and analytics
- **TPS Calculation**: Real-time transactions per second metrics for each model
- **Health Check History**: Persistent storage of health check results in Redis for trend analysis
- **Status Freshness**: Per-model `FRESH`/`STALE`/`NO PROBE` indicators (24-hour freshness window via `statusFresh`) so operators can distinguish a stale status from a live failure
- **Swagger UI/OpenAPI Documentation**: Auto-generated API documentation at `/swagger-ui.html`

### 6. Standard API Specification
Neural Gateway strictly implements the official OpenAI API specification for LLM consumption:
- Universal Completions: `POST /v1/chat/completions` (supports streaming, tools, vision, and aliases)
- Model Discovery: `GET /v1/models` and `GET /v1/models/{modelId}`
- Fleet Telemetry: `GET /api/models/status`, `GET /api/models/status/stream`
- Operations: `POST /api/models/ping`, `POST /api/models/circuit-reset`, `GET /api/requesters/status`

## Non-Functional Requirements

### Performance
- Sub-100ms routing decision latency
- Support for 1000+ concurrent requests
- Horizontal scalability via Redis-backed state sharing
- Efficient memory usage with bounded telemetry storage

### Reliability
- 99.9% uptime SLA target
- Graceful degradation during partial system failures
- Automatic recovery from transient faults
- Data persistence for state recovery after restarts

### Security
- API key management through environment variables
- Rate limiting per provider to prevent abuse
- Input sanitization to prevent injection attacks
- CORS configuration for web client support
- JWT token validation ready for enterprise integration

### Operability
- Docker-based deployment for consistent environments
- Comprehensive logging with configurable levels
- Health check endpoints for Kubernetes liveness/readiness probes
- Configuration via application.yml and environment variables
- Zero-downtime rolling updates supported

## User Stories

### As a Developer, I want to:
1. Send chat completion requests to Neural Gateway using OpenAI-compatible APIs
2. Specify which pipeline (coding, reasoning, vision) to use for my requests
3. Get automatic failover when my preferred model is unavailable
4. Receive streaming responses for better UX in chat applications
5. Track my API usage for cost management

### As an MLOps Engineer, I want to:
1. Monitor real-time health and performance of all registered models
2. Receive alerts when models become unhealthy or trip circuit breakers
3. Scale the gateway horizontally without losing state consistency
4. Perform manual health checks and circuit resets for troubleshooting
5. View historical performance trends for capacity planning

### As a DevOps Engineer, I want to:
1. Deploy Neural Gateway using Docker Compose for easy setup
2. Configure the system via environment variables and YAML files
3. Monitor system health with standard Kubernetes probes
4. Update model configurations without redeploying the application
5. Scale resources based on observed load patterns

## Acceptance Criteria

### Functional Requirements
1. ✅ Requests to `/api/*/chat/completions` return valid OpenAI-compatible responses
2. ✅ **Requests to `/v1/messages` return valid Anthropic Messages API-compatible responses**
3. ✅ Provider cooldowns activate after consecutive failures and reset after successful health checks
3. ✅ Health checks run periodically and update model status in real-time
4. ✅ Context window validation prevents requests that exceed model limits
5. ✅ SSE endpoint provides real-time status updates to connected clients
6. ✅ Requester telemetry tracks usage per client identifier
7. ✅ Emergency degraded mode activates when all models in a pipeline are unavailable
8. ✅ API key rotation works correctly when rate limits are hit
9. ✅ Models flagged unhealthy by transient routed failures are re-probed by the recovery sweep with exponential backoff and restored on first successful probe
10. ✅ Provider-wide failures (upstream auth/quota/rate-limit errors) trigger failover without marking individual models DOWN
11. ✅ Model status exposes a `statusFresh` indicator (24-hour freshness window) for the dashboard

### Performance Requirements
1. ✅ 95th percentile routing latency < 50ms under load
2. ✅ System handles 100 concurrent requests without errors
3. ✅ Memory usage remains stable under continuous operation
4. ✅ Health check overhead < 5% of total system capacity

### Reliability Requirements
1. ✅ System recovers from transient network failures within 30 seconds
2. ✅ No single point of failure in the health checking system
3. ✅ State persists across application restarts via Redis
4. ✅ Provider cooldown state is consistent across gateway instances

## Future Enhancements
1. **Advanced Routing Algorithms**: Machine learning-based model selection
2. **Multi-tenant Support**: Isolation and quota management for different teams/organizations
3. **Custom Model Registration**: API for dynamically adding/removing models at runtime
4. **A/B Testing Framework**: Traffic splitting for model comparison experiments
5. **Extended Provider Support**: Add support for AWS Bedrock, Google Vertex AI, Azure OpenAI
6. **Prompt Caching**: Semantic caching to reduce redundant LLM calls
7. **Cost Optimization**: Automatic routing to cheapest viable model based on usage patterns
8. **WebSocket Support**: Full-duplex communication for real-time applications
9. **GraphQL Adapter**: GraphQL interface alongside REST APIs
10. **Plugin Architecture**: Extensible system for custom routing logic and telemetry
11. **Anthropic Vision Pipeline Support**: Extend `/v1/messages` endpoint to support `model: "vision"` for multimodal image+text requests

## Dependencies
- Java 21+
- Spring Boot 3.3.4
- Redis 7+
- Docker & Docker Compose
- NVIDIA NIM API Access
- Antseed API Access _(optional — enables the `deepseek-v4-flash` / `openai/gpt-oss-120b` / `Qwen/Qwen3-235B-A22B-Instruct-2507` fleet)_
- Maven 3.9+

## Risks & Mitigations
1. **Risk**: Redis becomes a bottleneck or single point of failure
   **Mitigation**: Use Redis clustering, implement local caching with TTL fallback

2. **Risk**: Health checking overwhelms upstream APIs
   **Mitigation**: Staggered ping timing, configurable frequency, exponential backoff

3. **Risk**: Cooldown thrashing during partial outages
   **Mitigation**: Configurable cooldown durations, health check-based recovery verification

4. **Risk**: Memory leaks in telemetry storage
   **Mitigation**: Bounded circular buffers, periodic cleanup of old metrics

5. **Risk**: Incompatible model API versions
   **Mitigation**: Version-specific adapters, graceful degradation to basic chat completion

## Success Metrics
1. **Availability**: 99.9% uptime measured over 30-day periods
2. **Performance**: 95th percentile request latency < 200ms (including upstream latency)
3. **Usage**: Number of active developers/applications using the gateway
4. **Reliability**: Mean time to recovery (MTTR) < 5 minutes for incidents
5. **Adoption**: Percentage of LLM traffic routed through the gateway vs direct calls
6. **Customer Satisfaction**: Net promoter score from internal users and stakeholders

---
*Document Version: 1.2*
*Last Updated: October 9, 2026*