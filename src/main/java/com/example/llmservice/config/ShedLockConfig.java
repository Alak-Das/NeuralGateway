package com.example.llmservice.config;

import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import net.javacrumbs.shedlock.core.LockProvider;

/**
 * Configuration for ShedLock distributed scheduling using Redis.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "3m")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        return new RedisLockProvider(connectionFactory, "llm-gateway-shedlock");
    }
}
