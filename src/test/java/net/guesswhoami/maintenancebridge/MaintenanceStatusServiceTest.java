package net.guesswhoami.maintenancebridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

class MaintenanceStatusServiceTest {

    private final Instant now = Instant.parse("2026-07-21T18:00:00Z");

    @Test
    void writeAtomicProducesGroupAndWorldReadableFile(@TempDir final Path dataDirectory) throws IOException {
        final MaintenanceStatusService service =
                new MaintenanceStatusService(null, null, dataDirectory, NOPLogger.NOP_LOGGER);

        final Path target = dataDirectory.resolve("status.json");
        service.writeAtomic(target, "{}");

        final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(target);
        assertTrue(permissions.contains(PosixFilePermission.GROUP_READ), "expected group-readable: " + permissions);
        assertTrue(permissions.contains(PosixFilePermission.OTHERS_READ), "expected world-readable: " + permissions);
        assertEquals("{}", Files.readString(target));
    }

    @Test
    void writeAtomicLeavesNoTempFileBehindOnSuccess(@TempDir final Path dataDirectory) throws IOException {
        final MaintenanceStatusService service =
                new MaintenanceStatusService(null, null, dataDirectory, NOPLogger.NOP_LOGGER);

        service.writeAtomic(dataDirectory.resolve("status.json"), "{}");

        try (var files = Files.list(dataDirectory)) {
            final long tmpFiles = files.filter(p -> p.getFileName().toString().endsWith(".tmp")).count();
            assertEquals(0, tmpFiles, "expected no leftover .tmp files");
        }
    }

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
