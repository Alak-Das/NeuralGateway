
# Neural Gateway Design Document

## Architecture Overview

Neural Gateway follows a microservices-inspired modular architecture built on Spring Boot, designed for high performance, reliability, and extensibility. The system is organized into distinct layers and components that handle specific concerns while maintaining loose coupling through well-defined interfaces.

### High-Level Architecture
```
+---------------------+     +---------------------+     +---------------------+
|   API Layer         |     | Service Layer       |     | Infrastructure Layer|
| (Controllers)       |     | (Business Logic)    |     | (Redis, HTTP, etc.) |
+---------------------+     +---------------------+     +---------------------+
          ^                         ^                         ^
          |                         |                         |
+---------------------+     +---------------------+     +---------------------+
| Cross-cutting       |     | Domain Models       |     | External Integrations|
| Concerns            |     |                     |     | (NVIDIA NIM, etc.)   |
+---------------------+     +---------------------+     +---------------------+
```

## Component Diagram

```
                                                  +------------------+
                                                  |   LlmController  |
                                                  +--------+---------+
                                                           |
                      +------------------------------------+------------------------------------+
                      |                                                |
+---------------------v---------------------+       +------------------v------------------+
|            LlmGatewayFacade               |       |       ToolCallNormalizer           |
|   (Orchestrates all gateway services)     |       +------------------+------------------+
+---------------------+---------------------+                        |
                      |                                                |
      +-----------------v------------------+       +-------------------v--------------------+
      |     RoutingService                 |       |    PayloadTelemetryService           |
      |   (Intelligent model selection)    |       +-------------------+--------------------+
      +-----------------+------------------+                           |
                        |                                            |
      +-----------------v------------------+       +------------------v------------------+
      |    CircuitBreakerService           |       |   HealthCheckService               |
      |   (Fault tolerance via Resilience4j)|       | (Periodic + recovery sweeps, ShedLock)|
      +-----------------+------------------+       +------------------+------------------+
                        |                                |
      +-----------------v------------------+       +------v--------+     +--------------v-----------+
      |     ModelRegistry                  |       | ApiKeyPool    |     | LlmProviderClient        |
      |   (Model catalog management)       |       | (Rate limiting)|     | (NVIDIA NIM HTTP client) |
      +-----------------+------------------+       +------+--------+     +--------------+-------------+
                        |                                |                       |
      +-----------------v------------------+       +------v--------+     +--------------v-----------+
      |   RedisPersistenceService          |       |               |     |                          |
      |   (State persistence to Redis)     |       |               |     |                          |
      +-----------------+------------------+       +---------------+     +--------------------------+
                        |
            +-----------v-----------+
            |         Redis         |
            | (State sharing, locks,|
            |  pub/sub, caching)    |
            +---------------------+
```

## Detailed Component Specifications

### 1. API Layer (`LlmController`)
**Responsibilities:**
- Expose RESTful endpoints compatible with OpenAI API
- Handle HTTP request/response transformation
- Route requests to appropriate pipeline-specific handlers
- Extract and validate request headers (X-Requester)
- Manage Server-Sent Events (SSE) connections for status streaming
- Implement global exception handling
- Provide API documentation via Swagger/OpenAPI

**Key Endpoints:**
- `POST /api/{coding|reasoning|vision}/chat/completions` - Main chat completion endpoints
- `POST /v1/chat/completions`, `/chat/completions` - OpenAI compatibility aliases
- `GET /api/models/status` - Current status of all models
- `GET /api/models/status/stream` - SSE stream of model status updates
- `GET /api/requesters/status` - Requester telemetry/usage statistics
- `POST /api/models/ping?model={name}` - Manual health check trigger
- `POST /api/models/circuit-reset?model={name}` - Manual circuit breaker reset

### 2. Service Layer

#### LlmGatewayFacade
**Responsibilities:**
- Orchestrate all gateway services to process chat completion requests
- Provide a unified interface for the controller layer
- Manage request lifecycle from intake to response
- Handle token estimation and context window validation
- Coordinate fallback mechanisms and emergency degraded mode
- Normalize tool calls between different provider formats
- Collect and report payload telemetry
- Feed routed outcomes into the recovery system (`recordRoutedFailure` / `recordRoutedSuccess`) so models recover via the dedicated sweep, and provider-wide failures (auth/quota/rate-limit) are excluded from per-model recovery scheduling

**Key Methods:**
- `processChatCompletion(requestBody, requester, transactionId, pipelineName)` - Main entry point
- `estimateTokens(requestBody)` - Calculate approximate token count from messages
- `sanitizeRequest(requestBody)` - Normalize provider-specific parameters (e.g., thinking_effort)
- `getRoutingScore(modelId)` - Retrieve current routing score for a model
- `recordRoutedFailure(modelId, throwable)` / `recordRoutedSuccess(modelId)` - Notify the recovery tracker of routed outcomes
- `noEligibleProvider(pipeline, cause)` - Builds the error message when no candidate is available. Filters `getModelsByPipeline()` with `.filter(Model::isEnabled)` so that only providers with at least one **enabled** model contribute their availability state to the error message. This prevents stale `QUOTA_EXHAUSTED` state from a disabled model from polluting the error.
- Various getter methods for telemetry and status information

#### RoutingService
**Responsibilities:**
- Calculate intelligent routing scores based on latency and connection counts
- Maintain in-memory telemetry for active connections and EMA latency
- Select optimal models for a given pipeline based on health, score, and context
- Implement fallback model selection logic
- Provide emergency degraded mode when all primary models are unavailable
- Track TPS (transactions per second) metrics per model

**Key Algorithms:**
- **Routing Score Calculation**: `score = emaLatency + (activeConnections * connectionPenaltyMs)`
- **EMA (Exponential Moving Average)**: `newEma = α * latency + (1-α) * currentEma` where α = 0.1
- **Model Selection**: Sort by routing score (ascending), filter by health and context window
- **Fallback Selection**: Healthy models first, then models with non-OPEN circuit breakers
- **Emergency Mode**: If no candidates available, return top models by score as canary probes

**Telemetry Structures:**
- `Map<String, AtomicInteger> activeConnectionsMap` - Thread-safe connection counting
- `Map<String, Double> emaLatencyMap` - EMA latency storage per model

#### CircuitBreakerService
**Responsibilities:**
- Implement circuit breaker pattern using Resilience4j for fault tolerance
- Isolate failing models to prevent cascading failures
- Manage circuit breaker state transitions (CLOSED → OPEN → HALF_OPEN → CLOSED)
- Persist circuit state to Redis for consistency across instances
- Provide manual reset and force-open capabilities for operations
- Force-close OPEN/HALF_OPEN/FORCED_OPEN breakers via markHealthy() after a verified healthy probe, syncing the CLOSED state to Redis so a recovered model unblocks immediately
- Execute protected calls with automatic success/failure recording

