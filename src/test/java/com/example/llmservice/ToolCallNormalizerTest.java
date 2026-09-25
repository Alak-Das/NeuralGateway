package com.example.llmservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class ToolCallNormalizerTest {

    private ToolCallNormalizer normalizer;

    @BeforeEach
    public void setup() {
        normalizer = new ToolCallNormalizer();
    }

    @Test
    public void testNormalizeEditorWithCamelCase() {
        Map<String, Object> originalArgs = new HashMap<>();
        originalArgs.put("path", "/src/main/resources/static/index.html");
        originalArgs.put("oldText", ".dashboard-header-left {");
        originalArgs.put("newText", ".dashboard-header-left-test {");

        Map<String, Object> normalized = normalizer.normalizeArgumentsMap(
                "editor",
                originalArgs,
                Map.of("path", Map.of("type", "string"),
                        "old_text", Map.of("type", "string"),
                        "new_text", Map.of("type", "string"),
                        "insert_line", Map.of("type", "integer")),
                false,
                "D:/WorkSpace/LLMService"
        );

        assertEquals("D:/WorkSpace/LLMService/src/main/resources/static/index.html", normalized.get("path"));
        assertEquals(".dashboard-header-left {", normalized.get("old_text"));
        assertEquals(".dashboard-header-left-test {", normalized.get("new_text"));
        assertNull(normalized.get("oldText"), "oldText should be removed for strict editor schema");
        assertNull(normalized.get("newText"), "newText should be removed for strict editor schema");
    }

    @Test
    public void testNormalizeEditorCreateFile() {
        Map<String, Object> originalArgs = new HashMap<>();
        originalArgs.put("path", "src/main/resources/static/index.html");
        originalArgs.put("newText", "<html><body>Hello</body></html>");

        Map<String, Object> normalized = normalizer.normalizeArgumentsMap(
                "editor",
                originalArgs,
                Map.of("path", Map.of("type", "string"),
                        "old_text", Map.of("type", "string"),
                        "new_text", Map.of("type", "string")),
                false,
                "D:/WorkSpace/LLMService"
        );

        assertEquals("D:/WorkSpace/LLMService/src/main/resources/static/index.html", normalized.get("path"));
        assertEquals("<html><body>Hello</body></html>", normalized.get("new_text"));
        assertNull(normalized.get("old_text"));
        assertNull(normalized.get("newText"));
    }

    @Test
    public void testNormalizeReplaceTextInFile() {
        Map<String, Object> originalArgs = new HashMap<>();
        originalArgs.put("path", "D:/WorkSpace/LLMService/src/main/resources/static/index.html");
        originalArgs.put("old_text", "old");
        originalArgs.put("new_text", "new");

        Map<String, Object> normalized = normalizer.normalizeArgumentsMap(
                "jetbrains-idea__replace_text_in_file",
                originalArgs,
                Map.of("pathInProject", Map.of("type", "string"),
                        "oldText", Map.of("type", "string"),
                        "newText", Map.of("type", "string")),
                false,
                "D:/WorkSpace/LLMService"
        );

        assertEquals("src/main/resources/static/index.html", normalized.get("pathInProject"));
        assertEquals("old", normalized.get("oldText"));
        assertEquals("new", normalized.get("newText"));
    }

    @Test
    public void testNormalizeRunCommandsString() {
        Map<String, Object> originalArgs = new HashMap<>();
        originalArgs.put("command", "mvn clean test");

        Map<String, Object> normalized = normalizer.normalizeArgumentsMap(
                "run_commands",
                originalArgs,
                Map.of("commands", Map.of("type", "array")),
                false,
                "D:/WorkSpace/LLMService"
        );

        assertTrue(normalized.get("commands") instanceof List<?>);
        assertEquals(List.of("mvn clean test"), normalized.get("commands"));
    }

    @Test
    public void testNormalizeReadFilesString() {
        Map<String, Object> originalArgs = new HashMap<>();
        originalArgs.put("file", "pom.xml");

        Map<String, Object> normalized = normalizer.normalizeArgumentsMap(
                "read_files",
                originalArgs,
                Map.of("files", Map.of("type", "array")),
                false,
                "D:/WorkSpace/LLMService"
        );

        assertEquals(List.of("pom.xml"), normalized.get("files"));
    }

    @Test
    public void testNormalizeEntireResponseStructure() {
        Map<String, Object> request = new HashMap<>();
        request.put("messages", List.of(
                Map.of("role", "system", "content", "Working Directory: D:/WorkSpace/LLMService")
        ));
        request.put("tools", List.of(
                Map.of("type", "function", "function", Map.of(
                        "name", "editor",
                        "parameters", Map.of(
                                "properties", Map.of(
                                        "path", Map.of("type", "string"),
                                        "old_text", Map.of("type", "string"),
                                        "new_text", Map.of("type", "string")
                                ),
                                "required", List.of("path", "new_text"),
                                "additionalProperties", false
                        )
                ))
        ));

        Map<String, Object> function = new HashMap<>();
        function.put("name", "editor");
        function.put("arguments", "{\"path\":\"/src/main/resources/static/index.html\",\"newText\":\"updated text\",\"oldText\":\"original text\"}");

        Map<String, Object> toolCall = new HashMap<>();
        toolCall.put("id", "call_123");
        toolCall.put("type", "function");
        toolCall.put("function", function);

        Map<String, Object> message = new HashMap<>();
        message.put("role", "assistant");
        message.put("tool_calls", List.of(toolCall));

        Map<String, Object> choice = new HashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", "stop");

        Map<String, Object> response = new HashMap<>();
        response.put("choices", List.of(choice));

        normalizer.normalizeToolCalls(response, request, "test-tx-1");

        assertEquals("tool_calls", choice.get("finish_reason"));
        String argsJson = (String) function.get("arguments");
        assertTrue(argsJson.contains("\"new_text\":\"updated text\""));
        assertTrue(argsJson.contains("\"old_text\":\"original text\""));
        assertFalse(argsJson.contains("\"newText\""));
        assertFalse(argsJson.contains("\"oldText\""));
        assertTrue(argsJson.contains("\"path\":\"D:/WorkSpace/LLMService/src/main/resources/static/index.html\""));
    }
}
