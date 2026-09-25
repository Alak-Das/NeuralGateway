package com.example.llmservice;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
public class LlmController {
    
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
    private final NvidiaLlmService llmService;

    public LlmController(NvidiaLlmService llmService) {
        this.llmService = llmService;
    }

    @GetMapping("/models/status")
    public List<ModelStatus> getStatus() {
        return llmService.getModelStatuses();
    }
    
    @GetMapping("/requesters/status")
    public java.util.Map<String, Long> getRequesterStatus() {
        return llmService.getRequesterUsage();
    }

    @GetMapping(value = "/models/status/stream", produces = "text/event-stream")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter streamModelStatus() {
        return llmService.subscribeToStatusUpdates();
    }

    @PostMapping("/models/ping")
    public PingResult pingModel(@RequestParam String model) {
        return llmService.pingModel(model);
    }

    @PostMapping("/models/circuit-reset")
    public void resetCircuitBreaker(@RequestParam String model) {
        llmService.resetCircuitBreaker(model);
    }

    @PostMapping({"/coding/chat/completions", "/coding/v1/chat/completions"})
    public org.springframework.http.ResponseEntity<?> generateCodingOpenAi(jakarta.servlet.http.HttpServletRequest httpRequest,
                                                              @RequestBody java.util.Map<String, Object> request, 
                                                              @RequestHeader(value = "X-Requester", defaultValue = "cline-proxy") String requester) {
        String transactionId = java.util.UUID.randomUUID().toString();
        log.info("[TxID: {}] Received OpenAI-compatible Coding proxy request to exact endpoint '{}' from '{}'", transactionId, httpRequest.getRequestURI(), requester);
        
        long start = System.currentTimeMillis();
        java.util.Map<String, Object> response = llmService.generateOpenAiProxy(request, requester, transactionId, "coding");
        log.info("[TxID: {}] Coding proxy request completed in {}ms", transactionId, (System.currentTimeMillis() - start));
        
        return formatOpenAiResponse(request, response);
    }

    @PostMapping({"/reasoning/chat/completions", "/reasoning/v1/chat/completions"})
    public org.springframework.http.ResponseEntity<?> generateReasoningOpenAi(jakarta.servlet.http.HttpServletRequest httpRequest,
                                                                 @RequestBody java.util.Map<String, Object> request, 
                                                                 @RequestHeader(value = "X-Requester", defaultValue = "cline-proxy") String requester) {
        String transactionId = java.util.UUID.randomUUID().toString();
        log.info("[TxID: {}] Received OpenAI-compatible Reasoning proxy request to exact endpoint '{}' from '{}'", transactionId, httpRequest.getRequestURI(), requester);
        
        long start = System.currentTimeMillis();
        java.util.Map<String, Object> response = llmService.generateOpenAiProxy(request, requester, transactionId, "reasoning");
        log.info("[TxID: {}] Reasoning proxy request completed in {}ms", transactionId, (System.currentTimeMillis() - start));
        
        return formatOpenAiResponse(request, response);
    }

    @PostMapping({"/vision/chat/completions", "/vision/v1/chat/completions"})
    public org.springframework.http.ResponseEntity<?> generateVisionOpenAi(jakarta.servlet.http.HttpServletRequest httpRequest,
                                                              @RequestBody java.util.Map<String, Object> request, 
                                                              @RequestHeader(value = "X-Requester", defaultValue = "cline-proxy") String requester) {
        String transactionId = java.util.UUID.randomUUID().toString();
        log.info("[TxID: {}] Received OpenAI-compatible Vision proxy request to exact endpoint '{}' from '{}'", transactionId, httpRequest.getRequestURI(), requester);
        
        long start = System.currentTimeMillis();
        java.util.Map<String, Object> response = llmService.generateOpenAiProxy(request, requester, transactionId, "vision");
        log.info("[TxID: {}] Vision proxy request completed in {}ms", transactionId, (System.currentTimeMillis() - start));
        
        return formatOpenAiResponse(request, response);
    }