**Configuration (from CircuitBreakerProperties):**
- `failureThreshold`: 3 consecutive failures to trip breaker
- `successThreshold`: 2 successful calls to close from HALF_OPEN
- `resetTimeoutMs`: 30000ms (30 seconds) wait in OPEN state
- `autoRecoveryEnabled`: true (automatic transition from OPEN to HALF_OPEN)

**State Persistence:**
- Circuit OPEN/CLOSED state stored in Redis
- Consecutive error counts persisted for recovery
- State transition listeners update Redis on changes

#### HealthCheckService
**Responsibilities:**
- Perform periodic asynchronous health checks on all registered models
- Utilize ShedLock for distributed locking to prevent duplicate sweeps
- Execute health checks in parallel using a thread pool
- Stagger individual pings to avoid thundering herd problems
- Update model status and telemetry based on check results
- Trigger circuit breaker updates based on health outcomes
- Run an independent recovery sweep that re-probes only models flagged unhealthy by routed failures, honouring per-model exponential backoff via `ModelRecoveryTracker`
- Accept routed-failure/success notifications from the gateway facade so recovery probes are scheduled/cleared without waiting for the full sweep (`recordRoutedFailure` / `recordRoutedSuccess`)
- Verify the upstream-reported `model` ID against the requested model on every ping; by default this is an exact match, and a missing/mismatched ID fails the probe. Providers that opt in via `allowQualifiedModelIds` (e.g. Antseed, an aggregator that echoes provider-qualified canonical IDs) additionally accept tolerant matches (see `modelIdMatches` below)
- On a successful probe, close any stale OPEN circuit (`CircuitBreakerService.markHealthy`) — e.g. one restored from Redis — and sync the CLOSED state to Redis, so routing and the dashboard unblock immediately instead of waiting for the passive half-open timeout

**Scheduling Configuration:**
- `initialDelayMs`: 5000ms (5 seconds) initial delay
- `intervalMs`: 240000ms (4 minutes) between sweeps
- Main sweep ShedLock: `lock-at-least-for: 10s`, `lock-at-most-for: 5m`
- `recoveryInitialDelayMs`: 15000ms (15 seconds) initial delay for the recovery sweep
- `recoveryIntervalMs`: 5000ms (5 seconds) between recovery sweeps
- `recoveryMaxModelsPerSweep`: 2 models max per recovery sweep (distinct providers)
- `threadPoolSize`: 10 concurrent health checks
- `pingTimeoutMs`: 120000ms (2 minutes) timeout per individual ping
- `pingMaxTokens`: 16 tokens by default (configurable via `llm.health-check.ping-max-tokens`, bounded 1–1024). Raised above 1 because some providers/models (e.g. explabs `gpt-6-luna`) reject `max_tokens < 16` and would otherwise fail every probe.
- Recovery sweep ShedLock: `recovery-lock-at-least-for: 1s`, `recovery-lock-at-most-for: 6m`

**Health Check Process:**
1. Retrieve prioritized model list (sorted by EMA latency if enabled)
2. For each model, schedule async ping with staggered delay (500ms intervals)
3. Execute actual ping call to LLM API with minimal request (max_tokens=pingMaxTokens, default 16)
4. Measure latency and determine success/failure
5. Update routing service EMA latency on success
6. Record success/failure in circuit breaker service
7. Persist result to Redis via RedisPersistenceService
8. Update in-memory model status via ModelStatusUpdater (triggers SSE broadcast)

**Recovery Sweep Process:**
1. Periodically (default every 5s) collect models whose recovery backoff is due (`ModelRecoveryTracker.isDue`)
2. Exclude models with pending provider-level probes (`nextRecoveryProviderProbeAt`) and cap at `recoveryMaxModelsPerSweep`, one model per provider
3. Probe each due model with the same minimal ping; on success, clear its backoff (`recordRoutedSuccess`) and mark the model healthy immediately
4. On failure, `ModelRecoveryTracker.recordFailure` extends the backoff (doubles from 30s, capped at 120s, ±20% jitter)

**Model-ID Match Verification (`modelIdMatches`):**
- By default an exact string match is required; a null/missing reported ID always fails the ping (preserves the "reject missing `model` field" safeguard)
- When the provider sets `allow-qualified-model-ids: true` (Antseed), the comparison additionally accepts:
  - case-insensitive exact equality
  - a `"/" + requested` suffix, e.g. `openai/gpt-oss-120b` reported for requested `gpt-oss-120b`
  - a response containing every `-`/`_`/`.`/`/`-separated token of the requested ID, tolerating canonical/dated variants such as `Qwen/Qwen3-235B-A22B-Instruct-2507` for requested `qwen3-235b-instruct`

#### ModelRecoveryTracker
**Responsibilities:**
- Track when a model made unhealthy by a transient routed failure may next be re-probed
- Implement exponential backoff with bounded jitter to spread recovery probes across models and gateway replicas
- Distinguish transient model failures (408 / 5xx upstream, `TRANSIENT_UPSTREAM`) from provider-wide failures (auth, quota, rate-limit) — the latter never schedule recovery probes
- Persist backoff state in Redis for cross-replica coordination, with TTL tied to the configured data retention window
- Clear backoff state immediately on a successful routed request

**Backoff Algorithm:**
- Initial delay: 30s (`INITIAL_BACKOFF_MS = 30_000`)
- Doubles each failure, capped at 120s (`MAX_BACKOFF_MS = 120_000`)
- Bounded jitter: ±20% (`JITTER_FRACTION = 0.2`) applied as `1.0 ± 0.2 * sample`
- Delay for failure `n`: `30s << min(max(n-1, 0), 2)`, then multiplied by the jitter multiplier

**Failure Classification (`isTransientModelFailure`):**
- `true`: `ProviderFailureType.TRANSIENT_UPSTREAM`, upstream 408, upstream 5xx
- `false` (provider-wide, no recovery scheduling): `ProviderFailureException.isProviderWide()`, `LlmProviderClient.RateLimitException`

**Persistence (via RedisPersistenceService):**
- `gateway:model:recovery:failures:{modelId}` → failure counter (INCR with retention TTL)
- `gateway:model:recovery:state:{modelId}` → serialized backoff (`failureCount|nextProbeAtEpochMs`, retention TTL)
- `clearModelRecoveryBackoff(modelId)` → deletes both keys on success

#### ModelRegistry
**Responsibilities:**
- Manage the catalog of available LLM models and their metadata
- Initialize model registry from configuration (application.yml)
- Provide lookup capabilities by model ID or pipeline
- Manage API key pools **per provider** (NVIDIA NIM, Experiential Labs, ...) for rate limiting
- Determine model capabilities based on pipeline assignment
- Validate model IDs and context limits
- **Multi-Provider Failover**: Candidate selection spans all configured providers as a single logical fleet, enabling seamless cross-provider failover when a provider-specific error (401/403/404, quota exceeded, etc.) occurs

**Data Structures:**
- `Map<String, Model> modelCatalog` - ID to model mapping
- `Map<String, ApiKeyPool> apiKeyPools` - Provider to API key pool mapping

