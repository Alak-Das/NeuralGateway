package com.example.llmservice;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import jakarta.annotation.PreDestroy;

@Slf4j
@Service
public class NvidiaLlmService {
    private final WebClient webClient;
    private final ToolCallNormalizer toolCallNormalizer;
    private final PayloadTelemetryService payloadTelemetryService;
    private final List<String> reasoningModels;
    private final List<String> codingModels;
    private final List<String> visionModels;
    private final List<String> allModels;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    
    private final Map<String, ModelStatus> latestStatusMap = new ConcurrentHashMap<>();
    
    
    
    // Requester Tracking
    
    
    // Advanced Routing Telemetry
    private final Map<String, AtomicInteger> activeConnectionsMap = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> consecutiveErrorsMap = new ConcurrentHashMap<>();
    private final Map<String, Double> tpsMap = new ConcurrentHashMap<>();
    private final Map<String, Double> emaLatencyMap = new ConcurrentHashMap<>();
    

    // SSE Emitters for real-time updates
    private final List<SseEmitter> statusEmitters = new CopyOnWriteArrayList<>();
    private final Object pingLock = new Object();
    private volatile long lastPingEndTime = 0;

    private static final int MAX_HISTORY_SIZE = 1440; // 24 hours of 1-minute pings
    private static final int CIRCUIT_BREAKER_THRESHOLD = 2;

    private static final Map<String, Integer> MODEL_CONTEXT_LIMITS = Map.ofEntries(
        Map.entry("moonshotai/kimi-k3", 128000),
        Map.entry("nvidia/nemotron-3-ultra-550b-a55b", 128000),
        Map.entry("nvidia/nemotron-3-super-120b-a12b", 128000),
        Map.entry("mistralai/mistral-nemotron", 128000),
        Map.entry("poolside/laguna-xs-2.1", 128000),
        Map.entry("z-ai/glm-5.3", 128000),
        Map.entry("z-ai/glm-5.3-flash", 128000),
        Map.entry("deepseek-ai/deepseek-v4.1-flash", 128000),
        Map.entry("deepseek-ai/deepseek-coder-v4.1", 128000),
        Map.entry("google/gemma-4-31b-it", 128000),
        Map.entry("nvidia/nemotron-3-nano-omni-30b-a3b-reasoning", 32000),
        Map.entry("meta/llama-3.2-11b-vision-instruct", 128000),
        Map.entry("meta/llama-3.2-90b-vision-instruct", 128000),
        Map.entry("microsoft/phi-3-vision-128k-instruct", 128000),
        Map.entry("nvidia/neva-22b", 4000)
    );
    private static final int DEFAULT_CONTEXT_LIMIT = 32000;
 // Trip after 2 consecutive errors

