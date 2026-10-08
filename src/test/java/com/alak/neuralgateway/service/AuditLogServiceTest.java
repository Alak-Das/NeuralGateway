package com.alak.neuralgateway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class AuditLogServiceTest {

    private final AuditLogService auditLogService = new AuditLogService();

    @Test
    @DisplayName("AuditLogService handles null values safely without NullPointerException")
    void testNullSafety() {
        assertDoesNotThrow(() -> {
            auditLogService.logRoutingDecision(null, null, null, null, null, 1);
            auditLogService.logFailover(null, null, null, null);
            auditLogService.logCircuitBreakerEvent(null, null, null);
            auditLogService.logModelStatusChange(null, true, 100, null);
            auditLogService.logRateLimitEvent(null, null, null, 0);
            auditLogService.logAuthFailure(null, null, 401, null);
        });
    }
}