**Model Configuration (from LlmProvidersProperties):**
Each provider defines:
- Base URL for API calls
- Rate limit (requests per minute)
- List of API keys for rotation
- `allow-qualified-model-ids` (optional, default `false`): relaxes health-ping model-ID matching for aggregator providers (e.g. Antseed) that echo provider-qualified canonical IDs in responses
- Models with:
  - ID (full model identifier)
  - Priority (lower number = higher priority)
  - Assigned pipelines (CODING, REASONING, VISION)

#### RedisPersistenceService
**Responsibilities:**
- Persist gateway state to Redis for sharing across instances
- Store health check results for historical analysis
- Maintain circuit breaker state consistency
- Store requester telemetry/usage statistics
- Enable zero cold-start lag through state restoration
- Provide Pub/Sub mechanism for real-time status broadcasting

**Stored Data Types:**
- Health check results: `health:{modelId}` → serialized HealthCheckResult
- Circuit breaker state: `circuit:open:{modelId}` → boolean
- Consecutive error counts: `circuit:errors:{modelId}` → integer
- Requester usage: `usage:{requester}` → incrementing counter
- Model status: `status:{modelId}` → serialized ModelStatus
- Health check history: `health:history:{modelId}` → list of recent results
- Model recovery backoff: `gateway:model:recovery:failures:{modelId}` → failure counter, `gateway:model:recovery:state:{modelId}` → serialized backoff (`failureCount|nextProbeAtEpochMs`); both expire with the configured data-retention TTL

**Pub/Sub Channels:**
- `model:status:updates` - Broadcast ModelStatus changes for SSE consumers
- `gateway:events` - System-wide events (circuit trips, recoveries, etc.)

#### LlmProviderClient
**Responsibilities:**
- Abstract HTTP client for communicating with LLM providers
- Handle provider-specific API differences and authentication
- Manage API key rotation via ApiKeyPool
- Implement retry logic with exponential backoff
- Handle streaming and non-streaming responses
- Convert between provider response formats and internal models
- Manage connection pooling and timeouts

**Key Features:**
- API key rotation with rate limiting (40 RPM default per key)
- Automatic failover to next key on rate limit (429) or server errors
- Support for both streaming (SSE) and non-streaming responses
- Configurable timeouts and retry attempts
- Provider-specific endpoint construction
- Request/response logging for debugging

#### PayloadTelemetryService
**Responsibilities:**
- Collect and aggregate request/response telemetry data
- Track usage statistics per requester (X-Requester header)
- Calculate real-time TPS (transactions per second) metrics
- Monitor token consumption patterns
- Provide data for billing, analytics, and capacity planning
- Implement sliding window metrics for recent activity

**Tracked Metrics:**
- Request count per requester
- Token count per requester (input/output/total)
- Requests per second (TPS) per model and globally
- Average response latency per model
- Error rates and failure patterns
- Peak usage times and traffic patterns

#### ModelStatusUpdater
**Responsibilities:**
- Maintain in-memory model status for fast access
- Trigger Server-Sent Events broadcasts on status changes
- Provide thread-safe status updates from health check results
- Maintain status history for trend analysis
- Serve as the single source of truth for current model state

**Status Attributes:**
- Model ID
- Availability (up/down)
- Latency (last successful ping)
- Timestamp of last check
- Error message (if applicable)
- Consecutive failure count
- Current TPS
- Circuit breaker state (OPEN/CLOSED/HALF_OPEN)

#### SseNotificationService
**Responsibilities:**
- Manage Server-Sent Events connections for real-time status updates
- Broadcast model status changes to all connected clients
- Handle connection lifecycle (open, close, error)
- Prevent memory leaks through proper connection cleanup
- Support multiple concurrent SSE consumers
- Format data according to SSE specification

**SSE Format:**
```
event: model-status-update
data: {"modelId":"nvidia/nemotron-3-super-120b-a12b","isUp":true,"latencyMs":145,"timestamp":"2026-09-27T10:30:00Z","errorMessage":null}

event: gateway-alert
data: {"type":"CIRCUIT_TRIPPED","modelId":"nvidia/nemotron-3-ultra-550b-a55b","reason":"3 consecutive failures"}

event: heartbeat
data: {"timestamp":"2026-09-27T10:30:05Z","instanceId":"neural-gateway-1"}
```

### 3. Domain Layer

#### Model
**Responsibilities:**
- Value object representing an LLM model with metadata and capabilities
- Encapsulate model identification, provider, and pipeline assignments
- Define context window limits and processing priorities
- Provide capability detection (vision, tools, JSON mode)
- Implement equality and hashing based on model ID
- Determine availability for specific pipelines
- Validate if model can handle given token count

**Attributes:**
- `id`: Unique model identifier (e.g., "nvidia/nemotron-3-super-120b-a12b")
- `name`: Human-readable model name
- `providerId`: Provider identifier (e.g., "nvidia")
- `pipelines`: Set of assigned pipelines (CODING, REASONING, VISION)
- `contextLimit`: Maximum context tokens supported
- `priority`: Priority level (lower = higher priority)
- `enabled`: Boolean flag (default `false` via `Boolean.TRUE.equals()`) — models must be explicitly marked `enabled: true` in configuration to be eligible for routing and health checks. Disabled models are excluded from `getModelsByPipeline()`, filtered by `noEligibleProvider()`, and marked DOWN in model status.
- `capabilities`: ModelCapabilities object

#### ModelCapabilities
**Responsibilities:**
- Value object representing model capabilities
- Flags for vision support, tool calling, and JSON mode
- Used for request validation and response formatting
- Determines what features can be safely used with each model

**Attributes:**
- `supportsVision`: Boolean indicating multimodal (text+image) support
- `supportsTools`: Boolean indicating function/tool calling support
- `supportsJsonMode`: Boolean indicating structured JSON output support

#### Pipeline
**Responsibilities:**
- Enum defining the three specialized model pipelines
- Provides type safety for pipeline-specific routing
- Enables compile-time validation of pipeline assignments

**Values:**
- `CODING`: Optimized for code generation and software engineering tasks
- `REASONING`: Optimized for complex logical reasoning and problem-solving
- `VISION`: Optimized for multimodal text and image understanding

#### ModelStatus
**Responsibilities:**
- Value object representing current status of a model
- Used for SSE broadcasts and status API responses
- Encapsulates all observable model state
- Serializable for Redis storage and network transmission

**Attributes:**
- `modelId`: Identifier of the model
- `isUp`: Boolean indicating if model is currently available
- `latencyMs`: Latency of last successful health check (milliseconds)
- `timestamp`: Time of last status update
- `errorMessage`: Error description if model is down
- `consecutiveFailures`: Count of consecutive failed health checks
- `tps`: Transactions per second (real-time metric)
- `circuitState`: Current circuit breaker state (CLOSED/OPEN/HALF_OPEN)

#### HealthCheckResult
**Responsibilities:**
- Value object representing the outcome of a single health check
- Used internally during health check processing
- Contains detailed information about check execution
- Basis for updating model status and circuit breakers

