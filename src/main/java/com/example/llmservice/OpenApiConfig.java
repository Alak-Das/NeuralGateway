package com.example.llmservice;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI neuralGatewayOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Neural Gateway API")
                        .description("High-performance intelligent routing, load balancing, and failover gateway for NVIDIA NIM reasoning, coding, and vision LLMs.")
                        .version("1.0.0")
                        .contact(new Contact().name("Neural Gateway Support"))
                        .license(new License().name("Apache 2.0").url("https://www.apache.org/licenses/LICENSE-2.0")))
                .servers(List.of(
                        new Server().url("/").description("Default Neural Gateway Server")
                ))
                .tags(List.of(
                        new Tag().name("Coding Pipeline").description("OpenAI-compatible chat completions optimized for code generation and agentic IDE tools (Cline, Cursor, etc.)."),
                        new Tag().name("Reasoning Pipeline").description("OpenAI-compatible chat completions tuned for deep analytical reasoning, math, and architecture planning."),
                        new Tag().name("Vision Pipeline").description("OpenAI-compatible multimodal chat completions for image understanding and visual reasoning."),
                        new Tag().name("Fleet Health & Diagnostics").description("Real-time health status, telemetry, latency tracking, and circuit breaker management."),
                        new Tag().name("Telemetry").description("Client and agent request usage statistics.")
                ));
    }
}
