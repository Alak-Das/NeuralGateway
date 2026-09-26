package com.example.llmservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;

import java.util.Map;

// Use WireMock's post explicitly where needed
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@org.junit.jupiter.api.Disabled("Requires Docker daemon for Testcontainers - run locally")
public class GatewayIntegrationTest {

    @Container
    public static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    public static WireMockServer wireMockServer;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("nvidia.api.base-url", () -> wireMockServer.baseUrl());
        registry.add("nvidia.api.key", () -> "test-key");
    }

    @BeforeAll
    static void setupWiremock() {
        wireMockServer = new WireMockServer(0); // Dynamic port
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());
    }

    @AfterAll
    static void tearDownWiremock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void testCodingPipelineFailoverAndSuccess() throws Exception {
        // Mock the first model failing (502 Bad Gateway)
        WireMock.stubFor(WireMock.post(WireMock.urlEqualTo("/chat/completions"))
                .withRequestBody(WireMock.matchingJsonPath("$.model", WireMock.equalTo("nvidia/nemotron-3-ultra-550b-a55b")))
                .willReturn(WireMock.aResponse().withStatus(502)));

        // Mock the second model succeeding
        WireMock.stubFor(WireMock.post(WireMock.urlEqualTo("/chat/completions"))
                .withRequestBody(WireMock.matchingJsonPath("$.model", WireMock.equalTo("z-ai/glm-5.3")))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"choices\": [{\"message\": {\"content\": \"Success\"}}]}")));

        // Send request
        Map<String, Object> requestBody = Map.of(
                "messages", java.util.List.of(
                        Map.of("role", "user", "content", "Test")
                )
        );

        // The Gateway should transparently failover from nemotron to glm-5.3 and return 200 OK.
        mockMvc.perform(post("/api/coding/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isOk());
                
        // Verify wiremock calls
        WireMock.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo("/chat/completions")).withRequestBody(WireMock.matchingJsonPath("$.model", WireMock.equalTo("nvidia/nemotron-3-ultra-550b-a55b"))));
        WireMock.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo("/chat/completions")).withRequestBody(WireMock.matchingJsonPath("$.model", WireMock.equalTo("z-ai/glm-5.3"))));
    }
}