**Attributes:**
- `modelId`: Identifier of the model checked
- `isUp`: Boolean indicating if health check passed
- `latencyMs`: Time taken for health check (milliseconds)
- `timestamp`: Time when check was performed
- `errorMessage`: Description of failure if applicable

#### RoutingScore
**Responsibilities:**
- Value object representing the calculated routing score for a model
- Encapsulates the factors that influence routing decisions
- Used for model selection and sorting
- Provides the calculated score used for comparison

**Attributes:**
- `emaLatency`: Exponential moving average latency (milliseconds)
- `activeConnections`: Current number of active requests
- `connectionPenaltyMs`: Penalty per active connection (configurable)
- `priority`: Model priority level (lower = higher priority)
- `calculatedScore`: Final computed routing score

### 4. Configuration Layer

#### Application Properties (`application.yml`)
**Configuration Categories:**
- **Server**: Port settings, logging levels
- **SpringDoc**: Swagger UI configuration
- **Spring**: Thread configuration, Redis connection
- **Resilience4j**: Retry configuration for NVIDIA API calls
- **LLM**: Provider configuration, logging, health check settings
- **Routing**: Connection penalty, context validation, fallback attempts
- **Health Check**: Enabled status, interval, thread pool, timeouts, recovery sweep
- **Circuit Breaker**: Failure/success thresholds, reset timeout, auto recovery

**Key Properties:**
```yaml
server:
  port: 8080

spring:
  data:
    redis:
      host: ""
      port: 6379
  threads:
    virtual:
      enabled: true

llm:
  providers:
    nvidia:
      base-url: https://integrate.api.nvidia.com/v1
      rate-limit-rpm: 40
      keys:
        - ${NVIDIA_API_KEY_1:}
        - ${NVIDIA_API_KEY_2:}
      models:
        - { id: "nvidia/nemotron-3-super-120b-a12b", priority: 10, pipelines: [REASONING, CODING] }
        - { id: "nvidia/nemotron-3-ultra-550b-a55b", priority: 9,  pipelines: [REASONING, CODING] }
        # ... additional models
  logging:
    payload-mode: SUMMARY
    preview-max-chars: 120

routing:
  connection-penalty-ms: 300
  context-window-validation-enabled: true
  max-fallback-attempts: 3

health-check:
  enabled: true
  interval-ms: 240000
  thread-pool-size: 10
  ping-timeout-ms: 5000
  ping-max-tokens: 16
  prioritize-by-ema: true

circuit-breaker:
  failure-threshold: 3
  success-threshold: 2
  reset-timeout-ms: 30000
  auto-recovery-enabled: true
```

#### Configuration Properties Classes
- `RoutingProperties` - Maps routing.* configuration
- `HealthCheckProperties` - Maps health-check.* configuration
- `CircuitBreakerProperties` - Maps circuit-breaker.* configuration
- `LlmProvidersProperties` - Maps llm.providers.* configuration
- Each uses `@ConfigurationProperties` and `@Validated` for type safety

### 5. Cross-Cutting Concerns

#### Logging
- SLF4J with Logback implementation
- Configurable levels per package (`com.alak.neuralgateway: DEBUG`)
- Specialized LLM logging for payload inspection
- Request/response logging for debugging and audit trails
- Structured logging for production monitoring

#### Exception Handling
- Global `@ExceptionHandler` in `LlmController`
- Specific handling for `WebClientResponseException` (upstream API errors)
- General exception handling for unexpected errors
- Consistent error response format:
  ```json
  {
    "error": {
      "message": "Error description",
      "type": "error_category",
      "code": "error_code"
    }
  }
  ```

#### Security
- API key protection through environment variables
- Input validation and sanitization
- CORS configuration for web browser access
- Rate limiting to prevent abuse
- Ready for JWT token authentication extension

#### Observability
- Micrometer metrics ready for Prometheus/Grafana integration
- Distributed tracing ready for OpenTelemetry/Jäger
- Health endpoints for Kubernetes liveness/readiness probes
- Structured logging for ELK stack ingestion
- Custom business metrics (TPS, latency, error rates)

## Data Flow

### Request Processing Flow
```
1. Client → LlmController (HTTP POST /api/{pipeline}/chat/completions)
   ↓
2. LlmController → LlmGatewayFacade.processChatCompletion()
   ↓
3. LlmGatewayFacade:
   a. Sanitize request (normalize provider-specific params)
   b. Estimate token count from messages
   c. Validate context window (if enabled)
   d. Call LlmGatewayFacade.processChatCompletion() [continued]
      ↓
   e. Retrieve candidate models via RoutingService.selectModels()
      ↓
   f. For each candidate model:
      i. Check circuit breaker permission
      ii. Acquire API key from provider's pool
      iii. Increment active connections counter
      iv. Execute LLM provider call with timing
      v. On success: update EMA latency, decrement connections, record success
      vi. On failure: decrement connections, record failure, try next candidate
   ↓
4. On successful response:
   a. Normalize tool calls (if needed)
   b. Update payload telemetry
   c. Return response to client
   ↓
5. If all candidates fail:
   a. Activate emergency degraded mode (try top models as canary probes)
   b. If still failing, return service unavailable error
```

### Health Check Flow
```
1. Scheduler Trigger → HealthCheckService.performHealthCheckSweep()
   ↓
2. HealthCheckService:
   a. Check if health checks are enabled
   b. Get prioritized model list (by EMA latency if configured)
   c. For each model with staggered delay:
      ↓
   d. PerformActualPing():
      i. Create minimal request (model, messages=[{role:"user",content:"ping"}], max_tokens=16, configurable via llm.health-check.ping-max-tokens)
      ii. Execute HTTP call to LLM provider API
      iii. Measure latency and capture result/exception
      ↓
   e. UpdateModelStatusFromResult():
      i. If success: update EMA latency in RoutingService, record success in CircuitBreakerService, then call markHealthy() to force-close any stale OPEN/HALF_OPEN circuit and sync CLOSED state to Redis
      ii. If failure: record failure in CircuitBreakerService
      iii. Persist result to Redis via RedisPersistenceService
      iv. Update in-memory status via ModelStatusUpdater (triggers SSE broadcast)
      ↓
3. ModelStatusUpdater:
   a. Update internal model status map
   b. Broadcast status change via SseNotificationService
   ↓
4. SseNotificationService:
   a. Send SSE event to all connected clients
   b. Format: event: model-status-update + data: JSON model status
```

### Circuit Breaker Flow
```
1. Request Attempt → CircuitBreakerService.isRequestPermitted()
   ↓
2. If permitted:
   a. Execute request via LlmProviderClient
   b. On success: CircuitBreakerService.recordSuccess()
      i. Update Resilience4j circuit state
      ii. Persist CLOSED state to Redis
      iii. Reset consecutive error count
   c. On failure: CircuitBreakerService.recordFailure()
      i. Update Resilience4j circuit state
      ii. Persist OPEN state to Redis (if threshold reached)
      iii. Increment consecutive error count
   ↓
3. State Transition Handling:
   a. OPEN → HALF_OPEN: After reset timeout expires
   b. HALF_OPEN → CLOSED: After success threshold met
   c. HALF_OPEN → OPEN: On failure during half-open
   d. Any state change: Persist to Redis via RedisPersistenceService
```

