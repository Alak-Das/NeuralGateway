package com.alak.neuralgateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.*;

@Service
public class AnthropicAdapterService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public Map<String, Object> convertToOpenAiRequest(Map<String, Object> anthropicReq) {
        Map<String, Object> openAiReq = new HashMap<>();
        
        // 1. Model
        if (anthropicReq.containsKey("model")) {
            openAiReq.put("model", anthropicReq.get("model"));
        }

        // 2. Stream
        if (anthropicReq.containsKey("stream")) {
            openAiReq.put("stream", anthropicReq.get("stream"));
        }

        // 3. Max tokens & Temperature
        if (anthropicReq.containsKey("max_tokens")) {
            openAiReq.put("max_tokens", anthropicReq.get("max_tokens"));
        }
        if (anthropicReq.containsKey("temperature")) {
            openAiReq.put("temperature", anthropicReq.get("temperature"));
        }

        // 4. Tools
        if (anthropicReq.containsKey("tools")) {
            List<Map<String, Object>> anthropicTools = (List<Map<String, Object>>) anthropicReq.get("tools");
            List<Map<String, Object>> openAiTools = new ArrayList<>();
            for (Map<String, Object> aTool : anthropicTools) {
                Map<String, Object> oTool = new HashMap<>();
                oTool.put("type", "function");
                Map<String, Object> function = new HashMap<>();
                function.put("name", aTool.get("name"));
                if (aTool.containsKey("description")) {
                    function.put("description", aTool.get("description"));
                }
                if (aTool.containsKey("input_schema")) {
                    function.put("parameters", aTool.get("input_schema"));
                }
                oTool.put("function", function);
                openAiTools.add(oTool);
            }
            openAiReq.put("tools", openAiTools);
        }
        
        if (anthropicReq.containsKey("tool_choice")) {
            Object tc = anthropicReq.get("tool_choice");
            if (tc instanceof Map) {
                Map<String, Object> tcMap = (Map<String, Object>) tc;
                if ("auto".equals(tcMap.get("type"))) {
                    openAiReq.put("tool_choice", "auto");
                } else if ("tool".equals(tcMap.get("type"))) {
                    openAiReq.put("tool_choice", Map.of("type", "function", "function", Map.of("name", tcMap.get("name"))));
                }
            }
        }

        // 5. Messages & System Prompt
        List<Map<String, Object>> openAiMessages = new ArrayList<>();
        
        if (anthropicReq.containsKey("system")) {
            Object sys = anthropicReq.get("system");
            String sysContent = "";
            if (sys instanceof String) {
                sysContent = (String) sys;
            } else if (sys instanceof List) {
                List<Map<String, Object>> sysList = (List<Map<String, Object>>) sys;
                StringBuilder sb = new StringBuilder();
                for (Map<String, Object> part : sysList) {
                    if ("text".equals(part.get("type")) && part.containsKey("text")) {
                        sb.append(part.get("text"));
                    }
                }
                sysContent = sb.toString();
            }
            if (!sysContent.isBlank()) {
                openAiMessages.add(Map.of("role", "system", "content", sysContent));
            }
        }