    private org.springframework.http.ResponseEntity<?> formatOpenAiResponse(java.util.Map<String, Object> request, java.util.Map<String, Object> response) {
        if (Boolean.TRUE.equals(request.get("stream"))) {
            try {
                java.util.List<java.util.Map<String, Object>> choices = (java.util.List<java.util.Map<String, Object>>) response.get("choices");
                java.util.Map<String, Object> message = (choices != null && !choices.isEmpty()) ? (java.util.Map<String, Object>) choices.get(0).get("message") : null;
                com.fasterxml.jackson.databind.ObjectMapper mapper = MAPPER;
                
                // Chunk 1: Content & Tool Calls
                java.util.Map<String, Object> delta = new java.util.HashMap<>();
                delta.put("role", "assistant");
                if (message != null && message.containsKey("content")) {
                    delta.put("content", message.get("content"));
                }
                boolean hasToolCalls = false;
                if (message != null && message.containsKey("tool_calls")) {
                    java.util.List<java.util.Map<String, Object>> originalToolCalls = (java.util.List<java.util.Map<String, Object>>) message.get("tool_calls");
                    if (originalToolCalls != null && !originalToolCalls.isEmpty()) {
                        hasToolCalls = true;
                        java.util.List<java.util.Map<String, Object>> streamToolCalls = new java.util.ArrayList<>();
                        for (int i = 0; i < originalToolCalls.size(); i++) {
                            java.util.Map<String, Object> tc = new java.util.HashMap<>(originalToolCalls.get(i));
                            tc.put("index", i);
                            
                            java.util.Map<String, Object> function = (java.util.Map<String, Object>) tc.get("function");
                            if (function != null && function.containsKey("arguments")) {
                                Object args = function.get("arguments");
                                if (!(args instanceof String)) {
                                    java.util.Map<String, Object> newFunction = new java.util.HashMap<>(function);
                                    newFunction.put("arguments", mapper.writeValueAsString(args));
                                    tc.put("function", newFunction);
                                }
                            }
                            
                            streamToolCalls.add(tc);
                        }
                        delta.put("tool_calls", streamToolCalls);
                    }
                }
                
                java.util.Map<String, Object> chunkChoice1 = new java.util.HashMap<>();
                chunkChoice1.put("index", 0);
                chunkChoice1.put("delta", delta);
                chunkChoice1.put("finish_reason", null);
                
                java.util.Map<String, Object> chunk1 = new java.util.HashMap<>();
                chunk1.put("id", response.get("id"));
                chunk1.put("object", "chat.completion.chunk");
                chunk1.put("created", response.get("created"));
                chunk1.put("model", response.get("model"));
                chunk1.put("choices", java.util.List.of(chunkChoice1));
                
                // Chunk 2: Finish Reason (tool_calls if tools called, otherwise stop)
                java.util.Map<String, Object> chunkChoice2 = new java.util.HashMap<>();
                chunkChoice2.put("index", 0);
                chunkChoice2.put("delta", new java.util.HashMap<>());
                chunkChoice2.put("finish_reason", hasToolCalls ? "tool_calls" : "stop");
                
                java.util.Map<String, Object> chunk2 = new java.util.HashMap<>(chunk1);
                chunk2.put("choices", java.util.List.of(chunkChoice2));
                
                String sse = "data: " + mapper.writeValueAsString(chunk1) + "\n\n" +
                             "data: " + mapper.writeValueAsString(chunk2) + "\n\n";
                             
                // Chunk 3: Usage (if requested)
                java.util.Map<String, Object> streamOptions = (java.util.Map<String, Object>) request.get("stream_options");
                if (streamOptions != null && Boolean.TRUE.equals(streamOptions.get("include_usage")) && response.containsKey("usage")) {
                    java.util.Map<String, Object> chunk3 = new java.util.HashMap<>(chunk1);
                    chunk3.put("choices", java.util.List.of()); // OpenAI usage chunks have empty choices
                    chunk3.put("usage", response.get("usage"));
                    sse += "data: " + mapper.writeValueAsString(chunk3) + "\n\n";
                }
                
                sse += "data: [DONE]\n\n";
                
                return org.springframework.http.ResponseEntity.ok()
                        .header("Content-Type", "text/event-stream")
                        .body(sse);
            } catch (Exception e) {
                log.error("Failed to convert to SSE chunk", e);
                return org.springframework.http.ResponseEntity.internalServerError().build();
            }
        }
        
        return org.springframework.http.ResponseEntity.ok(response);
    }

    @ExceptionHandler(org.springframework.web.reactive.function.client.WebClientResponseException.class)
    public org.springframework.http.ResponseEntity<?> handleWebClientResponseException(org.springframework.web.reactive.function.client.WebClientResponseException e) {
        String body = e.getResponseBodyAsString();
        String message = (body != null && !body.isBlank()) ? body : ("Upstream API error: " + e.getStatusCode());
        Map<String, Object> error = Map.of(
            "message", message,
            "type", e.getStatusCode().is4xxClientError() ? "invalid_request_error" : "upstream_error",
            "code", String.valueOf(e.getStatusCode().value())
        );
        return org.springframework.http.ResponseEntity.status(e.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", error));
    }

    @ExceptionHandler(Exception.class)
    public org.springframework.http.ResponseEntity<?> handleGeneralException(Exception e) {
        log.error("Gateway error: {}", e.getMessage(), e);
        Map<String, Object> error = Map.of(
            "message", e.getMessage() != null ? e.getMessage() : "Internal Gateway Error",
            "type", "gateway_error",
            "code", "model_unavailable"
        );
        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", error));
    }
}