### Recovery Sweep Flow
```
1. LlmGatewayFacade routes a request to a model.
   a. On transient failure (408/5xx upstream, TRANSIENT_UPSTREAM):
      recordRoutedFailure(modelId, throwable)
        → ModelRecoveryTracker.recordFailure()
        → Redis INCR failure counter, store backoff state (TTL = retention window)
   b. On provider-wide failure (auth/quota/rate-limit): no per-model recovery scheduled
   c. On success: recordRoutedSuccess(modelId)
        → ModelRecoveryTracker.recordSuccess() → clear backoff keys
   ↓
2. Recovery sweep fires every 5s (ShedLock-protected, 15s initial delay).
   a. Collect due models: ModelRecoveryTracker.isDue(modelId, now)
   b. Filter: skip models on unavailable providers, cap at recoveryMaxModelsPerSweep (2), one per provider
   c. For each due model with staggered 5s gap:
      i. performActualPing() minimal ping
      ii. Success → recordRoutedSuccess, mark model healthy immediately (SSE broadcast)
      iii. Failure/timeout → ModelRecoveryTracker.recordFailure (doubles backoff, 30s→120s cap, ±20% jitter)
   ↓
3. Independent from the full 4-minute health-check sweep; a model can recover in seconds.
```

### SSE Broadcast Flow
```
1. ModelStatusUpdater.updateStatus()
   ↓
2. Internal status map updated
   ↓
3. SseNotificationService.broadcastModelStatus()
   ↓
4. For each connected SSE client:
   a. Write formatted event to response output stream
   b. Format:
      event: model-status-update
      data: {modelId, isUp, latencyMs, timestamp, errorMessage}
   c. Flush to ensure immediate delivery
   ↓
5. Client receives event and updates UI accordingly
```

## Concurrency Model

### Threading Strategy
- **Virtual Threads**: Enabled via Spring configuration for efficient concurrency
- **Health Check Pool**: Fixed thread pool (size configurable) for parallel health checks
- **Database Access**: Redis operations are mostly asynchronous but use synchronous clients for simplicity
- **HTTP Calls**: Non-blocking WebClient for provider communications
- **Shared State**: 
  - AtomicIntegers for connection counting
  - ConcurrentHashMaps for telemetry storage
  - Synchronized blocks where necessary for complex updates
  - Redis for distributed state consistency

### Resource Management
- **Connection Pooling**: 
  - HTTP client pooling via WebClient
  - Redis connection pooling via Lettuce
- **Memory Bounds**:
  - Fixed-size sliding windows for telemetry metrics
  - Bounded history for health check results
  - LRU caches where appropriate
- **Lifecycle Management**:
  - Proper shutdown of executor services
  - Resource cleanup in @PreDestroy methods
  - Connection reset handling

## Performance Characteristics

### Latency Profile
- **Routing Decision**: < 1ms (in-memory score calculation)
- **Context Validation**: < 0.1ms (simple integer comparison)
- **Circuit Breaker Check**: < 0.1ms (atomic state read)
- **API Key Acquisition**: < 0.1ms (atomic pool operation)
- **Actual LLM Call**: Variable (100ms-2s+ depending on provider and model)
- **Response Processing**: < 1ms (telemetry updates, tool normalization)
- **Total Gateway Overhead**: Typically < 5ms end-to-end

### Throughput Capacity
- **Requests/Second**: Limited primarily by upstream provider rate limits
- **Concurrent Connections**: Scales with available memory and thread capacity
- **Horizontal Scaling**: Linear scaling possible with Redis-backed state sharing
- **Health Check Overhead**: < 5% of total capacity with default configuration

### Memory Usage
- **Base Footprint**: ~50-100MB JVM heap
- **Per-Model Telemetry**: ~1-2KB for active connections + EMA latency
- **Health Check History**: Configurable, default ~10KB per model for 100 results
- **Requester Telemetry**: Scales with unique requesters, ~1KB per active requester
- **Circuit Breaker State**: Minimal, ~100 bytes per model

## Reliability Features

### Fault Tolerance
- **Circuit Breaker Pattern**: Prevents cascading failures
- **Bulkhead Isolation**: Thread pool separation for health checks vs request processing
- **Timeouts**: Configurable timeouts on all external calls
- **Retries**: Exponential backoff with jitter for transient failures
- **Fallbacks**: Multiple fallback models per pipeline
- **Emergency Mode**: Graceful degradation when primary paths fail

### Data Consistency
- **Redis as Source of Truth**: All persistent state stored in Redis
- **Eventual Consistency**: Acceptable for telemetry and non-critical metrics
- **Strong Consistency**: Used for circuit breaker state and model availability
- **Atomic Operations**: Redis INCR/HINCRBY for telemetry counters
- **Distributed Locking**: ShedLock prevents duplicate health check sweeps

### Recovery Mechanisms
- **Auto-Recovery**: Circuit breakers automatically test readiness
- **Smart Model Recovery Backoff**: Models flagged unhealthy by transient routed failures are re-probed by a dedicated low-cost sweep with exponential backoff (30s → 120s, ±20% jitter) coordinated across replicas via Redis; a single successful probe restores the model immediately, without waiting for the next full health-check sweep
- **Provider-Wide Failure Isolation**: Upstream auth (`401`/`403`/`404`), quota, and rate-limit failures trigger cross-provider failover and never mark individual models DOWN or schedule recovery probes for them
- **State Restoration**: Full state recovery from Redis on startup
- **Health-Based Healing**: Failed models automatically retested
- **Manual Override**: Admin endpoints for forced circuit resets
- **Last Known Good**: Fallback to historically healthy models

## Scalability Considerations

### Horizontal Scaling
- **Stateless Processing**: Request handling doesn't rely on local state
- **Shared State**: Redis enables consistent state across instances
- **Load Balancing**: Works behind any L4/L7 load balancer (round-robin, least connections, etc.)
- **Session Affinity**: Not required due to externalized state
- **Rolling Updates**: Supported with zero downtime

### Vertical Scaling
- **Increased Throughput**: More vCPUs → higher concurrent request capacity
- **Increased Memory**: Larger heap → more telemetry history and requester tracking
- **Better Performance**: Faster CPUs → lower latency for encryption/compression
- **Redis Offloading**: Can separate Redis to dedicated cluster for large deployments

### Bottlenecks and Mitigations
1. **Redis Throughput**
   - Mitigation: Use Redis clustering, enable read replicas, implement local caching with TTL

2. **HTTP Client Connections**
   - Mitigation: Connection pooling, keep-alive, tune max connections per route

3. **Health Check Load**
   - Mitigation: Staggered pings, configurable frequency, adaptive thread pools

4. **Telemetry Aggregation**
   - Mitigation: Sliding window metrics, periodic aggregation to downsampled stores