        if (anthropicReq.containsKey("messages")) {
            List<Map<String, Object>> anthropicMessages = (List<Map<String, Object>>) anthropicReq.get("messages");
            for (Map<String, Object> msg : anthropicMessages) {
                String role = (String) msg.get("role");
                Object contentObj = msg.get("content");
                
                if (contentObj instanceof String) {
                    openAiMessages.add(Map.of("role", role, "content", contentObj));
                } else if (contentObj instanceof List) {
                    List<Map<String, Object>> contentList = (List<Map<String, Object>>) contentObj;
                    
                    // Check if this contains tool uses or tool results
                    List<Map<String, Object>> toolCalls = new ArrayList<>();
                    StringBuilder textContent = new StringBuilder();
                    
                    for (Map<String, Object> part : contentList) {
                        String type = (String) part.get("type");
                        if ("text".equals(type)) {
                            textContent.append(part.get("text"));
                        } else if ("tool_use".equals(type)) {
                            Map<String, Object> toolCall = new HashMap<>();
                            toolCall.put("id", part.get("id"));
                            toolCall.put("type", "function");
                            Map<String, Object> function = new HashMap<>();
                            function.put("name", part.get("name"));
                            try {
                                function.put("arguments", MAPPER.writeValueAsString(part.get("input")));
                            } catch (JsonProcessingException e) {
                                function.put("arguments", "{}");
                            }
                            toolCall.put("function", function);
                            toolCalls.add(toolCall);
                        } else if ("tool_result".equals(type)) {
                            // Anthropic puts tool_result in the user's content array. 
                            // OpenAI needs a separate message for each tool result.
                            String contentStr = "";
                            Object resContent = part.get("content");
                            if (resContent instanceof String) {
                                contentStr = (String) resContent;
                            } else if (resContent instanceof List) {
                                try {
                                    contentStr = MAPPER.writeValueAsString(resContent);
                                } catch (Exception e) {}
                            }
                            openAiMessages.add(Map.of(
                                "role", "tool",
                                "tool_call_id", part.get("tool_use_id"),
                                "content", contentStr
                            ));
                        }
                    }
                    
                    if ("assistant".equals(role)) {
                        Map<String, Object> asstMsg = new HashMap<>();
                        asstMsg.put("role", "assistant");
                        if (textContent.length() > 0) {
                            asstMsg.put("content", textContent.toString());
                        }
                        if (!toolCalls.isEmpty()) {
                            asstMsg.put("tool_calls", toolCalls);
                        }
                        if (asstMsg.containsKey("content") || asstMsg.containsKey("tool_calls")) {
                            openAiMessages.add(asstMsg);
                        }
                    } else if ("user".equals(role)) {
                        if (textContent.length() > 0) {
                            openAiMessages.add(Map.of("role", "user", "content", textContent.toString()));
                        }
                    }
                }
            }
        }
        
