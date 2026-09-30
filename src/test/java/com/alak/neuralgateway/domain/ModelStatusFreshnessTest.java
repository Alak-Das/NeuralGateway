package com.alak.neuralgateway.domain;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelStatusFreshnessTest {
    @Test
    void statusFreshIsTrueForChecksWithinTwentyFourHours() {
        ModelStatus status = statusCheckedAt(Instant.now().minus(Duration.ofHours(23)));

        assertTrue(status.isStatusFresh());
    }

    @Test
    void statusFreshIsFalseForMissingOldOrFutureChecks() {
        assertFalse(statusCheckedAt(null).isStatusFresh());
        assertFalse(statusCheckedAt(Instant.now().minus(Duration.ofHours(25))).isStatusFresh());
        assertFalse(statusCheckedAt(Instant.now().plusSeconds(60)).isStatusFresh());
    }

    @Test
    void freshnessIsSerializedForApiAndSseConsumers() throws Exception {
        ModelStatus status = statusCheckedAt(Instant.now());

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(status);

        assertTrue(json.contains("\"statusFresh\":true"));
    }

    @Test
    void freshlyReconstructedStatusRetainsItsLastCheckedTimestamp() {
        Instant checkedAt = Instant.now().minusSeconds(60);
        ModelStatus original = statusCheckedAt(checkedAt);

        ModelStatus reconstructed = new ModelStatus(original.model(), original.categories(), original.isUp(),
                original.latencyMs(), original.lastChecked(), original.errorMessage(), original.history(),
                original.totalUses(), original.activeConnections(), original.tps(), original.circuitOpen(),
                original.provider(), original.priority());

        assertEquals(checkedAt, reconstructed.lastChecked());
        assertTrue(reconstructed.isStatusFresh());
    }

    private ModelStatus statusCheckedAt(Instant lastChecked) {
        return new ModelStatus("model", List.of(), true, 1, lastChecked, null,
                List.of(), 0, 0, 0, false, "provider", 1);
    }
}