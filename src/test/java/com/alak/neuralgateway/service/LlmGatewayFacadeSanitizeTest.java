package com.alak.neuralgateway.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LlmGatewayFacade#sanitizeRequest(Map)}:
 * normalisation of non-standard reasoning-effort parameters sent by
 * clients such as Cline, Roo Code, Cursor and Anthropic-based SDKs,
 * so that stricter upstream providers do not reject
 * the request with 400 wrong_api_format.
 */
public class LlmGatewayFacadeSanitizeTest {

    private LlmGatewayFacade facade;

    @BeforeEach
    public void setup() {
        // sanitizeRequest is a pure function of the request body and uses no
        // injected collaborators, so the facade can be built with nulls here.
        facade = new LlmGatewayFacade(null, null, null, null, null,
                null, null, null, null, null, null);
    }

    @Test
    public void testNullBodyIsIgnored() {
        assertDoesNotThrow(() -> facade.sanitizeRequest(null));
    }

    @Test
    public void testEmptyBodyIsIgnored() {
        Map<String, Object> body = new HashMap<>();
        facade.sanitizeRequest(body);
        assertTrue(body.isEmpty());
    }

    @Test
    public void testStandardBodyIsUntouched() {
        Map<String, Object> body = new HashMap<>();
        body.put("model", "mimo-v2.6-pro");
        body.put("messages", new ArrayList<>());
        facade.sanitizeRequest(body);
        assertEquals(2, body.size());
        assertFalse(body.containsKey("reasoning_effort"));
    }

    @Test
    public void testThinkingEffortXhighIsConvertedAndNormalized() {
        Map<String, Object> body = new HashMap<>();
        body.put("thinking_effort", "xhigh");

        facade.sanitizeRequest(body);

        assertFalse(body.containsKey("thinking_effort"), "thinking_effort must be removed");
        assertEquals("high", body.get("reasoning_effort"));
    }

    @Test
    public void testThinkingEffortDoesNotOverwriteExistingReasoningEffort() {
        Map<String, Object> body = new HashMap<>();
        body.put("thinking_effort", "xhigh");
        body.put("reasoning_effort", "low");

        facade.sanitizeRequest(body);

        assertFalse(body.containsKey("thinking_effort"));
        assertEquals("low", body.get("reasoning_effort"), "existing reasoning_effort must win");
    }

    @Test
    public void testNullThinkingEffortIsDropped() {
        Map<String, Object> body = new HashMap<>();
        body.put("thinking_effort", null);

        facade.sanitizeRequest(body);

        assertFalse(body.containsKey("thinking_effort"));
        assertFalse(body.containsKey("reasoning_effort"));
    }

    @Test
    public void testAnthropicThinkingBudgetHigh() {
        Map<String, Object> body = new HashMap<>();
        body.put("thinking", Map.of("type", "enabled", "budget_tokens", 10000));

        facade.sanitizeRequest(body);

        assertFalse(body.containsKey("thinking"));
        assertEquals("high", body.get("reasoning_effort"));
    }

    @Test
    public void testAnthropicThinkingBudgetMedium() {
        Map<String, Object> body = new HashMap<>();
        body.put("thinking", Map.of("type", "enabled", "budget_tokens", 3000));

        facade.sanitizeRequest(body);

        assertFalse(body.containsKey("thinking"));
        assertEquals("medium", body.get("reasoning_effort"));
    }

    @Test
    public void testAnthropicThinkingBudgetLow() {
        Map<String, Object> body = new HashMap<>();
        body.put("thinking", Map.of("type", "enabled", "budget_tokens", 1000));

        facade.sanitizeRequest(body);

        assertFalse(body.containsKey("thinking"));
        assertEquals("low", body.get("reasoning_effort"));
    }

    @Test
    public void testAnthropicThinkingWithoutBudgetIsDropped() {
        Map<String, Object> body = new HashMap<>();
        body.put("thinking", Map.of("type", "enabled"));
        body.put("messages", new ArrayList<>());

        facade.sanitizeRequest(body);

        assertFalse(body.containsKey("thinking"));
        assertFalse(body.containsKey("reasoning_effort"));
        assertTrue(body.containsKey("messages"));
    }

    @Test
    public void testReasoningEffortExactAliasesAreNormalized() {
        String[][] cases = {
                {"xhigh", "high"},
                {"max", "high"},
                {"maximum", "high"},
                {"very_high", "high"},
                {"very-high", "high"},
                {"extra_high", "high"},
                {"extra-high", "high"},
                {"HIGH", "high"},
                {"medium", "medium"},
                {"med", "medium"},
                {"mid", "medium"},
                {"moderate", "medium"},
                {"low", "low"},
                {"min", "low"},
                {"minimum", "low"},
                {"minimal", "low"},
                {"none", "none"},
                {"off", "none"},
                {"false", "none"},
                {"disabled", "none"},
        };

        for (String[] tc : cases) {
            Map<String, Object> body = new HashMap<>();
            body.put("reasoning_effort", tc[0]);
            facade.sanitizeRequest(body);
            assertEquals(tc[1], body.get("reasoning_effort"),
                    "effort '" + tc[0] + "' should normalise to '" + tc[1] + "'");
        }
    }

    @Test
    public void testReasoningEffortSubstringFallback() {
        // "ultra_high" is not an exact alias but contains "hi" -> high
        Map<String, Object> body = new HashMap<>();
        body.put("reasoning_effort", "ultra_high");

        facade.sanitizeRequest(body);

        assertEquals("high", body.get("reasoning_effort"));
    }

    @Test
    public void testUnsupportedReasoningEffortIsRemoved() {
        Map<String, Object> body = new HashMap<>();
        body.put("reasoning_effort", "banana");

        facade.sanitizeRequest(body);

        assertFalse(body.containsKey("reasoning_effort"),
                "unknown effort values must be removed to avoid 400 wrong_api_format");
    }

    @Test
    public void testNullReasoningEffortIsRemoved() {
        Map<String, Object> body = new HashMap<>();
        body.put("reasoning_effort", null);

        facade.sanitizeRequest(body);

        assertFalse(body.containsKey("reasoning_effort"));
    }

    @Test
    public void testCombinedNonStandardFieldsAllNormalised() {
        // Simulates a Cline-style payload mixing everything at once.
        Map<String, Object> body = new HashMap<>();
        body.put("model", "mimo-v2.6-pro");
        body.put("messages", new ArrayList<Map<String, Object>>());
        body.put("thinking_effort", "xhigh");
        body.put("thinking", Map.of("type", "enabled", "budget_tokens", 80000));

        facade.sanitizeRequest(body);

        assertFalse(body.containsKey("thinking_effort"));
        assertFalse(body.containsKey("thinking"));
        // thinking_effort is processed before 'thinking', so its converted value survives
        assertEquals("high", body.get("reasoning_effort"));
        // original fields preserved
        assertEquals("mimo-v2.6-pro", body.get("model"));
        assertTrue(body.get("messages") instanceof List<?>);
    }
}