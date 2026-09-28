package com.alak.neuralgateway;

import com.alak.neuralgateway.domain.health.HealthCheckResult;
import com.alak.neuralgateway.domain.ModelStatus;
import com.alak.neuralgateway.service.LlmGatewayFacade;
import com.alak.neuralgateway.service.PipelineResolverService;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@CrossOrigin(origins = "*")
public class LlmController {
    
    private final LlmGatewayFacade gatewayFacade;
    private final PipelineResolverService pipelineResolver;

    public LlmController(LlmGatewayFacade gatewayFacade, PipelineResolverService pipelineResolver) {
        this.gatewayFacade = gatewayFacade;
        this.pipelineResolver = pipelineResolver;
    }

    // ==========================================
    // OpenAI Standard API (/v1)
    // ==========================================

    @Operation(
        summary = "Create chat completion",
        description = "Standard OpenAI-compatible chat completions endpoint (`/v1/chat/completions`). " +
                      "Intelligently routes requests to the optimal model based on virtual model alias (coding, reasoning, vision, auto), " +
                      "multimodal image payload, IDE/coding tool definitions, or caller identification (Cline, Cursor, etc.). " +
                      "Supports SSE streaming, tool calls normalization, automatic failover, and EMA latency routing.",
        tags = {"OpenAI API"}
    )
    @PostMapping("/v1/chat/completions")
    public ResponseEntity<?> generateChatCompletion(
            HttpServletRequest httpRequest,
            @RequestBody Map<String, Object> request, 
            @Parameter(description = "Identifier of calling agent/client", example = "Cline")
            @RequestHeader(value = "X-Requester", defaultValue = "Anonymous") String requester) {
        var resolution = pipelineResolver.resolve(request, httpRequest, requester, null);
        return processRequest(httpRequest, request, requester, resolution.pipeline().name().toLowerCase(), resolution.reason());
    }

