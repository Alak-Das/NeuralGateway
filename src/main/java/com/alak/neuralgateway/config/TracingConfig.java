package com.alak.neuralgateway.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

@Configuration
public class TracingConfig {
    @PostConstruct
    public void init() {
        Hooks.enableAutomaticContextPropagation();
    }
}
