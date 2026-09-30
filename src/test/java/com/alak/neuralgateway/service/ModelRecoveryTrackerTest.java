package com.alak.neuralgateway.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class ModelRecoveryTrackerTest {
    private static final Instant NOW = Instant.parse("2025-01-01T00:00:00Z");

    @Test
    void exponentialBackoffDoublesAndCapsAtTwoMinutes() {
        ModelRecoveryTracker tracker = trackerWithJitter(0.5);

        assertEquals(30_000, tracker.recordFailure("model").nextProbeAt().toEpochMilli() - NOW.toEpochMilli());
        assertEquals(60_000, tracker.recordFailure("model").nextProbeAt().toEpochMilli() - NOW.toEpochMilli());
        assertEquals(120_000, tracker.recordFailure("model").nextProbeAt().toEpochMilli() - NOW.toEpochMilli());
        assertEquals(120_000, tracker.recordFailure("model").nextProbeAt().toEpochMilli() - NOW.toEpochMilli());
    }

    @Test
    void jitterIsBoundedAndBackoffNeverExceedsCap() {
        ModelRecoveryTracker early = trackerWithJitter(0.0);
        ModelRecoveryTracker late = trackerWithJitter(1.0);

        assertEquals(24_000, early.recordFailure("model").nextProbeAt().toEpochMilli() - NOW.toEpochMilli());
        assertEquals(36_000, late.recordFailure("model").nextProbeAt().toEpochMilli() - NOW.toEpochMilli());

        ModelRecoveryTracker capped = trackerWithJitter(1.0);
        ModelRecoveryTracker.RecoveryState state = null;
        for (int i = 0; i < 8; i++) state = capped.recordFailure("model");
        assertNotNull(state);
        assertTrue(state.nextProbeAt().toEpochMilli() - NOW.toEpochMilli() <= ModelRecoveryTracker.MAX_BACKOFF_MS);
    }

    @Test
    void successfulRequestClearsFailureCountAndDueTime() {
        ModelRecoveryTracker tracker = trackerWithJitter(0.5);
        tracker.recordFailure("model");
        assertTrue(tracker.hasState("model"));

        tracker.recordSuccess("model");

        assertFalse(tracker.hasState("model"));
        assertNull(tracker.getState("model"));
        assertFalse(tracker.isDue("model", NOW.plusSeconds(300)));
        assertEquals(1, tracker.recordFailure("model").failureCount());
    }

    @Test
    void onlyTransientModelFailuresAreRecoveryCandidates() {
        assertTrue(ModelRecoveryTracker.isTransientModelFailure(
                new ProviderFailureException("temporary", 503, "provider", ProviderFailureType.TRANSIENT_UPSTREAM, null)));
        assertTrue(ModelRecoveryTracker.isTransientModelFailure(
                new LlmProviderClient.UpstreamServiceException("timeout", 504)));
        assertFalse(ModelRecoveryTracker.isTransientModelFailure(
                new ProviderFailureException("rate limit", 429, "provider", ProviderFailureType.RATE_LIMIT, null)));
        LlmProviderClient.RateLimitException rateLimit = new LlmProviderClient.RateLimitException(
                "rate limit", "provider", "key", "model");
        assertTrue(ModelRecoveryTracker.isProviderWideFailure(rateLimit));
        assertFalse(ModelRecoveryTracker.isTransientModelFailure(rateLimit));
        assertFalse(ModelRecoveryTracker.isTransientModelFailure(
                new ProviderFailureException("model missing", 404, "provider", ProviderFailureType.MODEL_UNAVAILABLE, null)));
        assertFalse(ModelRecoveryTracker.isTransientModelFailure(new IllegalArgumentException("bad request")));
    }

    private ModelRecoveryTracker trackerWithJitter(double jitter) {
        return new ModelRecoveryTracker(Clock.fixed(NOW, ZoneOffset.UTC), () -> jitter);
    }
}