    public NvidiaLlmService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            WebClient.Builder webClientBuilder,
            ToolCallNormalizer toolCallNormalizer,
            PayloadTelemetryService payloadTelemetryService,
            @Value("${nvidia.api.base-url}") String baseUrl,
            @Value("${nvidia.api.key}") String apiKey,
            @Value("${nvidia.reasoning-models}") String reasoningModelsCsv,
            @Value("${nvidia.coding-models}") String codingModelsCsv,
            @Value("${nvidia.vision-models}") String visionModelsCsv) {
        
        this.toolCallNormalizer = toolCallNormalizer;
        this.payloadTelemetryService = payloadTelemetryService;
        this.reasoningModels = List.of(reasoningModelsCsv.split(","));
        this.codingModels = List.of(codingModelsCsv.split(","));
        this.visionModels = List.of(visionModelsCsv.split(","));
        
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.allModels = java.util.stream.Stream.of(this.reasoningModels, this.codingModels, this.visionModels)
                                 .flatMap(List::stream)
                                 .map(String::trim)
                                 .filter(s -> !s.isEmpty())
                                 .distinct()
                                 .collect(Collectors.toList());
        
        // Initialize telemetry maps for all models to prevent NullPointerException
        for (String model : this.allModels) {
            activeConnectionsMap.putIfAbsent(model, new AtomicInteger(0));
            consecutiveErrorsMap.putIfAbsent(model, new AtomicInteger(0));
            tpsMap.putIfAbsent(model, 0.0);
            emaLatencyMap.putIfAbsent(model, getEmaLatency(model));

            // Initialize status with default values
            List<String> categories = new ArrayList<>();
            if (reasoningModels.contains(model)) categories.add("Reasoning");
            if (codingModels.contains(model)) categories.add("Coding");
            if (visionModels.contains(model)) categories.add("Vision");
            if (categories.isEmpty()) categories.add("Unknown");

            // Initialize status by restoring history, usage, and circuit breaker from Redis if available
            List<PingResult> history = new ArrayList<>();
            boolean isUp = false;
            long latency = 0L;
            Instant lastChecked = Instant.now();
            String errorMsg = "Not yet checked";

            try {
                List<String> histStrs = redisTemplate.opsForList().range("gateway:model:history:" + model, 0, -1);
                if (histStrs != null && !histStrs.isEmpty()) {
                    for (String s : histStrs) {
                        try {
                            history.add(objectMapper.readValue(s, PingResult.class));
                        } catch (Exception ex) {}
                    }
                    if (!history.isEmpty()) {
                        PingResult lastPing = history.get(history.size() - 1);
                        isUp = lastPing.isUp();
                        latency = lastPing.latencyMs();
                        lastChecked = lastPing.timestamp() != null ? lastPing.timestamp() : Instant.now();
                        errorMsg = isUp ? null : "Recovered from history";
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to restore history from Redis for model {}: {}", model, e.getMessage());
            }

            long usage = 0L;
            try {
                Object usageObj = redisTemplate.opsForHash().get("gateway:model:usage", model);
                if (usageObj != null) {
                    usage = Long.parseLong(usageObj.toString());
                }
            } catch (Exception e) {}

            boolean circuitOpen = false;
            try {
                Object cbObj = redisTemplate.opsForHash().get("gateway:model:circuit", model);
                if (cbObj != null) {
                    circuitOpen = Boolean.parseBoolean(cbObj.toString());
                }
            } catch (Exception e) {}

            latestStatusMap.put(model, new ModelStatus(
                model,
                categories,
                isUp,
                latency,
                lastChecked,
                errorMsg,
                history,
                usage,
                0,
                0.0,
                circuitOpen
            ));
        }

        this.webClient = webClientBuilder.baseUrl(baseUrl)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        log.info("Initialized NvidiaLlmService with {} models (state restored from Redis): {}", allModels.size(), allModels);
    }

    private double getEmaLatency(String model) {
        List<String> historyStr = redisTemplate.opsForList().range("gateway:model:history:" + model, 0, -1);
        if (historyStr == null || historyStr.isEmpty()) return 10000.0;
        
        double ema = -1;
        double alpha = 0.3;
        for (String str : historyStr) {
            try {
                PingResult pr = objectMapper.readValue(str, PingResult.class);
                if (pr.isUp()) {
                    if (ema == -1) {
                        ema = pr.latencyMs();
                    } else {
                        ema = (alpha * pr.latencyMs()) + ((1 - alpha) * ema);
                    }
                }
            } catch (Exception e) {}
        }
        return ema == -1 ? 10000.0 : ema;
    }

    private double calculateRoutingScore(String model) {
        double baseLatency = emaLatencyMap.getOrDefault(model, 10000.0);
        int activeConns = activeConnectionsMap.get(model) != null ? activeConnectionsMap.get(model).get() : 0;
        boolean circuitOpen = latestStatusMap.get(model) != null && latestStatusMap.get(model).circuitOpen();
        // Least Connections: add a 300ms simulated penalty for each active connection
        // Circuit open: add a 50000ms penalty so tripped models are routed last
        return baseLatency + (activeConns * 300.0) + (circuitOpen ? 50000.0 : 0.0);
    }

    public List<String> getTargetModels(String pipeline) {
        if ("coding".equalsIgnoreCase(pipeline)) {
            return codingModels;
        } else if ("vision".equalsIgnoreCase(pipeline)) {
            return visionModels;
        } else {
            return reasoningModels;
        }
    }

    private record LlmCallResult(String content, long totalTokens) {}

    public LlmResponse generate(String prompt, String requester, String transactionId, boolean isCoding) {
        return generate(prompt, requester, transactionId, isCoding ? "coding" : "reasoning");
    }

    public LlmResponse generate(String prompt, String requester, String transactionId, String pipeline) {
        List<String> targetModels = getTargetModels(pipeline);
        
        // Intelligently pick the best models: Must be UP and Circuit Closed and in Target List
        List<String> upModels = latestStatusMap.values().stream()
                .filter(s -> targetModels.contains(s.model()))
                .filter(ModelStatus::isUp)
                .filter(s -> !s.circuitOpen())
                .sorted(Comparator.comparingDouble(s -> calculateRoutingScore(s.model())))
                .map(ModelStatus::model)
                .collect(Collectors.toList());
                
        // Fallback to DOWN or Open Circuit models if absolutely necessary, sorted by lowest score
        List<String> downModels = latestStatusMap.values().stream()
                .filter(s -> targetModels.contains(s.model()))
                .filter(status -> !status.isUp() || status.circuitOpen())
                .sorted(Comparator.comparingDouble(s -> calculateRoutingScore(s.model())))
                .map(ModelStatus::model)
                .collect(Collectors.toList());

        int attempts = 0;
        for (String model : upModels) {
            if (++attempts > 3) break;
            try {
                return executeWithTelemetry(model, prompt, "UP", requester, transactionId);
            } catch (Exception e) {
                log.warn("[TxID: {}] UP Model {} failed during generate. Failing over. Error: {}", transactionId, model, e.getMessage());
            }
        }
        
        int downAttempts = 0;
        int maxDownAttempts = upModels.isEmpty() ? 3 : 1;
        for (String model : downModels) {
            if (++downAttempts > maxDownAttempts) break;
            try {
                return executeWithTelemetry(model, prompt, "DOWN", requester, transactionId);
            } catch (Exception e) {
                log.warn("[TxID: {}] DOWN Model {} failed during generate. Error: {}", transactionId, model, e.getMessage());
            }
        }

        log.error("[TxID: {}] All available models failed to fulfill the generation request.", transactionId);
        return new LlmResponse(transactionId, "system", "I apologize, but all AI models are currently unreachable or experiencing heavy load.");
    }
    
    private LlmResponse executeWithTelemetry(String model, String prompt, String state, String requester, String transactionId) {
        log.debug("[TxID: {}] Routing request to {} model: {} (Score: {})", transactionId, state, model, calculateRoutingScore(model));
        
        activeConnectionsMap.get(model).incrementAndGet();
        updateLatestStatus(model);
        
        long start = System.currentTimeMillis();
        boolean connectionActive = true;
        long timeTaken = 0;
        try {
            LlmCallResult result = performLlmCall(model, prompt);
            timeTaken = System.currentTimeMillis() - start;
            
            // Success: Update usage and TPS
            redisTemplate.opsForHash().increment("gateway:model:usage", model, result.totalTokens());
            redisTemplate.opsForHash().increment("gateway:requester:usage", requester, result.totalTokens());
            
            if (timeTaken > 0 && result.totalTokens() > 0) {
                double currentTps = (result.totalTokens() * 1000.0) / timeTaken;
                tpsMap.compute(model, (k, oldTps) -> oldTps == 0.0 ? currentTps : (0.2 * currentTps + 0.8 * oldTps));
            }
            
            activeConnectionsMap.get(model).decrementAndGet();
            connectionActive = false;
            recordModelSuccess(model, timeTaken);
            
            log.info("[TxID: {}] Successfully generated response using {} model: {} in {}ms", transactionId, state, model, timeTaken);
            return new LlmResponse(transactionId, model, result.content());
            
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            timeTaken = System.currentTimeMillis() - start;
            if (connectionActive) {
                activeConnectionsMap.get(model).decrementAndGet();
                connectionActive = false;
            }
            if (!e.getStatusCode().is4xxClientError()) {
                recordModelFailure(model, timeTaken, e);
            } else {
                updateLatestStatus(model);
            }
            throw e;
        } catch (Exception e) {
            timeTaken = System.currentTimeMillis() - start;
            if (connectionActive) {
                activeConnectionsMap.get(model).decrementAndGet();
                connectionActive = false;
            }
            recordModelFailure(model, timeTaken, e);
            throw e;
        } finally {
            if (connectionActive) {
                activeConnectionsMap.get(model).decrementAndGet();
                updateLatestStatus(model);
            }
        }
    }
    
    
    private int estimateTokens(Map<String, Object> requestBody) {
        if (requestBody == null || !requestBody.containsKey("messages")) return 0;
        try {
            Object msgsObj = requestBody.get("messages");
            if (!(msgsObj instanceof List<?> messages)) return 0;
            int estimatedTokens = 0;
            for (Object mObj : messages) {
                if (!(mObj instanceof Map<?, ?> m)) continue;
                Object content = m.get("content");
                if (content instanceof String str) {
                    estimatedTokens += str.length() / 4;
                } else if (content instanceof List<?> parts) {
                    for (Object partObj : parts) {
                        if (partObj instanceof Map<?, ?> part) {
                            String type = (String) part.get("type");
                            if ("text".equals(type) && part.get("text") instanceof String t) {
                                estimatedTokens += t.length() / 4;
                            } else if ("image_url".equals(type)) {
                                estimatedTokens += 1000; // Standard nominal token budget for vision image
                            }
                        }
                    }
                }
            }
            return estimatedTokens;
        } catch (Exception e) {
            return 0;
        }
    }

    public Map<String, Object> generateOpenAiProxy(Map<String, Object> requestBody, String requester, String transactionId, boolean isCoding) {
        return generateOpenAiProxy(requestBody, requester, transactionId, isCoding ? "coding" : "reasoning");
    }

    public Map<String, Object> generateOpenAiProxy(Map<String, Object> requestBody, String requester, String transactionId, String pipeline) {
        List<String> targetModels = getTargetModels(pipeline);
        final int estimatedTokens = estimateTokens(requestBody);
        
        List<String> upModels = latestStatusMap.values().stream()
                .filter(s -> targetModels.contains(s.model()))
                .filter(s -> estimatedTokens <= MODEL_CONTEXT_LIMITS.getOrDefault(s.model(), DEFAULT_CONTEXT_LIMIT))
                .filter(ModelStatus::isUp)
                .filter(s -> !s.circuitOpen())
                .sorted(Comparator.comparingDouble(s -> calculateRoutingScore(s.model()))) // Ascending score: lowest latency & least connections first
                .map(ModelStatus::model)
                .collect(Collectors.toList());
                
        List<String> downModels = latestStatusMap.values().stream()
                .filter(s -> targetModels.contains(s.model()))
                .filter(s -> estimatedTokens <= MODEL_CONTEXT_LIMITS.getOrDefault(s.model(), DEFAULT_CONTEXT_LIMIT))
                .filter(status -> !status.isUp() || status.circuitOpen())
                .sorted(Comparator.comparingDouble(s -> calculateRoutingScore(s.model())))
                .map(ModelStatus::model)
                .collect(Collectors.toList());

        int attempts = 0;
        for (String model : upModels) {
            if (++attempts > 3) break;
            try {
                return executeWithTelemetryOpenAi(model, requestBody, "UP", requester, transactionId);
            } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
                if (e.getStatusCode().is4xxClientError()) {
                    throw e; // Client payload error - do not failover, rethrow immediately
                }
                log.warn("[TxID: {}] UP Proxy Model {} failed. Error: {}", transactionId, model, e.getMessage());
            } catch (Exception e) {
                log.warn("[TxID: {}] UP Proxy Model {} failed. Error: {}", transactionId, model, e.getMessage());
            }
        }
        
        int downAttempts = 0;
        int maxDownAttempts = upModels.isEmpty() ? 3 : 1; // If no UP models available (cold start or temporary outage), try up to 3 best down models
        for (String model : downModels) {
            if (++downAttempts > maxDownAttempts) break;
            try {
                return executeWithTelemetryOpenAi(model, requestBody, "DOWN", requester, transactionId);
            } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
                if (e.getStatusCode().is4xxClientError()) {
                    throw e;
                }
                log.warn("[TxID: {}] DOWN Proxy Model {} failed. Error: {}", transactionId, model, e.getMessage());
            } catch (Exception e) {
                log.warn("[TxID: {}] DOWN Proxy Model {} failed. Error: {}", transactionId, model, e.getMessage());
            }
        }

        log.error("[TxID: {}] All available models failed to fulfill the proxy request.", transactionId);
        throw new RuntimeException("All backend models failed.");
    }

    private Map<String, Object> executeWithTelemetryOpenAi(String model, Map<String, Object> requestBody, String state, String requester, String transactionId) {
        log.debug("[TxID: {}] Routing proxy request to {} model: {} (Score: {})", transactionId, state, model, calculateRoutingScore(model));
        
        activeConnectionsMap.get(model).incrementAndGet();
        updateLatestStatus(model);
        
        long start = System.currentTimeMillis();
        boolean connectionActive = true;
        long timeTaken = 0;
        try {
            Map<String, Object> overrideBody = new java.util.HashMap<>(requestBody);
            overrideBody.put("model", model);
            overrideBody.put("stream", false); // Force non-streaming response for proxy
            overrideBody.remove("stream_options"); // Nvidia API rejects this if stream is false
            
            // Normalize thinking_effort if present (e.g. Cline sending "xhigh" which Kimi K3 rejects)
            if (overrideBody.containsKey("thinking_effort")) {
                Object te = overrideBody.get("thinking_effort");
                if (te != null && "xhigh".equalsIgnoreCase(te.toString())) {
                    overrideBody.put("thinking_effort", "max");
                }
            }

            // Message preprocessing: tool reminder & ChatML role sequence normalization
            Object msgsObj = overrideBody.get("messages");
            if (msgsObj instanceof List<?> msgsList) {
                List<Map<String, Object>> newMsgs = new ArrayList<>();
                boolean reminderAdded = false;
                String lastRole = null;
                for (Object m : msgsList) {
                    if (m instanceof Map<?, ?> msgMap) {
                        Map<String, Object> copyMsg = new HashMap<>((Map<String, Object>) msgMap);
                        String currentRole = (String) copyMsg.get("role");

                        // Strict ChatML fix: if user message follows immediately after tool, insert bridge assistant turn
                        if ("user".equals(currentRole) && "tool".equals(lastRole)) {
                            Map<String, Object> bridgeMsg = new HashMap<>();
                            bridgeMsg.put("role", "assistant");
                            bridgeMsg.put("content", "Tool execution complete. Proceeding with user instructions.");
                            newMsgs.add(bridgeMsg);
                        }

                        if ("system".equals(currentRole) && !reminderAdded && overrideBody.containsKey("tools")) {
                            Object rawContent = copyMsg.get("content");
                            if (rawContent instanceof String content) {
                                String promptReminder = "\n\n[TOOL CALLING MANDATE]: You MUST strictly follow the exact property names in each tool schema. For the 'editor' tool, you MUST use snake_case parameter names: 'path', 'new_text', 'old_text', and 'insert_line'. NEVER use camelCase like 'newText' or 'oldText'. For 'run_commands', 'commands' must be an array of string commands.";
                                copyMsg.put("content", content + promptReminder);
                                reminderAdded = true;
                            }
                        }
                        newMsgs.add(copyMsg);
                        lastRole = currentRole;
                    }
                }
                overrideBody.put("messages", newMsgs);
            }

            if (log.isDebugEnabled()) {
                String summary = payloadTelemetryService.summarizeRequest(overrideBody);
                if (summary != null) {
                    log.debug("[TxID: {}] Request Summary: {}", transactionId, summary);
                }
            }
            
            Map response = webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(overrideBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(java.time.Duration.ofSeconds(75))
                    .block();
                    
            timeTaken = System.currentTimeMillis() - start;
            long totalTokens = 0;
            
            if (response != null && response.containsKey("usage")) {
                Map<String, Object> usage = (Map<String, Object>) response.get("usage");
                if (usage != null && usage.containsKey("total_tokens")) {
                    totalTokens = ((Number) usage.get("total_tokens")).longValue();
                }
            }
            
            redisTemplate.opsForHash().increment("gateway:model:usage", model, totalTokens);
            redisTemplate.opsForHash().increment("gateway:requester:usage", requester, totalTokens);
            
            if (timeTaken > 0 && totalTokens > 0) {
                double currentTps = (totalTokens * 1000.0) / timeTaken;
                tpsMap.compute(model, (k, oldTps) -> oldTps == 0.0 ? currentTps : (0.2 * currentTps + 0.8 * oldTps));
            }
            
            activeConnectionsMap.get(model).decrementAndGet();
            connectionActive = false;
            recordModelSuccess(model, timeTaken);
            
            log.info("[TxID: {}] Successfully proxied response using {} model: {} in {}ms", transactionId, state, model, timeTaken);
            
            if (response != null) {
                response.put("id", transactionId);
                response.put("model", model);
                toolCallNormalizer.normalizeToolCalls(response, requestBody, transactionId);
            }
            return response;
            
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            timeTaken = System.currentTimeMillis() - start;
            if (connectionActive) {
                activeConnectionsMap.get(model).decrementAndGet();
                connectionActive = false;
            }
            log.error("[TxID: {}] Nvidia API returned {}: {}", transactionId, e.getStatusCode(), e.getResponseBodyAsString());
            if (!e.getStatusCode().is4xxClientError()) {
                recordModelFailure(model, timeTaken, e);
            } else {
                updateLatestStatus(model);
            }
            throw e;
        } catch (Exception e) {
            timeTaken = System.currentTimeMillis() - start;
            if (connectionActive) {
                activeConnectionsMap.get(model).decrementAndGet();
                connectionActive = false;
            }
            recordModelFailure(model, timeTaken, e);
            throw e;
        } finally {
            if (connectionActive) {
                activeConnectionsMap.get(model).decrementAndGet();
                updateLatestStatus(model);
            }
        }
    }
    
    public Map<String, Long> getRequesterUsage() {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries("gateway:requester:usage");
        return entries.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> e.getKey().toString(),
                        e -> Long.parseLong(e.getValue().toString())
                ));
    }

    /**
     * Streams an OpenAI-compatible chat completion request from the upstream model to the client
     * in real-time. Tokens are forwarded as SSE chunks as they arrive from the backend, instead of
     * buffering the entire response first.
     */
    public Flux<String> generateOpenAiProxyStream(Map<String, Object> requestBody, String requester, String transactionId, boolean isCoding) {
        return generateOpenAiProxyStream(requestBody, requester, transactionId, isCoding ? "coding" : "reasoning");
    }

    public Flux<String> generateOpenAiProxyStream(Map<String, Object> requestBody, String requester, String transactionId, String pipeline) {
        List<String> targetModels = getTargetModels(pipeline);
        final int estimatedTokens = estimateTokens(requestBody);

        List<String> upModels = latestStatusMap.values().stream()
                .filter(s -> targetModels.contains(s.model()))
                .filter(s -> estimatedTokens <= MODEL_CONTEXT_LIMITS.getOrDefault(s.model(), DEFAULT_CONTEXT_LIMIT))
                .filter(ModelStatus::isUp)
                .filter(s -> !s.circuitOpen())
                .sorted(Comparator.comparingDouble(s -> calculateRoutingScore(s.model())))
                .map(ModelStatus::model)
                .collect(Collectors.toList());

        List<String> downModels = latestStatusMap.values().stream()
                .filter(s -> targetModels.contains(s.model()))
                .filter(s -> estimatedTokens <= MODEL_CONTEXT_LIMITS.getOrDefault(s.model(), DEFAULT_CONTEXT_LIMIT))
                .filter(status -> !status.isUp() || status.circuitOpen())
                .sorted(Comparator.comparingDouble(s -> calculateRoutingScore(s.model())))
                .map(ModelStatus::model)
                .collect(Collectors.toList());

        List<String> candidates = new ArrayList<>(upModels);
        for (String dm : downModels) {
            if (!candidates.contains(dm)) {
                candidates.add(dm);
            }
        }

        return tryStreamModel(candidates, 0, requestBody, requester, transactionId);
    }
    
    private Flux<String> tryStreamModel(List<String> models, int index, Map<String, Object> requestBody, String requester, String transactionId) {
        if (index >= models.size() || index >= 3) { // Max 3 retries
            log.error("[TxID: {}] All available models failed to stream the proxy request.", transactionId);
            return Flux.error(new RuntimeException("All backend models failed."));
        }
        String model = models.get(index);
        String state = latestStatusMap.get(model) != null && latestStatusMap.get(model).isUp() ? "UP" : "DOWN";
        return executeWithTelemetryOpenAiStream(model, requestBody, state, requester, transactionId)
            .onErrorResume(e -> {
                log.warn("[TxID: {}] {} Streaming Proxy Model {} failed to stream. Error: {}. Transparently retrying next model...", transactionId, state, model, e.getMessage());
                return tryStreamModel(models, index + 1, requestBody, requester, transactionId);
            });
    }

    private Flux<String> executeWithTelemetryOpenAiStream(String model, Map<String, Object> requestBody, String state, String requester, String transactionId) {
        log.debug("[TxID: {}] Routing streaming proxy request to {} model: {} (Score: {})", transactionId, state, model, calculateRoutingScore(model));

        activeConnectionsMap.get(model).incrementAndGet();
        updateLatestStatus(model);

        Map<String, Object> overrideBody = new HashMap<>(requestBody);
        overrideBody.put("model", model);
        overrideBody.put("stream", true); // Request true streaming from the backend

        long start = System.currentTimeMillis();
        AtomicBoolean connectionActive = new AtomicBoolean(true);
        Flux<String> contentFlux = webClient.post()
                .uri("/chat/completions")
                .bodyValue(overrideBody)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(java.time.Duration.ofSeconds(180))
                .doOnComplete(() -> {
                    long timeTaken = System.currentTimeMillis() - start;
                    if (connectionActive.compareAndSet(true, false)) {
                        activeConnectionsMap.get(model).decrementAndGet();
                    }
                    recordModelSuccess(model, timeTaken);
                })
                .doOnError(e -> {
                    long timeTaken = System.currentTimeMillis() - start;
                    if (connectionActive.compareAndSet(true, false)) {
                        activeConnectionsMap.get(model).decrementAndGet();
                    }
                    if (e instanceof org.springframework.web.reactive.function.client.WebClientResponseException wce) {
                        if (!wce.getStatusCode().is4xxClientError()) {
                            recordModelFailure(model, timeTaken, e);
                        } else {
                            updateLatestStatus(model);
                        }
                    } else {
                        recordModelFailure(model, timeTaken, e);
                    }
                })
                .doFinally(signal -> {
                    long timeTaken = System.currentTimeMillis() - start;
                    if (connectionActive.compareAndSet(true, false)) {
                        activeConnectionsMap.get(model).decrementAndGet();
                        updateLatestStatus(model);
                    }
                    log.debug("[TxID: {}] Streaming request to {} model: {} finished in {}ms with signal: {}",
                            transactionId, state, model, timeTaken, signal);
                });

        return contentFlux;
    }
    
    private void updateLatestStatus(String model) {
        latestStatusMap.computeIfPresent(model, (k, current) -> {
            Object usageObj = redisTemplate.opsForHash().get("gateway:model:usage", model);
            long usage = usageObj != null ? Long.parseLong(usageObj.toString()) : 0L;
            Object cbObj = redisTemplate.opsForHash().get("gateway:model:circuit", model);
            boolean circuitOpen = cbObj != null && Boolean.parseBoolean(cbObj.toString());
            return new ModelStatus(
                current.model(), current.categories(), current.isUp(), current.latencyMs(), 
                current.lastChecked(), current.errorMessage(), 
                current.history(), usage,
                activeConnectionsMap.get(model).get(),
                tpsMap.get(model),
                circuitOpen
            );
        });
        notifyStatusChange();
    }
    
    private LlmCallResult performLlmCall(String model, String prompt) {
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "max_tokens", 1024
        );

        Map response = webClient.post()
                .uri("/chat/completions")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(java.time.Duration.ofSeconds(45))
                .block(); 

        if (response != null && response.containsKey("choices")) {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (!choices.isEmpty()) {
                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                String content = (String) message.get("content");
                
                long totalTokens = 0;
                if (response.containsKey("usage")) {
                    Map<String, Object> usage = (Map<String, Object>) response.get("usage");
                    if (usage != null && usage.containsKey("total_tokens")) {
                        totalTokens = ((Number) usage.get("total_tokens")).longValue();
                    }
                }
                
                return new LlmCallResult(content, totalTokens);
            }
        }
        throw new RuntimeException("Invalid response format");
    }

    private void performPingCall(String model) {
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", "ping")),
                "max_tokens", 1
        );

        webClient.post()
                .uri("/chat/completions")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(java.time.Duration.ofSeconds(180))
                .block();
    }

    public void recordModelSuccess(String model, long latencyMs) {
        consecutiveErrorsMap.get(model).set(0);
        redisTemplate.opsForHash().put("gateway:model:circuit", model, "false");

        final double currentLatency = latencyMs;
        emaLatencyMap.compute(model, (k, currentEma) -> {
            if (currentEma == null || currentEma >= 10000.0) {
                return currentLatency;
            }
            double alpha = 0.3;
            return (alpha * currentLatency) + ((1 - alpha) * currentEma);
        });

        ModelStatus prev = latestStatusMap.get(model);
        boolean wasDown = prev != null && (!prev.isUp() || prev.circuitOpen());

        updateModelStatusInternal(model, true, latencyMs, null);

        if (wasDown) {
            log.info("Model {} successfully processed request/ping. Status automatically RECOVERED to UP ({}ms)!", model, latencyMs);
        }
    }

    public void recordModelFailure(String model, long latencyMs, Throwable error) {
        int errors = consecutiveErrorsMap.get(model).incrementAndGet();
        if (errors >= CIRCUIT_BREAKER_THRESHOLD) {
            redisTemplate.opsForHash().put("gateway:model:circuit", model, "true");
            log.error("CIRCUIT BREAKER TRIPPED for model: {} after {} consecutive errors", model, errors);
        }

        emaLatencyMap.put(model, 10000.0);

        ModelStatus prev = latestStatusMap.get(model);
        boolean wasUp = prev != null && prev.isUp();
        String errorMsg = error != null ? error.getMessage() : "Unknown error";

        updateModelStatusInternal(model, false, latencyMs, errorMsg);

        if (wasUp) {
            log.warn("Model {} failed request/ping. Status automatically marked as DOWN ({}ms) - {}", model, latencyMs, errorMsg);
        }
    }

    private List<String> getModelCategories(String model) {
        List<String> categories = new ArrayList<>();
        if (reasoningModels.contains(model)) categories.add("Reasoning");
        if (codingModels.contains(model)) categories.add("Coding");
        if (visionModels.contains(model)) categories.add("Vision");
        if (categories.isEmpty()) categories.add("Unknown");
        return categories;
    }

    private void updateModelStatusInternal(String model, boolean isUp, long latencyMs, String errorMsg) {
        Instant now = Instant.now();
        PingResult result = new PingResult(now, isUp, latencyMs);
        try {
            redisTemplate.opsForList().rightPush("gateway:model:history:" + model, objectMapper.writeValueAsString(result));
            redisTemplate.opsForList().trim("gateway:model:history:" + model, -MAX_HISTORY_SIZE, -1);
        } catch (JsonProcessingException ex) {}

        List<String> histStrs = redisTemplate.opsForList().range("gateway:model:history:" + model, 0, -1);
        List<PingResult> history = new ArrayList<>();
        if (histStrs != null) {
            for (String s : histStrs) {
                try {
                    history.add(objectMapper.readValue(s, PingResult.class));
                } catch (Exception ex) {}
            }
        }

        List<String> categories = getModelCategories(model);
        Object usageObj = redisTemplate.opsForHash().get("gateway:model:usage", model);
        long usage = usageObj != null ? Long.parseLong(usageObj.toString()) : 0L;
        Object cbObj = redisTemplate.opsForHash().get("gateway:model:circuit", model);
        boolean circuitOpen = cbObj != null && Boolean.parseBoolean(cbObj.toString());

        latestStatusMap.put(model, new ModelStatus(
            model, categories, isUp, latencyMs, now, errorMsg, new ArrayList<>(history),
            usage,
            activeConnectionsMap.get(model).get(),
            tpsMap.get(model),
            circuitOpen
        ));

        notifyStatusChange();
    }

    private PingResult pingSingleModelInternal(String model, Instant now) {
        synchronized (pingLock) {
            long timeSinceLastPing = System.currentTimeMillis() - lastPingEndTime;
            if (lastPingEndTime > 0 && timeSinceLastPing < 5000) {
                try {
                    Thread.sleep(5000 - timeSinceLastPing);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            long startTime = System.currentTimeMillis();
            boolean isUp = false;
            long latency = 0;

            try {
                performPingCall(model);
                latency = System.currentTimeMillis() - startTime;
                isUp = true;
                recordModelSuccess(model, latency);
                log.info("Ping successful for model: {} ({}ms)", model, latency);
            } catch (Exception e) {
                latency = System.currentTimeMillis() - startTime;
                recordModelFailure(model, latency, e);
                log.warn("Ping failed for model: {} after {}ms - {}", model, latency, e.getMessage());
            } finally {
                lastPingEndTime = System.currentTimeMillis();
            }

            return new PingResult(now, isUp, latency);
        }
    }

    @Scheduled(fixedDelay = 300000)
    public void pingModels() {
        // Prioritize pings: models with lower historical latency are pinged first
        // so fast and reliable models are validated immediately.
        List<String> sortedModels = allModels.stream()
                .sorted(Comparator.comparingDouble(this::calculateRoutingScore))
                .collect(Collectors.toList());

        log.info("Starting scheduled health ping sweep for {} models (sequential with 5s gap)...", sortedModels.size());
        for (String model : sortedModels) {
            try {
                pingSingleModelInternal(model, Instant.now());
                notifyStatusChange();
            } catch (Exception e) {
                log.error("Failed to ping model {}: {}", model, e.getMessage());
            }
        }
        log.info("Completed scheduled health ping sweep for {} models. Next sweep will run in 5 minutes.", sortedModels.size());
    }
    
    public List<ModelStatus> getModelStatuses() {
        return allModels.stream().map(latestStatusMap::get).collect(Collectors.toList());
    }

    // SSE Support for real-time dashboard updates
    public SseEmitter subscribeToStatusUpdates() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        statusEmitters.add(emitter);

        emitter.onCompletion(() -> statusEmitters.remove(emitter));
        emitter.onTimeout(() -> statusEmitters.remove(emitter));
        emitter.onError((ex) -> statusEmitters.remove(emitter));

        // Send initial state immediately
        try {
            emitter.send(SseEmitter.event()
                    .name("init")
                    .data(getModelStatuses()));
        } catch (Exception e) {
            statusEmitters.remove(emitter);
        }

        return emitter;
    }

    private void broadcastStatusUpdate() {
        List<ModelStatus> statuses = getModelStatuses();
        List<SseEmitter> deadEmitters = new ArrayList<>();
        for (SseEmitter emitter : statusEmitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("status")
                        .data(statuses));
            } catch (Exception e) {
                deadEmitters.add(emitter);
            }
        }
        if (!deadEmitters.isEmpty()) {
            statusEmitters.removeAll(deadEmitters);
        }
    }

    // Call this after pingModels() updates the status
    private void notifyStatusChange() {
        broadcastStatusUpdate();
    }

    // Manual ping for a single model
    public PingResult pingModel(String model) {
        log.info("Manual on-demand ping requested for model: {}", model);
        PingResult result = pingSingleModelInternal(model, Instant.now());
        notifyStatusChange();
        return result;
    }

    @PreDestroy
    public void cleanup() {
    }

    // Reset circuit breaker for a single model
    public void resetCircuitBreaker(String model) {
        if (!allModels.contains(model)) {
            throw new IllegalArgumentException("Model not found: " + model);
        }
        consecutiveErrorsMap.get(model).set(0);
        redisTemplate.opsForHash().put("gateway:model:circuit", model, "false");
        
        log.info("Circuit breaker manually reset for model: {}", model);
        
        // Update status to reflect the change
        updateLatestStatus(model);
        notifyStatusChange();
    }
}