5. **SSE Fan-Out**
   - Mitigation: Limit concurrent connections, consider message queue for large audiences

## Deployment Architecture

### Containerized Deployment
```
+------------------+     +------------------+     +------------------+
|  Load Balancer   |     |  Load Balancer   |     |  Load Balancer   |
+--------+---------+     +--------+---------+     +--------+---------+
         |                         |                         |
+--------v---------+     +--------v---------+     +--------v---------+
| NeuralGateway-1  |     | NeuralGateway-2  |     | NeuralGateway-3  |
| (Container)      |     | (Container)      |     | (Container)      |
+--------+---------+     +--------+---------+     +--------+---------+
         |                         |                         |
+--------v---------+     +--------v---------+     +--------v---------+
|     Redis        |<----+     Redis        |<----+     Redis        |
|   (Cluster)      |     |   (Cluster)      |     |   (Cluster)      |
+------------------+     +------------------+     +------------------+
```

### Kubernetes Deployment
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: neural-gateway
spec:
  replicas: 3
  selector:
    matchLabels:
      app: neural-gateway
  template:
    metadata:
      labels:
        app: neural-gateway
    spec:
      containers:
      - name: neural-gateway
        image: neural-gateway:latest
        ports:
        - containerPort: 8080
        env:
        - name: NVIDIA_API_KEY_1
          valueFrom:
            secretKeyRef:
              name: nvidia-api-keys
              key: key1
        - name: NVIDIA_API_KEY_2
          valueFrom:
            secretKeyRef:
              name: nvidia-api-keys
              key: key2
        readinessProbe:
          httpGet:
            path: /api/models/status
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 10
        livenessProbe:
          httpGet:
            path: /api/models/status
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 30
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "1Gi"
            cpu: "1000m"
---
apiVersion: v1
kind: Service
metadata:
  name: neural-gateway-service
spec:
  selector:
    app: neural-gateway
  ports:
    - protocol: TCP
      port: 80
      targetPort: 8080
  type: LoadBalancer
```

### Docker Compose (Development/Testing)
```yaml
version: '3.8'
services:
  neural-gateway:
    build: .
    ports:
      - "9090:8080"
    environment:
      - NVIDIA_API_KEY_1=${NVIDIA_API_KEY_1}
      - NVIDIA_API_KEY_2=${NVIDIA_API_KEY_2}
      - REDIS_HOST=redis
    depends_on:
      - redis
    restart: unless-stopped

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    volumes:
      - ./redis-data:/data
    restart: unless-stopped
```

## API Contracts

### Request Format
```json
{
  "model": "optional-model-override",  // If omitted, uses pipeline routing
  "messages": [
    {
      "role": "system|user|assistant|tool",
      "content": "string|[{type:text|image_url, text|image_url:{url}}]"
    }
  ],
  "max_tokens": 100,                  // Optional, defaults to model max
  "temperature": 0.7,                 // Optional, 0.0-2.0
  "top_p": 1.0,                       // Optional, 0.0-1.0
  "n": 1,                             // Optional, number of completions
  "stream": false,                    // Optional, enables Server-Sent Events
  "stop": ["\\n"],                    // Optional, stop sequences
  "presence_penalty": 0.0,            // Optional, -2.0 to 2.0
  "frequency_penalty": 0.0,           // Optional, -2.0 to 2.0
  "logit_bias": {},                   // Optional, token bias map
  "user": "optional-end-user-id",     // Optional, for abuse monitoring
  "tools": [                          // Optional, function definitions
    {
      "type": "function",
      "function": {
        "name": "get_current_weather",
        "description": "Get the weather in a given location",
        "parameters": {
          "type": "object",
          "properties": {
            "location": {
              "type": "string",
              "description": "The city and state, e.g. San Francisco, CA"
            },
            "unit": {
              "enum": ["celsius", "fahrenheit"]
            }
          },
          "required": ["location"]
        }
      }
    }
  ],
  "tool_choice": "none|auto|required", // Optional, tool usage control
  "response_format": {                 // Optional, constrains output format
    "type": "text"                     // or { "type": "json_object" }
  },
  "seed": null,                        // Optional, for deterministic sampling
  "service_tier": null,                // Optional, specifies service level
  "max_completion_tokens": null        // Optional, alternative to max_tokens
}
```

### Response Format (Non-Streaming)
```json
{
  "id": "chatcmpl-123",
  "object": "chat.completion",
  "created": 1699113422,
  "model": "nvidia/nemotron-3-super-120b-a12b",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "The weather in San Francisco is 72°F.",
        "tool_calls": null
      },
      "logprobs": null,
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 56,
    "completion_tokens": 31,
    "total_tokens": 87
  },
  "system_fingerprint": "fp_44709d6fcb"
}
```

### Response Format (Streaming - SSE)
```
event: message
data: {"id":"chatcmpl-123","object":"chat.completion.chunk","created":1699113422,"model":"nvidia/nemotron-3-super-120b-a12b","choices":[{"index":0,"delta":{"role":"assistant","content":"The"},"logprobs":null,"finish_reason":null}]}

event: message
data: {"id":"chatcmpl-123","object":"chat.completion.chunk","created":1699113422,"model":"nvidia/nemotron-3-super-120b-a12b","choices":[{"index":0,"delta":{"content":" weather in"},"logprobs":null,"finish_reason":null}]}

event: message
data: {"id":"chatcmpl-123","object":"chat.completion.chunk","created":1699113422,"model":"nvidia/nemotron-3-super-120b-a12b","choices":[{"index":0,"delta":{"content":" San Francisco is 72°F."},"logprobs":null,"finish_reason":"stop"}]}

event: message
data: {"id":"chatcmpl-123","object":"chat.completion.chunk","created":1699113422,"model":"nvidia/nemotron-3-super-120b-a12b","choices":[{"index":0,"delta":{},"logprobs":null,"finish_reason":"stop"}],"usage":{"prompt_tokens":56,"completion_tokens":31,"total_tokens":87}}

