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
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies that the shipped application.yml binds into {@link LlmProvidersProperties}
 * with the {@code enabled} flag respected: all nvidia/antseed models enabled, and the
 * two explabs preview models ({@code mimo-v2.6-pro}, {@code gpt-6-luna}) intentionally
 * disabled.
 */
class ApplicationYamlEnabledFlagTest {

    @Test
    void allModelsBindWithCorrectEnabledFlags() throws IOException {
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
        assertEquals(10, totalModels, "Expected 10 models total in application.yml");

        long enabledModels = providers.values().stream()
                .flatMap(p -> p.getModels() == null ? Stream.empty() : p.getModels().stream())
                .filter(LlmProvidersProperties.ModelConfig::isEnabled)
                .count();
        assertEquals(8, enabledModels, "Expected 8 enabled models");
        assertEquals(2, totalModels - enabledModels, "Expected 2 disabled models");

        Set<String> disabledIds = providers.values().stream()
                .flatMap(p -> p.getModels() == null ? Stream.empty() : p.getModels().stream())
                .filter(m -> !m.isEnabled())
                .map(LlmProvidersProperties.ModelConfig::getId)
                .collect(Collectors.toSet());
        assertEquals(Set.of("mimo-v2.6-pro", "gpt-6-luna"), disabledIds,
                "explabs preview models must stay disabled");

        providers.forEach((providerId, providerConfig) -> {
            assertNotNull(providerConfig.getModels(), providerId + " must declare models");
            for (LlmProvidersProperties.ModelConfig model : providerConfig.getModels()) {
                assertNotNull(model.getId(), providerId + " model must have an id");
            }
        });
    }
}