        openAiReq.put("messages", openAiMessages);
        return openAiReq;
    }

    public Map<String, Object> convertToAnthropicResponse(Map<String, Object> openAiResp) {
        Map<String, Object> anthropicResp = new HashMap<>();
        anthropicResp.put("id", "msg_" + openAiResp.get("id"));
        anthropicResp.put("type", "message");
        anthropicResp.put("role", "assistant");
        anthropicResp.put("model", openAiResp.get("model"));
        
        List<Map<String, Object>> contentList = new ArrayList<>();
        String stopReason = "end_turn";

        if (openAiResp.containsKey("choices")) {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) openAiResp.get("choices");
            if (!choices.isEmpty()) {
                Map<String, Object> choice = choices.get(0);
                Map<String, Object> message = (Map<String, Object>) choice.get("message");
                
                String fr = (String) choice.get("finish_reason");
                if ("tool_calls".equals(fr)) {
                    stopReason = "tool_use";
                } else if ("length".equals(fr)) {
                    stopReason = "max_tokens";
                }
                
                if (message.containsKey("content") && message.get("content") != null) {
                    contentList.add(Map.of(
                        "type", "text",
                        "text", message.get("content")
                    ));
                }
                
                if (message.containsKey("tool_calls")) {
                    List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");
                    for (Map<String, Object> tc : toolCalls) {
                        Map<String, Object> function = (Map<String, Object>) tc.get("function");
                        Map<String, Object> input = new HashMap<>();
                        try {
                            input = MAPPER.readValue((String) function.get("arguments"), Map.class);
                        } catch (Exception e) {}
                        
                        contentList.add(Map.of(
                            "type", "tool_use",
                            "id", tc.get("id"),
                            "name", function.get("name"),
                            "input", input
                        ));
                    }
                    stopReason = "tool_use";
                }
            }
        }
        
        anthropicResp.put("content", contentList);
        anthropicResp.put("stop_reason", stopReason);
        anthropicResp.put("stop_sequence", null);
        
        Map<String, Object> usage = new HashMap<>();
        if (openAiResp.containsKey("usage")) {
            Map<String, Object> oUsage = (Map<String, Object>) openAiResp.get("usage");
            usage.put("input_tokens", oUsage.getOrDefault("prompt_tokens", 0));
            usage.put("output_tokens", oUsage.getOrDefault("completion_tokens", 0));
        } else {
            usage.put("input_tokens", 0);
            usage.put("output_tokens", 0);
        }
        anthropicResp.put("usage", usage);
        
        return anthropicResp;
    }

    public Flux<String> convertToAnthropicStream(Flux<String> openAiStream, String modelId) {
        return Flux.defer(() -> {
            AnthropicStreamingState state = new AnthropicStreamingState(modelId);
            return openAiStream.flatMapIterable(chunk -> {
                if ("[DONE]".equals(chunk.trim())) {
                    return state.finish();
                }
                try {
                    Map<String, Object> data = MAPPER.readValue(chunk, Map.class);
                    return state.process(data);
                } catch (Exception e) { System.err.println("AnthropicAdapter json parse error: " + e.getMessage() + " for chunk: " + chunk); return Collections.emptyList(); }
            });
        });
    }

    private static class AnthropicStreamingState {
        private final String modelId;
        private final String msgId = "msg_" + UUID.randomUUID().toString().replace("-", "");
        private boolean started = false;
        private int blockIndex = -1;
        private boolean inText = false;
        private boolean inTool = false;
        private boolean anyToolCalled = false;

        public AnthropicStreamingState(String modelId) {
            this.modelId = modelId;
        }

        public List<String> process(Map<String, Object> data) {
            List<String> events = new ArrayList<>();
            if (!started) {
                started = true;
                                    Map<String, Object> msgMap = new HashMap<>();
                    msgMap.put("id", msgId);
                    msgMap.put("type", "message");
                    msgMap.put("role", "assistant");
                    msgMap.put("content", Collections.emptyList());
                    msgMap.put("model", modelId != null ? modelId : "unknown");
                    msgMap.put("stop_reason", null);
                    msgMap.put("stop_sequence", null);
                    msgMap.put("usage", Map.of("input_tokens", 0, "output_tokens", 0));
                    events.add(formatEvent("message_start", Map.of(
                        "type", "message_start",
                        "message", msgMap
                    )));
            }

            if (!data.containsKey("choices")) return events;
            List<Map<String, Object>> choices = (List<Map<String, Object>>) data.get("choices");
            if (choices.isEmpty()) return events;
            
            Map<String, Object> choice = choices.get(0);
            Map<String, Object> delta = (Map<String, Object>) choice.get("delta");
            if (delta == null) return events;

            if (delta.containsKey("content") && delta.get("content") != null) {
                if (inTool) {
                    events.add(formatEvent("content_block_stop", Map.of("type", "content_block_stop", "index", blockIndex)));
                    inTool = false;
                }
                if (!inText) {
                    blockIndex++;
                    inText = true;
                    events.add(formatEvent("content_block_start", Map.of(
                        "type", "content_block_start",
                        "index", blockIndex,
                        "content_block", Map.of("type", "text", "text", "")
                    )));
                }
                events.add(formatEvent("content_block_delta", Map.of(
                    "type", "content_block_delta",
                    "index", blockIndex,
                    "delta", Map.of("type", "text_delta", "text", delta.get("content"))
                )));
            }

            if (delta.containsKey("tool_calls")) {
                List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) delta.get("tool_calls");
                for (Map<String, Object> tc : toolCalls) {
                    Map<String, Object> function = (Map<String, Object>) tc.get("function");
                    if (function == null) continue;
                    
                    if (tc.containsKey("id") && function.containsKey("name")) {
                        if (inText || inTool) {
                            events.add(formatEvent("content_block_stop", Map.of("type", "content_block_stop", "index", blockIndex)));
                        }
                        inText = false;
                        inTool = true;
                        anyToolCalled = true;
                        blockIndex++;
                        events.add(formatEvent("content_block_start", Map.of(
                            "type", "content_block_start",
                            "index", blockIndex,
                            "content_block", Map.of(
                                "type", "tool_use",
                                "id", tc.get("id"),
                                "name", function.get("name"),
                                "input", Collections.emptyMap()
                            )
                        )));
                    }
                    if (function.containsKey("arguments")) {
                        events.add(formatEvent("content_block_delta", Map.of(
                            "type", "content_block_delta",
                            "index", blockIndex,
                            "delta", Map.of("type", "input_json_delta", "partial_json", function.get("arguments"))
                        )));
                    }
                }
            }

            return events;
        }

        public List<String> finish() {
            List<String> events = new ArrayList<>();
            if (inText || inTool) {
                events.add(formatEvent("content_block_stop", Map.of("type", "content_block_stop", "index", blockIndex)));
            }
            events.add(formatEvent("message_delta", Map.of(
                "type", "message_delta",
                "delta", Map.of("stop_reason", anyToolCalled ? "tool_use" : "end_turn"),
                "usage", Map.of("output_tokens", 0)
            )));
            events.add(formatEvent("message_stop", Map.of("type", "message_stop")));
            return events;
        }

        private String formatEvent(String event, Map<String, Object> data) {
            try {
                return "event: " + event + "\ndata: " + MAPPER.writeValueAsString(data);
            } catch (JsonProcessingException e) {
                return "";
            }
        }
    }
}



