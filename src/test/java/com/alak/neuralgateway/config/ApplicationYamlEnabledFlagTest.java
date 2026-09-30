package com.alak.neuralgateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the shipped application.yml binds into {@link LlmProvidersProperties}
 * with every model carrying the new {@code enabled} flag (all currently {@code true}).
 */
class ApplicationYamlEnabledFlagTest {

    @Test
    void allThirteenModelsBindWithEnabledTrue() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));

        Map<String, Object> flattened = new HashMap<>();
        for (PropertySource<?> source : sources) {
            if (source instanceof EnumerablePropertySource<?> eps) {
                for (String name : eps.getPropertyNames()) {
                    flattened.put(name, eps.getProperty(name));
                }
            }
        }

        LlmProvidersProperties props = new Binder(new MapConfigurationPropertySource(flattened))
                .bind("llm", Bindable.of(LlmProvidersProperties.class))
                .orElseThrow(() -> new AssertionError("Failed to bind llm.* to LlmProvidersProperties"));

        Map<String, LlmProvidersProperties.ProviderConfig> providers = props.getProviders();
        assertNotNull(providers, "llm.providers must bind from application.yml");
        assertEquals(3, providers.size(), "Expected nvidia, explabs and antseed providers");

        long totalModels = providers.values().stream()
                .mapToLong(p -> p.getModels() == null ? 0 : p.getModels().size())
                .sum();
        assertEquals(13, totalModels, "Expected 13 models total in application.yml");

        providers.forEach((providerId, providerConfig) -> {
            assertNotNull(providerConfig.getModels(), providerId + " must declare models");
            for (LlmProvidersProperties.ModelConfig model : providerConfig.getModels()) {
                assertNotNull(model.getId(), providerId + " model must have an id");
                assertTrue(model.isEnabled(),
                        () -> "Model '" + model.getId() + "' must bind enabled: true");
            }
        });
    }
}