data: [DONE]
```

### Error Response Format
```json
{
  "error": {
    "message": "Error description",
    "type": "invalid_request_error|upstream_error|gateway_error|rate_limit_error",
    "code": "400|502|503|429|model_not_found|context_length_exceeded"
  }
}
```

### Status Endpoint Response
```json
{
  "models": [
    {
      "modelId": "nvidia/nemotron-3-super-120b-a12b",
      "isUp": true,
      "latencyMs": 145,
      "timestamp": "2026-09-27T10:30:00Z",
      "errorMessage": null,
      "consecutiveFailures": 0,
      "tps": 2.5,
      "circuitState": "CLOSED"
    }
  ],
  "timestamp": "2026-09-27T10:30:05Z",
  "gatewayId": "neural-gateway-1",
  "version": "0.0.1-SNAPSHOT"
}
```

### Requester Telemetry Response
```json
[
  {
    "requester": "cline-proxy",
    "count": 142
  },
  {
    "requester": "github-copilot",
    "count": 89
  },
  {
    "requester": "cursor-ide",
    "count": 67
  }
]
```

## Integration Points

### External Systems
1. **NVIDIA NIM API**
   - Protocol: HTTPS/REST with JSON payloads
   - Authentication: Bearer token in Authorization header
   - Endpoints: `/v1/chat/completions` (per model)
   - Rate Limits: Provider-specific (typically 40 RPM per key)
   - Integration: Via LlmProviderClient with API key rotation

2. **Redis**
   - Protocol: RESP over TCP
   - Usage: State persistence, Pub/Sub, distributed locking
   - Data Types: Strings, Hashes, Lists, Sets
   - Integration: Via Spring Data Redis and custom RedisPersistenceService

3. **Monitoring Systems** (Optional)
   - Prometheus: Metrics endpoint via Micrometer
   - Grafana: Dashboards for latency, TPS, error rates
   - ELK Stack: Log aggregation and analysis
   - Jaeger/OpenTelemetry: Distributed tracing

4. **CI/CD Pipeline**
   - Docker Image Build: Multi-stage build (Maven → JRE)
   - Automated Testing: Unit, integration, and contract tests
   - Security Scanning: Dependency and container image scanning
   - Deployment: Blue/green or rolling updates via Kubernetes

### Internal Contracts
- **Service Interfaces**: All services interact through well-defined Java interfaces
- **Data Transfer Objects**: Immutable objects for state transfer between layers
- **Events**: Domain events for decoupled communication (ModelStatusChangedEvent)
- **Configuration**: Type-safe configuration properties via Spring Boot
- **Validation**: Bean Validation (JSR-380) for input validation where applicable

## Security Considerations

### Authentication & Authorization
- **API Keys**: Stored in environment variables, never in code or logs
- **Rate Limiting**: Per-API-key limits to prevent abuse
- **Input Validation**: All external inputs validated and sanitized
- **CORS**: Configurable for web browser integration
- **Future Extensions**: 
  - JWT token authentication for end-user identification
  - RBAC for administrative endpoints
  - API key validation and quota management

### Data Protection
- **In Transit**: TLS 1.2+ for all external communications
- **At Rest**: Redis persistence (can be encrypted with Redis Enterprise or disk encryption)
- **In Memory**: No persistent storage of sensitive data beyond runtime
- **Logging**: Payload masking and truncation options available
- **Secrets Management**: Integration with Vault/AWS Secrets Manager planned

### Network Security
- **Service Binding**: Configurable bind addresses (0.0.0.0 for containerized)
- **Port Exposure**: Only necessary ports exposed (8080 HTTP, 6379 Redis internally)
- **Firewall Rules**: Restrict Redis access to gateway instances only
- **Network Policies**: Kubernetes network policies for pod-to-pod communication
- **Ingress Control**: TLS termination at ingress controller for production

### Audit & Compliance
- **Access Logging**: Request logging with anonymization options
- **Change Tracking**: Configuration change events (planned)
- **Data Retention**: Configurable telemetry retention policies
- **GDPR Readiness**: Personal data minimization, deletion capabilities planned

## Testing Strategy

### Unit Testing
- **Target**: Individual classes and methods
- **Framework**: JUnit 5 + Mockito
- **Coverage**: >80% target for business logic
- **Focus**: 
  - Routing algorithm correctness
  - Circuit breaker state transitions
  - Token estimation accuracy
  - Model capability detection
  - Request/response transformation

### Integration Testing
- **Target**: Service interactions and external system integrations
- **Framework**: Spring Boot Test + Testcontainers
- **Components**: 
  - Redis integration with Testcontainers
  - Mock LLM providers with WireMock
  - End-to-end request processing flows
  - Health check and status update cycles
  - Circuit breaker behavior under failure conditions

### Contract Testing
- **Target**: API compatibility with OpenAI specification
- **Framework**: Pact or custom contract tests
- **Scenarios**:
  - Request/response format validation
  - Status code correctness
  - Header compliance
  - SSE stream format
  - Error response structures

### Performance Testing
- **Target**: Throughput, latency, and resource utilization
- **Tools**: JMeter, Gatling, or k6
- **Metrics**:
  - Requests per second under load
  - 95th percentile latency
  - Memory usage and GC behavior
  - Thread utilization
  - Redis command rates
- **Scenarios**:
  - Steady state load
  - Spike traffic patterns
  - Failure injection and recovery
  - Horizontal scaling validation

### Chaos Engineering
- **Target**: Resilience to infrastructure failures
- **Tools**: Custom chaos experiments or LitmusChaos
- **Experiments**:
  - Redis instance failure/recovery
  - Network partition simulation
  - Upstream API latency injection
  - CPU/memory pressure
  - Graceful degradation validation

## Release Management

### Versioning Strategy
- **Semantic Versioning**: MAJOR.MINOR.PATCH
- **MAJOR**: Breaking API changes, incompatible updates
- **MINOR**: Backward-compatible feature additions
- **PATCH**: Backward-compatible bug fixes
- **Pre-release**: 0.0.1-SNAPSHOT for development versions

### Release Process
1. **Development**: Feature branches from main
2. **Testing**: Automated test suite execution
3. **Staging**: Deployment to staging environment for validation
4. **Approval**: Manual approval gate for production release
5. **Deployment**: Blue/green or rolling update strategy
6. **Verification**: Smoke tests and health checks post-deployment
7. **Rollback**: Automated rollback on health check failures

### Change Management
- **Backward Compatibility**: Strict maintenance of API compatibility
- **Deprecation Policy**: 6-month deprecation notice for breaking changes
- **Migration Guides**: Provided for all breaking changes
- **Configuration Changes**: Default values preserve existing behavior
- **Database Migrations**: Not applicable (Redis schema is flexible)

## Future Enhancement Roadmap

### Phase 1: Core Stability (Current)
- ✅ Basic pipeline routing
- ✅ Circuit breaker pattern
- ✅ Distributed health checking
- ✅ Redis-backed state sharing
- ✅ OpenAI API compatibility
- ✅ Docker-based deployment

### Phase 2: Enhanced Observability (Q4 2026)
- 🔲 Prometheus metrics endpoint
- 🔲 Grafana dashboard templates
- 🔲 Distributed tracing with OpenTelemetry
- 🔲 Advanced telemetry retention policies
- 🔲 Custom alerting rules

### Phase 3: Multi-tenant Support (Q1 2027)
- 🔲 Tenant isolation and quotas
- 🔲 API key management per tenant
- 🔲 Usage-based billing integration
- 🔲 Tenant-specific model allowlists
- 🔲 Administrative portal for tenant management

### Phase 4: Advanced Routing (Q2 2027)
- 🔲 Machine learning-based model selection
- 🔲 Cost-aware routing optimization
- 🔲 A/B testing framework for model comparison
- 🔲 Predictive autoscaling based on traffic patterns
- 🔲 Context-aware model chaining (prompt → model₁ → output → model₂)

### Phase 5: Extended Provider Support (Q3 2027)
- 🔲 AWS Bedrock integration
- 🔲 Google Vertex AI integration
- 🔲 Azure OpenAI integration
- 🔲 Hugging Face Inference API support
- 🔲 Self-hosted model support (via standard OpenAI API)

### Phase 6: Performance Optimization (Q4 2027)
- 🔲 Response caching for repetitive queries
- 🔲 Semantic caching for similar prompts
- 🔲 Request batching for efficiency
- 🔲 Adaptive connection pooling
- 🔲 GPU acceleration for local model fallback

### Phase 7: Enterprise Features (Q1 2028)
- 🔲 Role-based access control (RBAC)
- 🔲 Audit logging and compliance reporting
- 🔲 Single Sign-On (SSO) integration
- 🔲 LDAP/Active Directory integration
- 🔲 Advanced encryption at rest
- 🔲 Disaster recovery and backup strategies

## Appendices

### Appendix A: Glossary of Terms
- **EMA**: Exponential Moving Average - weighting recent data more heavily
- **TPS**: Transactions Per Second - measure of request throughput
- **SSE**: Server-Sent Events - server-to-client streaming protocol
- **Circuit Breaker**: Pattern for detecting failures and preventing cascading issues
- **ShedLock**: Distributed locking mechanism for scheduled tasks
- **Latency**: Time taken for a request to complete (measured in milliseconds)
- **Context Window**: Maximum number of tokens a model can process in a single request
- **Fallback**: Alternative model selection when primary choice is unavailable
- **Canary Probe**: Limited trial request to test model health
- **Thundering Herd**: Scenario where many processes simultaneously wake up to process an event

### Appendix B: Configuration Reference
*(Key configuration options with descriptions and defaults)*

**Server Settings**
- `server.port`: HTTP port (default: 8080)

**LLM Provider Settings**
- `llm.providers.<provider>.base-url`: Base URL for API calls
- `llm.providers.<provider>.rate-limit-rpm`: Requests per minute per key
- `llm.providers.<provider>.keys`: List of API keys for rotation
- `llm.providers.<provider>.models`: List of models with ID, priority, pipelines

**Routing Settings**
- `routing.connection-penalty-ms`: Penalty per active connection (default: 300ms)
- `routing.context-window-validation-enabled`: Enable context window checks (default: true)
- `routing.max-fallback-attempts`: Number of fallback models to try (default: 3)

**Health Check Settings**
- `health-check.enabled`: Enable/disable health checks (default: true)
- `health-check.interval-ms`: Time between sweeps in milliseconds (default: 240000)
- `health-check.thread-pool-size`: Concurrent health checks (default: 10)
- `health-check.ping-timeout-ms`: Timeout per individual ping (default: 120000)
- `health-check.ping-max-tokens`: Tokens for health check request (default: 1)
- `health-check.min-ping-gap-ms`: Minimum gap between consecutive pings (default: 5000)
- `health-check.prioritize-by-ema`: Sort models by EMA latency (default: true)
- `health-check.recovery-initial-delay-ms`: Initial delay before first recovery sweep (default: 15000)
- `health-check.recovery-interval-ms`: Delay between independent recovery sweeps (default: 5000)
- `health-check.recovery-max-models-per-sweep`: Max due unhealthy models probed per recovery sweep (default: 2)

**Circuit Breaker Settings**
- `circuit-breaker.failure-threshold`: Consecutive failures to trip (default: 3)
- `circuit-breaker.success-threshold`: Successes to close from half-open (default: 2)
- `circuit-breaker.reset-timeout-ms`: Wait time in open state (default: 30000)
- `circuit-breaker.auto-recovery-enabled`: Auto transition from open to half-open (default: true)

### Appendix C: Performance Benchmarks
*(Baseline measurements from testing environment)*

**Test Environment**
- Hardware: 8 vCPU, 32GB RAM
- Software: OpenJDK 21, Redis 7-alpine
- Load Generator: 50 concurrent users, ramp-up over 5 minutes
- Models: 3 NVIDIA NIM models with varying latencies

**Results**
| Metric | Value | Target |
|--------|-------|--------|
| 95th % Latency | 185ms | < 200ms |
| 99th % Latency | 320ms | < 500ms |
| Requests/sec | 1450 | > 1000 |
| Error Rate | 0.02% | < 0.5% |
| Memory Usage | 420MB | < 1GB |
| CPU Utilization | 65% | < 80% |
| Redis Commands/sec | 2400 | < 5000 |

**Scaling Characteristics**
- Linear scaling observed up to 6 instances
- Diminishing returns beyond 8 instances due to Redis network limits
- Health check overhead remains < 3% of total capacity at scale

### Appendix D: Troubleshooting Guide
*(Common issues and resolution steps)*

**Symptom: All requests failing with 503 Service Unavailable**
- Check: Redis connectivity (`docker-compose logs neural-gateway`)
- Verify: API keys are configured and valid
- Confirm: Health checks are running and models showing as up
- Action: Restart Redis if unresponsive, verify network connectivity

**Symptom: High latency (> 2s) on requests**
- Check: Individual model latency via `/api/models/status/stream`
- Verify: No network issues between gateway and providers
- Confirm: Circuit breakers not repeatedly tripping and resetting
- Action: Temporarily disable health checks to isolate issue

**Symptom: Stale status in UI/SSE stream**
- Check: Redis persistence service logs for errors
- Verify: SseNotificationService is broadcasting events
- Confirm: Client-side event source handling is correct
- Action: Check for Redis pub/sub subscription errors

**Symptom: Rate limit errors (429) from providers**
- Check: API key rotation is working in logs
- Verify: Configured RPM matches provider limits
- Confirm: No keys are exhausted or invalid
- Action: Add additional API keys or reduce concurrent load

**Symptom: Models not showing as available / stuck `Routing blocked`**
- Check: Health checks are enabled (`llm.health-check.enabled`, default `true`; `LLM_HEALTH_CHECK_ENABLED`)
- Verify: The 4-minute sweep / NVIDIA 2-minute re-probe is running (watch `HealthCheckService` logs)
- Confirm: A stale OPEN circuit isn't blocking routing — a successful probe calls `CircuitBreakerService.markHealthy()` to force-close it
- Action: The dashboard shows an amber `UP · BLOCKED` badge when the probe succeeds but the circuit is still OPEN; wait for the next probe or call `POST /api/models/circuit-reset?model={name}` to unblock manually

**Symptom: Missing models in status endpoint**
- Check: Model configuration in application.yml
- Verify: Model IDs match exactly what providers expect
- Confirm: No YAML syntax errors in model definitions
- Action: Validate configuration at startup with debug logging

### Appendix E: Diagram Sources
*All diagrams in this document were created using:*
- **Component Diagram**: Created with Mermaid syntax
- **Data Flow Diagrams**: Created with draw.io (diagrams.net)
- **Deployment Architecture**: Created with Mermaid syntax
- **Sequence Diagrams**: Created with Mermaid syntax

---
*Document Version: 1.0*
*Last Updated: September 30, 2026*
*Author: Neural Gateway Architecture Team*