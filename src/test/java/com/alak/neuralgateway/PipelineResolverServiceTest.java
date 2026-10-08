package com.alak.neuralgateway;

import com.alak.neuralgateway.domain.model.Model;
import com.alak.neuralgateway.domain.model.Model.Pipeline;
import com.alak.neuralgateway.service.ModelRegistry;
import com.alak.neuralgateway.service.PipelineResolverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class PipelineResolverServiceTest {

    private ModelRegistry modelRegistry;
    private PipelineResolverService resolver;

    @BeforeEach
    void setUp() {
        modelRegistry = Mockito.mock(ModelRegistry.class);
        resolver = new PipelineResolverService(modelRegistry);
    }

    @Test
    @DisplayName("Explicit virtual model 'coding' routes to CODING pipeline")
    void testVirtualModelCoding() {
        Map<String, Object> request = Map.of("model", "coding");
        var result = resolver.resolve(request, null, "GenericUser", null);
        assertEquals(Pipeline.CODING, result.pipeline());
        assertTrue(result.reason().contains("Virtual model"));
    }

    @Test
    @DisplayName("Explicit virtual model 'neural-reasoning' routes to REASONING pipeline")
    void testVirtualModelReasoning() {
        Map<String, Object> request = Map.of("model", "neural-reasoning");
        var result = resolver.resolve(request, null, "GenericUser", null);
        assertEquals(Pipeline.REASONING, result.pipeline());
    }

    @Test
    @DisplayName("Explicit virtual model 'vision' routes to VISION pipeline")
    void testVirtualModelVision() {
        Map<String, Object> request = Map.of("model", "vision");
        var result = resolver.resolve(request, null, "GenericUser", null);
        assertEquals(Pipeline.VISION, result.pipeline());
    }

    @Test
    @DisplayName("Multimodal payload with image_url routes to VISION pipeline even with generic model")
    void testMultimodalImageDetection() {
        Map<String, Object> imagePart = Map.of("type", "image_url", "image_url", Map.of("url", "https://example.com/test.png"));
        Map<String, Object> textPart = Map.of("type", "text", "text", "What is this?");
        Map<String, Object> message = Map.of("role", "user", "content", List.of(textPart, imagePart));
        Map<String, Object> request = Map.of("model", "auto", "messages", List.of(message));

        var result = resolver.resolve(request, null, "GenericUser", null);
        assertEquals(Pipeline.VISION, result.pipeline());
        assertTrue(result.reason().contains("Multimodal image payload"));
    }

    @Test
    @DisplayName("Inline base64 image data URL routes to VISION pipeline")
    void testBase64ImageDetection() {
        Map<String, Object> message = Map.of("role", "user", "content", "Inspect this image: data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAA...");
        Map<String, Object> request = Map.of("messages", List.of(message));

        var result = resolver.resolve(request, null, "GenericUser", null);
        assertEquals(Pipeline.VISION, result.pipeline());
    }

    @Test
    @DisplayName("Request with coding tools routes to CODING pipeline")
    void testCodingToolsDetection() {
        Map<String, Object> tool = Map.of(
                "type", "function",
                "function", Map.of("name", "write_to_file", "description", "Writes file to disk")
        );
        Map<String, Object> request = Map.of("tools", List.of(tool), "messages", List.of(Map.of("role", "user", "content", "help")));

        var result = resolver.resolve(request, null, "GenericUser", null);
        assertEquals(Pipeline.CODING, result.pipeline());
        assertTrue(result.reason().contains("IDE/Coding tools"));
    }

    @Test
    @DisplayName("Caller Cline or Cursor automatically routes to CODING pipeline")
    void testRequesterDetection() {
        Map<String, Object> request = Map.of("messages", List.of(Map.of("role", "user", "content", "explain this architecture")));
        
        var resultCline = resolver.resolve(request, null, "Cline", null);
        assertEquals(Pipeline.CODING, resultCline.pipeline());

        var resultCursor = resolver.resolve(request, null, "Cursor-Agent", null);
        assertEquals(Pipeline.CODING, resultCursor.pipeline());

        var resultArthaScope = resolver.resolve(request, null, "ArthaScope-Service", null);
        assertEquals(Pipeline.REASONING, resultArthaScope.pipeline());
    }

    @Test
    @DisplayName("Prompt containing programming code blocks routes to CODING pipeline")
    void testCodeIndicatorsInPrompt() {
        Map<String, Object> message = Map.of(
                "role", "user",
                "content", "Can you fix the bug in this function:\n```python\ndef calculate_sum(a, b):\n    return a - b\n```"
        );
        Map<String, Object> request = Map.of("messages", List.of(message));

        var result = resolver.resolve(request, null, "GenericUser", null);
        assertEquals(Pipeline.CODING, result.pipeline());
        assertTrue(result.reason().contains("Code blocks or programming keywords"));
    }

    @Test
    @DisplayName("Explicit scoped path override takes precedence")
    void testExplicitScopedOverride() {
        Map<String, Object> request = Map.of("model", "vision"); // model says vision
        // But path says coding
        var result = resolver.resolve(request, null, "GenericUser", "coding");
        assertEquals(Pipeline.CODING, result.pipeline());
        assertEquals("Explicit scoped endpoint path", result.reason());
    }

    @Test
    @DisplayName("Single-capability vision-only model resolves to VISION pipeline")
    void testSingleCapabilityModelResolution() {
        String visionModelId = "meta/llama-3.2-11b-vision-instruct";
        Model visionModel = Mockito.mock(Model.class);
        when(visionModel.getPipelines()).thenReturn(Set.of(Pipeline.VISION));
        when(modelRegistry.isValidModel(visionModelId)).thenReturn(true);
        when(modelRegistry.getModel(visionModelId)).thenReturn(Optional.of(visionModel));

        Map<String, Object> request = Map.of("model", visionModelId);
        var result = resolver.resolve(request, null, "GenericUser", null);
        assertEquals(Pipeline.VISION, result.pipeline());
        assertTrue(result.reason().contains("Single-capability model"));
    }

    @Test
    @DisplayName("Structured multimodal content parts with code indicators resolves to CODING pipeline")
    void testStructuredContentPartsWithCode() {
        Map<String, Object> textPart = Map.of(
                "type", "text",
                "text", "Please inspect this code:\n```java\npublic class Solution {}\n```"
        );
        Map<String, Object> message = Map.of(
                "role", "user",
                "content", List.of(textPart)
        );
        Map<String, Object> request = Map.of("messages", List.of(message));

        var result = resolver.resolve(request, null, "GenericUser", null);
        assertEquals(Pipeline.CODING, result.pipeline());
        assertTrue(result.reason().contains("Code blocks or programming keywords"));
    }
}
