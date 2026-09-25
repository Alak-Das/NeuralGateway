package com.example.llmservice;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Service
public class NvidiaLlmService {
    private final WebClient webClient;
    private final ToolCallNormalizer toolCallNormalizer;
    private final PayloadTelemetryService payloadTelemetryService;
    private final List<String> reasoningModels;
    private final List<String> codingModels;
    private final List<String> allModels;
    
    private final Map<String, ModelStatus> latestStatusMap = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentLinkedDeque<PingResult>> modelHistoryMap = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> modelUsageMap = new ConcurrentHashMap<>();
    
    // Requester Tracking
    private final Map<String, AtomicLong> requesterUsageMap = new ConcurrentHashMap<>();
    
    // Advanced Routing Telemetry
    private final Map<String, AtomicInteger> activeConnectionsMap = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> consecutiveErrorsMap = new ConcurrentHashMap<>();
    private final Map<String, Double> tpsMap = new ConcurrentHashMap<>();
    private final Map<String, Boolean> circuitBreakerMap = new ConcurrentHashMap<>();

    // SSE Emitters for real-time updates
    private final List<SseEmitter> statusEmitters = new CopyOnWriteArrayList<>();

    private static final int MAX_HISTORY_SIZE = 30;
    private static final int CIRCUIT_BREAKER_THRESHOLD = 2; // Trip after 2 consecutive errors

    public NvidiaLlmService(
            WebClient.Builder webClientBuilder,
            ToolCallNormalizer toolCallNormalizer,
            PayloadTelemetryService payloadTelemetryService,
            @Value("${nvidia.api.base-url}") String baseUrl,
            @Value("${nvidia.api.key}") String apiKey,
            @Value("${nvidia.reasoning-models}") String reasoningModelsCsv,
            @Value("${nvidia.coding-models}") String codingModelsCsv) {
        
        this.toolCallNormalizer = toolCallNormalizer;
        this.payloadTelemetryService = payloadTelemetryService;
        this.reasoningModels = List.of(reasoningModelsCsv.split(","));
        this.codingModels = List.of(codingModelsCsv.split(","));
        
        this.allModels = java.util.stream.Stream.concat(this.reasoningModels.stream(), this.codingModels.stream())
                                 .map(String::trim)
                                 .filter(s -> !s.isEmpty())
                                 .distinct()
                                 .collect(Collectors.toList());
        
        this.webClient = webClientBuilder.baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        
        for (String model : allModels) {
            List<String> categories = new ArrayList<>();
            if (reasoningModels.contains(model)) categories.add("Reasoning");
            if (codingModels.contains(model)) categories.add("Coding");
            if (categories.isEmpty()) categories.add("Unknown");
            
            modelHistoryMap.put(model, new ConcurrentLinkedDeque<>());
            modelUsageMap.put(model, new AtomicLong(0));
            activeConnectionsMap.put(model, new AtomicInteger(0));
            consecutiveErrorsMap.put(model, new AtomicInteger(0));
            tpsMap.put(model, 0.0);
            circuitBreakerMap.put(model, false);
            latestStatusMap.put(model, new ModelStatus(model, categories, false, 0, null, "Not yet checked", new ArrayList<>(), 0, 0, 0.0, false));
        }
        log.info("Initialized NvidiaLlmService with advanced routing strategies.");
    }

    private double getEmaLatency(String model) {
        ConcurrentLinkedDeque<PingResult> history = modelHistoryMap.get(model);
        if (history == null || history.isEmpty()) return 10000.0;
        
        double ema = -1;
        double alpha = 0.3; // Weight for recent data
        for (PingResult pr : history) {
            if (pr.isUp()) {
                if (ema == -1) {
                    ema = pr.latencyMs();
                } else {
                    ema = (alpha * pr.latencyMs()) + ((1 - alpha) * ema);
                }
            }
        }
        return ema == -1 ? 10000.0 : ema;
    }

    private double calculateRoutingScore(String model) {
        double baseLatency = getEmaLatency(model);
        int activeConns = activeConnectionsMap.get(model).get();
        // Least Connections: add a 300ms simulated penalty for each active connection
        return baseLatency + (activeConns * 300.0);
    }

    private record LlmCallResult(String content, long totalTokens) {}