    @Operation(
        summary = "List models",
        description = "Standard OpenAI-compatible model listing endpoint (`/v1/models`). " +
                      "Returns virtual routing aliases (coding, reasoning, vision, auto) as well as all registered physical models in the fleet.",
        tags = {"OpenAI API"}
    )
    @GetMapping("/v1/models")
    public ResponseEntity<Map<String, Object>> listModels() {
        List<ModelStatus> statuses = gatewayFacade.getModelStatuses();
        List<Map<String, Object>> modelsData = new ArrayList<>();
        long createdTimestamp = System.currentTimeMillis() / 1000;

        // Virtual model aliases for standard OpenAI clients
        modelsData.add(createModelObject("coding", createdTimestamp));
        modelsData.add(createModelObject("neural-coding", createdTimestamp));
        modelsData.add(createModelObject("reasoning", createdTimestamp));
        modelsData.add(createModelObject("neural-reasoning", createdTimestamp));
        modelsData.add(createModelObject("vision", createdTimestamp));
        modelsData.add(createModelObject("neural-vision", createdTimestamp));
        modelsData.add(createModelObject("auto", createdTimestamp));
        modelsData.add(createModelObject("neural-gateway", createdTimestamp));

        // Physical fleet models
        for (ModelStatus status : statuses) {
            modelsData.add(createModelObject(status.model(), createdTimestamp));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("object", "list");
        response.put("data", modelsData);
        return ResponseEntity.ok(response);
    }

    @Operation(
        summary = "Retrieve model",
        description = "Standard OpenAI-compatible single model retrieval endpoint (`/v1/models/{modelId}`).",
        tags = {"OpenAI API"}
    )
    @GetMapping("/v1/models/{modelId}")
    public ResponseEntity<?> getModelDetails(
            @Parameter(description = "ID of the model to retrieve", example = "coding")
            @PathVariable String modelId) {
        long createdTimestamp = System.currentTimeMillis() / 1000;
        return ResponseEntity.ok(createModelObject(modelId, createdTimestamp));
    }

    private Map<String, Object> createModelObject(String id, long createdTimestamp) {
        Map<String, Object> model = new HashMap<>();
        model.put("id", id);
        model.put("object", "model");
        model.put("created", createdTimestamp);
        model.put("owned_by", "neural-gateway");
        return model;
    }

    // ==========================================
    // Fleet Health & Diagnostics (/api)
    // ==========================================

    @Operation(
        summary = "Get all model statuses",
        description = "Returns current operational status, EMA latency, active connections, total requests, and circuit breaker state across all registered models.",
        tags = {"Fleet Health & Diagnostics"}
    )
    @GetMapping("/api/models/status")
    public List<ModelStatus> getStatus() {
        return gatewayFacade.getModelStatuses();
    }

    @Operation(
        summary = "Stream model status updates",
        description = "Subscribes to a real-time Server-Sent Events (SSE) feed emitting status changes whenever background health checks finish.",
        tags = {"Fleet Health & Diagnostics"}
    )
    @GetMapping(value = "/api/models/status/stream", produces = "text/event-stream")
    public SseEmitter streamModelStatus() {
        return gatewayFacade.subscribeToStatusUpdates();
    }

    @Operation(
        summary = "Trigger on-demand health ping",
        description = "Sends an immediate health ping to a single model and updates its recorded status and latency in Redis.",
        tags = {"Fleet Health & Diagnostics"}
    )
    @PostMapping("/api/models/ping")
    public HealthCheckResult pingModel(
            @Parameter(description = "Exact name of model to ping", example = "moonshotai/kimi-k3")
            @RequestParam String model) {
        return gatewayFacade.pingModel(model);
    }

    @Operation(
        summary = "Reset circuit breaker",
        description = "Manually closes a tripped circuit breaker for the specified model, restoring it to active routing.",
        tags = {"Fleet Health & Diagnostics"}
    )
    @PostMapping("/api/models/circuit-reset")
    public void resetCircuitBreaker(
            @Parameter(description = "Exact name of model to reset", example = "moonshotai/kimi-k3")
            @RequestParam String model) {
        gatewayFacade.resetCircuitBreaker(model);
    }

    // ==========================================
    // Telemetry (/api)
    // ==========================================

    @Operation(
        summary = "Get requester statistics",
        description = "Returns request count statistics grouped by calling client or agent (based on X-Requester header).",
        tags = {"Telemetry"}
    )
    @GetMapping("/api/requesters/status")
    public List<Map<String, Object>> getRequesterStatus() {
        return gatewayFacade.getRequesterTelemetry();
    }

    // ==========================================
    // Internal Helper & Formatting
    // ==========================================

    private ResponseEntity<?> processRequest(
            HttpServletRequest httpRequest,
            Map<String, Object> request,
            String requester,
            String pipeline,
            String resolutionReason) {
        String transactionId = UUID.randomUUID().toString();
        
        org.slf4j.MDC.put("txId", transactionId);
        org.slf4j.MDC.put("requester", requester);
        
        try {
            log.info("Received OpenAI-compatible {} proxy request to '{}' [Resolution: {}]",
                    pipeline, httpRequest.getRequestURI(), resolutionReason != null ? resolutionReason : "Direct");

            long start = System.currentTimeMillis();
            Map<String, Object> response = gatewayFacade.processChatCompletion(request, requester, transactionId, pipeline);
            log.info("{} proxy request completed in {}ms", pipeline, (System.currentTimeMillis() - start));

            return formatOpenAiResponse(request, response, transactionId);
        } finally {
            org.slf4j.MDC.clear();
        }
    }

    private ResponseEntity<?> formatOpenAiResponse(Map<String, Object> request, Map<String, Object> response, String transactionId) {
        if (Boolean.TRUE.equals(request.get("stream"))) {
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
                @SuppressWarnings("unchecked")
                Map<String, Object> message = (choices != null && !choices.isEmpty()) ? (Map<String, Object>) choices.get(0).get("message") : null;
                ObjectMapper mapper = new ObjectMapper();
                
                // Chunk 1: Content & Tool Calls
                Map<String, Object> delta = new HashMap<>();
                delta.put("role", "assistant");
                if (message != null && message.containsKey("content")) {
                    delta.put("content", message.get("content"));
                }
                boolean hasToolCalls = false;
                if (message != null && message.containsKey("tool_calls")) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> originalToolCalls = (List<Map<String, Object>>) message.get("tool_calls");
                    if (originalToolCalls != null && !originalToolCalls.isEmpty()) {
                        hasToolCalls = true;
                        List<Map<String, Object>> streamToolCalls = new ArrayList<>();
                        for (int i = 0; i < originalToolCalls.size(); i++) {
                            Map<String, Object> tc = new HashMap<>(originalToolCalls.get(i));
                            tc.put("index", i);
                            
                            @SuppressWarnings("unchecked")
                            Map<String, Object> function = (Map<String, Object>) tc.get("function");
                            if (function != null && function.containsKey("arguments")) {
                                Object args = function.get("arguments");
                                if (!(args instanceof String)) {
                                    Map<String, Object> newFunction = new HashMap<>(function);
                                    newFunction.put("arguments", mapper.writeValueAsString(args));
                                    tc.put("function", newFunction);
                                }
                            }
                            
                            streamToolCalls.add(tc);
                        }
                        delta.put("tool_calls", streamToolCalls);
                    }
                }
                
                Map<String, Object> chunkChoice1 = new HashMap<>();
                chunkChoice1.put("index", 0);
                chunkChoice1.put("delta", delta);
                chunkChoice1.put("finish_reason", null);
                
                Map<String, Object> chunk1 = new HashMap<>();
                chunk1.put("id", response.get("id"));
                chunk1.put("object", "chat.completion.chunk");
                chunk1.put("created", response.get("created"));
                chunk1.put("model", response.get("model"));
                chunk1.put("choices", List.of(chunkChoice1));
                
                // Chunk 2: Finish Reason (tool_calls if tools called, otherwise stop)
                Map<String, Object> chunkChoice2 = new HashMap<>();
                chunkChoice2.put("index", 0);
                chunkChoice2.put("delta", new HashMap<>());
                chunkChoice2.put("finish_reason", hasToolCalls ? "tool_calls" : "stop");
                
                Map<String, Object> chunk2 = new HashMap<>(chunk1);
                chunk2.put("choices", List.of(chunkChoice2));
                
                String sse = "data: " + mapper.writeValueAsString(chunk1) + "\n\n" +
                             "data: " + mapper.writeValueAsString(chunk2) + "\n\n";
                             
                // Chunk 3: Usage (if requested)
                @SuppressWarnings("unchecked")
                Map<String, Object> streamOptions = (Map<String, Object>) request.get("stream_options");
                if (streamOptions != null && Boolean.TRUE.equals(streamOptions.get("include_usage")) && response.containsKey("usage")) {
                    Map<String, Object> chunk3 = new HashMap<>(chunk1);
                    chunk3.put("choices", List.of()); // OpenAI usage chunks have empty choices
                    chunk3.put("usage", response.get("usage"));
                    sse += "data: " + mapper.writeValueAsString(chunk3) + "\n\n";
                }
                
                sse += "data: [DONE]\n\n";
                
                return ResponseEntity.ok()
                        .header("Content-Type", "text/event-stream")
                        .header("X-Transaction-Id", transactionId)
                        .body(sse);
            } catch (Exception e) {
                log.error("Failed to convert to SSE chunk", e);
                return ResponseEntity.internalServerError().build();
            }
        }
        
