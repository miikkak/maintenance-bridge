package net.guesswhoami.maintenancebridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class MaintenanceStatusServiceTest {

    private final Instant now = Instant.parse("2026-07-21T18:00:00Z");

    @Test
    void turningOnWithMinutesSetsPlannedEndsAt() {
        final Long endsAt = MaintenanceStatusService.computePlannedEndsAt(true, 15L, now);

        assertEquals(now.plusSeconds(15 * 60L).getEpochSecond(), endsAt);
    }

    @Test
    void turningOnWithoutMinutesClearsPlannedEndsAt() {
        assertNull(MaintenanceStatusService.computePlannedEndsAt(true, null, now));
    }

    @Test
    void turningOffAlwaysClearsPlannedEndsAtEvenIfMinutesSent() {
        // A request like {"maintenance":false,"minutes":15} is a nonsensical caller error, but
        // status.json must never end up publishing maintenance=false with a stale ETA regardless.
        assertNull(MaintenanceStatusService.computePlannedEndsAt(false, 15L, now));
    }
}
