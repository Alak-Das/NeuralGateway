package com.example.llmservice;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class PayloadTelemetryServiceTest {

    @Test
    public void testSummarizeRequest_SummaryMode() {
        PayloadTelemetryService service = new PayloadTelemetryService("SUMMARY", 60);

        Map<String, Object> request = new HashMap<>();
        request.put("model", "nvidia/nemotron-3-ultra-550b-a55b");
        request.put("stream", false);
        request.put("max_tokens", 4096);
        request.put("temperature", 0.7);

        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", "You are an assistant. System prompt instructions here."),
                Map.of("role", "user", "content", "First user request"),
                Map.of("role", "assistant", "content", "First assistant answer"),
                Map.of("role", "tool", "content", "Tool execution output"),
                Map.of("role", "user", "content", "Please update the navbar with a clean theme toggle button in index.html")
        );
        request.put("messages", messages);

        List<Map<String, Object>> tools = List.of(
                Map.of("type", "function", "function", Map.of("name", "editor")),
                Map.of("type", "function", "function", Map.of("name", "run_commands")),
                Map.of("type", "function", "function", Map.of("name", "read_files")),
                Map.of("type", "function", "function", Map.of("name", "search_codebase")),
                Map.of("type", "function", "function", Map.of("name", "ask_question"))
        );
        request.put("tools", tools);

        String summary = service.summarizeRequest(request);
        assertNotNull(summary);

        assertTrue(summary.contains("model=nvidia/nemotron-3-ultra-550b-a55b"));
        assertTrue(summary.contains("stream=false"));
        assertTrue(summary.contains("max_tokens=4096"));
        assertTrue(summary.contains("messages=5 (system:1, user:2, assistant:1, tool:1"));
        assertTrue(summary.contains("lastUserMsg=\"Please update the navbar with a clean theme toggle button in...\""));
        assertTrue(summary.contains("tools=5 [editor, run_commands, read_files, +2 more]"));

        // Ensure single line (no raw newlines)
        assertFalse(summary.contains("\n"));
        assertFalse(summary.contains("\r"));
    }

    @Test
    public void testSummarizeRequest_NoneMode() {
        PayloadTelemetryService service = new PayloadTelemetryService("NONE", 60);
        Map<String, Object> request = Map.of("model", "test-model");
        assertNull(service.summarizeRequest(request));
    }

    @Test
    public void testSummarizeRequest_FullMode() {
        PayloadTelemetryService service = new PayloadTelemetryService("FULL", 60);
        Map<String, Object> request = Map.of("model", "test-model", "stream", false);
        String summary = service.summarizeRequest(request);
        assertNotNull(summary);
        assertTrue(summary.contains("\"model\":\"test-model\""));
    }

    @Test
    public void testSummarizeToolCallArgs() {
        PayloadTelemetryService service = new PayloadTelemetryService("SUMMARY", 60);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("path", "D:/WorkSpace/LLMService/src/main/resources/static/index.html");
        args.put("insert_line", 42);
        args.put("old_text", "short old text");
        // A long string of 300 characters
        String longText = "a".repeat(300);
        args.put("new_text", longText);
        args.put("commands", List.of("mvn clean", "mvn test", "docker ps", "docker logs"));

        String summary = service.summarizeToolCallArgs(args);
        assertNotNull(summary);

        assertTrue(summary.contains("path=\"D:/WorkSpace/LLMService/src/main/resources/static/index.html\""));
        assertTrue(summary.contains("insert_line=42"));
        assertTrue(summary.contains("old_text=\"short old text\""));
        // Long string should be truncated with character count indicator
        assertTrue(summary.contains("new_text=\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa...\" (300 chars)"));
        // Array with > 3 items summarized
        assertTrue(summary.contains("commands=[mvn clean, +3 items]"));
    }
}