    public LlmResponse generate(String prompt, String requester, String transactionId, boolean isCoding) {
        List<String> targetModels = isCoding ? codingModels : reasoningModels;
        
        // Intelligently pick the best models: Must be UP and Circuit Closed and in Target List
        List<String> upModels = latestStatusMap.values().stream()
                .filter(s -> targetModels.contains(s.model()))
                .filter(ModelStatus::isUp)
                .filter(s -> !s.circuitOpen())
                .sorted((a, b) -> Double.compare(calculateRoutingScore(a.model()), calculateRoutingScore(b.model())))
                .map(ModelStatus::model)
                .collect(Collectors.toList());
                
        // Fallback to DOWN or Open Circuit models if absolutely necessary
        List<String> downModels = latestStatusMap.values().stream()
                .filter(s -> targetModels.contains(s.model()))
                .filter(status -> !status.isUp() || status.circuitOpen())
                .map(ModelStatus::model)
                .collect(Collectors.toList());

        for (String model : upModels) {
            try {
                return executeWithTelemetry(model, prompt, "UP", requester, transactionId);
            } catch (Exception e) {
                log.warn("[TxID: {}] UP Model {} failed during generate. Failing over. Error: {}", transactionId, model, e.getMessage());
            }
        }
        
        for (String model : downModels) {
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
        try {
            LlmCallResult result = performLlmCall(model, prompt);
            long timeTaken = System.currentTimeMillis() - start;
            
            // Success: Update usage and TPS
            modelUsageMap.get(model).addAndGet(result.totalTokens());
            requesterUsageMap.computeIfAbsent(requester, k -> new AtomicLong(0)).addAndGet(result.totalTokens());
            
            if (timeTaken > 0 && result.totalTokens() > 0) {
                double currentTps = (result.totalTokens() * 1000.0) / timeTaken;
                tpsMap.compute(model, (k, oldTps) -> oldTps == 0.0 ? currentTps : (0.2 * currentTps + 0.8 * oldTps));
            }
            
            // Reset Circuit Breaker
            consecutiveErrorsMap.get(model).set(0);
            circuitBreakerMap.put(model, false);
            
            log.info("[TxID: {}] Successfully generated response using {} model: {} in {}ms", transactionId, state, model, timeTaken);
            return new LlmResponse(transactionId, model, result.content());
            
        } catch (Exception e) {
            // Failure: Increment errors and potentially trip circuit
            int errors = consecutiveErrorsMap.get(model).incrementAndGet();
            if (errors >= CIRCUIT_BREAKER_THRESHOLD) {
                circuitBreakerMap.put(model, true);
                log.error("[TxID: {}] CIRCUIT BREAKER TRIPPED for model: {} after {} errors", transactionId, model, errors);
            }
            throw e;
        } finally {
            activeConnectionsMap.get(model).decrementAndGet();
            updateLatestStatus(model);
        }
    }
    
    public Map<String, Object> generateOpenAiProxy(Map<String, Object> requestBody, String requester, String transactionId, boolean isCoding) {
        List<String> targetModels = isCoding ? codingModels : reasoningModels;
        
        List<String> upModels = latestStatusMap.values().stream()
                .filter(s -> targetModels.contains(s.model()))
                .filter(ModelStatus::isUp)
                .filter(s -> !s.circuitOpen())
                .sorted((a, b) -> Double.compare(calculateRoutingScore(a.model()), calculateRoutingScore(b.model())))
                .map(ModelStatus::model)
                .collect(Collectors.toList());
                
        List<String> downModels = latestStatusMap.values().stream()
                .filter(s -> targetModels.contains(s.model()))
                .filter(status -> !status.isUp() || status.circuitOpen())
                .map(ModelStatus::model)
                .collect(Collectors.toList());

        for (String model : upModels) {
            try {
                return executeWithTelemetryOpenAi(model, requestBody, "UP", requester, transactionId);
            } catch (Exception e) {
                log.warn("[TxID: {}] UP Proxy Model {} failed. Error: {}", transactionId, model, e.getMessage());
            }
        }
        
        for (String model : downModels) {
            try {
                return executeWithTelemetryOpenAi(model, requestBody, "DOWN", requester, transactionId);
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
        try {
            Map<String, Object> overrideBody = new java.util.HashMap<>(requestBody);
            overrideBody.put("model", model);
            overrideBody.put("stream", false); // Force non-streaming response for proxy
            overrideBody.remove("stream_options"); // Nvidia API rejects this if stream is false
            
            // Reinforce tool calling instructions in system prompt if tools are present
            if (overrideBody.containsKey("tools")) {
                Object msgsObj = overrideBody.get("messages");
                if (msgsObj instanceof List<?> msgsList) {
                    List<Map<String, Object>> newMsgs = new ArrayList<>();
                    boolean reminderAdded = false;
                    for (Object m : msgsList) {
                        if (m instanceof Map<?, ?> msgMap) {
                            Map<String, Object> copyMsg = new HashMap<>((Map<String, Object>) msgMap);
                            if ("system".equals(copyMsg.get("role")) && !reminderAdded) {
                                String content = (String) copyMsg.get("content");
                                String promptReminder = "\n\n[TOOL CALLING MANDATE]: You MUST strictly follow the exact property names in each tool schema. For the 'editor' tool, you MUST use snake_case parameter names: 'path', 'new_text', 'old_text', and 'insert_line'. NEVER use camelCase like 'newText' or 'oldText'. For 'run_commands', 'commands' must be an array of string commands.";
                                copyMsg.put("content", (content != null ? content : "") + promptReminder);
                                reminderAdded = true;
                            }
                            newMsgs.add(copyMsg);
                        }
                    }
                    overrideBody.put("messages", newMsgs);
                }
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
                    .timeout(java.time.Duration.ofSeconds(180))
                    .block();
                    
            long timeTaken = System.currentTimeMillis() - start;
            long totalTokens = 0;
            
            if (response != null && response.containsKey("usage")) {
                Map<String, Object> usage = (Map<String, Object>) response.get("usage");
                if (usage != null && usage.containsKey("total_tokens")) {
                    totalTokens = ((Number) usage.get("total_tokens")).longValue();
                }
            }
            
            modelUsageMap.get(model).addAndGet(totalTokens);
            requesterUsageMap.computeIfAbsent(requester, k -> new AtomicLong(0)).addAndGet(totalTokens);
            
            if (timeTaken > 0 && totalTokens > 0) {
                double currentTps = (totalTokens * 1000.0) / timeTaken;
                tpsMap.compute(model, (k, oldTps) -> oldTps == 0.0 ? currentTps : (0.2 * currentTps + 0.8 * oldTps));
            }
            
            consecutiveErrorsMap.get(model).set(0);
            circuitBreakerMap.put(model, false);
            
            log.info("[TxID: {}] Successfully proxied response using {} model: {} in {}ms", transactionId, state, model, timeTaken);
            
            if (response != null) {
                response.put("id", transactionId);
                response.put("model", model);
                toolCallNormalizer.normalizeToolCalls(response, requestBody, transactionId);
            }
            return response;
            
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            log.error("[TxID: {}] Nvidia API returned {}: {}", transactionId, e.getStatusCode(), e.getResponseBodyAsString());
            int errors = consecutiveErrorsMap.get(model).incrementAndGet();
            if (errors >= CIRCUIT_BREAKER_THRESHOLD) {
                circuitBreakerMap.put(model, true);
                log.error("[TxID: {}] CIRCUIT BREAKER TRIPPED for model: {} after {} errors", transactionId, model, errors);
            }
            throw e;
        } catch (Exception e) {
            int errors = consecutiveErrorsMap.get(model).incrementAndGet();
            if (errors >= CIRCUIT_BREAKER_THRESHOLD) {
                circuitBreakerMap.put(model, true);
                log.error("[TxID: {}] CIRCUIT BREAKER TRIPPED for model: {} after {} errors", transactionId, model, errors);
            }
            throw e;
        } finally {
            activeConnectionsMap.get(model).decrementAndGet();
            updateLatestStatus(model);
        }
    }
    
    public Map<String, Long> getRequesterUsage() {
        return requesterUsageMap.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
    }
    
    private void updateLatestStatus(String model) {
        latestStatusMap.computeIfPresent(model, (k, current) -> 
            new ModelStatus(
                current.model(), current.categories(), current.isUp(), current.latencyMs(), 
                current.lastChecked(), current.errorMessage(), 
                current.history(), modelUsageMap.get(model).get(),
                activeConnectionsMap.get(model).get(),
                tpsMap.get(model),
                circuitBreakerMap.get(model)
            )
        );
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
                .timeout(java.time.Duration.ofSeconds(180))
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

    @Scheduled(fixedRate = 60000)
    public void pingModels() {
        log.debug("Starting scheduled health ping for all models...");
        Instant now = Instant.now();
        allModels.parallelStream().forEach(model -> {
            long startTime = System.currentTimeMillis();
            boolean isUp = false;
            long latency = 0;
            String errorMsg = null;
            
            try {
                performLlmCall(model, "ping");
                latency = System.currentTimeMillis() - startTime;
                isUp = true;
                
                // Successful ping resets the circuit breaker!
                consecutiveErrorsMap.get(model).set(0);
                circuitBreakerMap.put(model, false);
                
                log.trace("Ping successful for model: {} ({}ms)", model, latency);
            } catch (Exception e) {
                latency = System.currentTimeMillis() - startTime;
                errorMsg = e.getMessage();
                log.trace("Ping failed for model: {} - {}", model, errorMsg);
            }
            
            PingResult result = new PingResult(now, isUp, latency);
            ConcurrentLinkedDeque<PingResult> history = modelHistoryMap.get(model);
            history.addLast(result);
            while (history.size() > MAX_HISTORY_SIZE) {
                history.pollFirst();
            }
            List<String> categories = new ArrayList<>();
            if (reasoningModels.contains(model)) categories.add("Reasoning");
            if (codingModels.contains(model)) categories.add("Coding");
            if (categories.isEmpty()) categories.add("Unknown");
            
            latestStatusMap.put(model, new ModelStatus(
                model, categories, isUp, latency, now, errorMsg, new ArrayList<>(history), 
                modelUsageMap.get(model).get(),
                activeConnectionsMap.get(model).get(),
                tpsMap.get(model),
                circuitBreakerMap.get(model)
            ));
        });
        // Broadcast updates to all connected SSE clients
        notifyStatusChange();
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
        } catch (IOException e) {
            statusEmitters.remove(emitter);
        }

        return emitter;
    }

    private void broadcastStatusUpdate() {
        List<ModelStatus> statuses = getModelStatuses();
        for (SseEmitter emitter : statusEmitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("status")
                        .data(statuses));
            } catch (IOException e) {
                emitter.completeWithError(e);
                statusEmitters.remove(emitter);
            }
        }
    }

    // Call this after pingModels() updates the status
    private void notifyStatusChange() {
        broadcastStatusUpdate();
    }

    // Manual ping for a single model
    public PingResult pingModel(String model) {
        long startTime = System.currentTimeMillis();
        Instant now = Instant.now();
        boolean isUp = false;
        long latency = 0;
        String errorMsg = null;

        try {
            performLlmCall(model, "ping");
            latency = System.currentTimeMillis() - startTime;
            isUp = true;

            // Successful ping resets the circuit breaker!
            consecutiveErrorsMap.get(model).set(0);
            circuitBreakerMap.put(model, false);

            log.trace("Manual ping successful for model: {} ({}ms)", model, latency);
        } catch (Exception e) {
            latency = System.currentTimeMillis() - startTime;
            errorMsg = e.getMessage();
            log.trace("Manual ping failed for model: {} - {}", model, errorMsg);
        }

        PingResult result = new PingResult(now, isUp, latency);
        ConcurrentLinkedDeque<PingResult> history = modelHistoryMap.get(model);
        history.addLast(result);
        if (history.size() > MAX_HISTORY_SIZE) {
            history.pollFirst();
        }
        List<String> categories = new ArrayList<>();
        if (reasoningModels.contains(model)) categories.add("Reasoning");
        if (codingModels.contains(model)) categories.add("Coding");
        if (categories.isEmpty()) categories.add("Unknown");

        latestStatusMap.put(model, new ModelStatus(
            model, categories, isUp, latency, now, errorMsg, new ArrayList<>(history),
            modelUsageMap.get(model).get(),
            activeConnectionsMap.get(model).get(),
            tpsMap.get(model),
            circuitBreakerMap.get(model)
        ));

        // Broadcast updates to all connected SSE clients
        notifyStatusChange();

        return result;
    }

    // Reset circuit breaker for a single model
    public void resetCircuitBreaker(String model) {
        if (!circuitBreakerMap.containsKey(model)) {
            throw new IllegalArgumentException("Model not found: " + model);
        }
        consecutiveErrorsMap.get(model).set(0);
        circuitBreakerMap.put(model, false);
        log.info("Circuit breaker manually reset for model: {}", model);
        
        // Update status to reflect the change
        updateLatestStatus(model);
        notifyStatusChange();
    }
}