        return ResponseEntity.ok()
                .header("X-Transaction-Id", transactionId)
                .body(response);
    }

    @ExceptionHandler(org.springframework.web.reactive.function.client.WebClientResponseException.class)
    public ResponseEntity<?> handleWebClientResponseException(org.springframework.web.reactive.function.client.WebClientResponseException e) {
        String body = e.getResponseBodyAsString();
        String message = (body != null && !body.isBlank()) ? body : ("Upstream API error: " + e.getStatusCode());
        Map<String, Object> error = Map.of(
            "message", message,
            "type", e.getStatusCode().is4xxClientError() ? "invalid_request_error" : "upstream_error",
            "code", String.valueOf(e.getStatusCode().value())
        );
        return ResponseEntity.status(e.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", error));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGeneralException(Exception e) {
        if (isClientDisconnect(e)) {
            log.debug("Client connection disconnected prematurely: {}", e.getMessage());
            return null;
        }

        log.error("Gateway error: {}", e.getMessage(), e);
        Map<String, Object> error = Map.of(
            "message", e.getMessage() != null ? e.getMessage() : "Internal Gateway Error",
            "type", "gateway_error",
            "code", "model_unavailable"
        );
        return ResponseEntity.status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", error));
    }

    private boolean isClientDisconnect(Throwable t) {
        if (t == null) return false;
        String msg = t.getMessage();
        if (msg != null && (msg.contains("Broken pipe") || msg.contains("Connection reset by peer"))) {
            return true;
        }
        String className = t.getClass().getSimpleName();
        if (className.contains("ClientAbortException") || className.contains("AsyncRequestNotUsableException")) {
            return true;
        }
        return isClientDisconnect(t.getCause());
    }
}
