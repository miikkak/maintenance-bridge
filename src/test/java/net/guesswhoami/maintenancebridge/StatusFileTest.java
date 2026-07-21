package net.guesswhoami.maintenancebridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StatusFileTest {

    private final Gson gson = new Gson();

    @Test
    void roundTripsThroughJsonWithServers() {
        final StatusFile status =
                StatusFile.now(true, "Planned restart", 1_800_000_000L, Map.of("lobby", true, "survival", false));

        final StatusFile parsed = gson.fromJson(gson.toJson(status), StatusFile.class);

        assertEquals(status.maintenance(), parsed.maintenance());
        assertEquals(status.reason(), parsed.reason());
        assertEquals(status.plannedEndsAtEpochSeconds(), parsed.plannedEndsAtEpochSeconds());
        assertEquals(status.servers(), parsed.servers());
        assertTrue(parsed.updated().length() > 0);
    }

    @Test
    void nullReasonAndEtaSurviveRoundTrip() {
        final StatusFile status = StatusFile.now(false, null, null, Map.of());

        final StatusFile parsed = gson.fromJson(gson.toJson(status), StatusFile.class);

        assertNull(parsed.reason());
        assertNull(parsed.plannedEndsAtEpochSeconds());
    }
}
