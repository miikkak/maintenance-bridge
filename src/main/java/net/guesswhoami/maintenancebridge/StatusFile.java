package net.guesswhoami.maintenancebridge;

import java.time.Instant;
import java.util.Map;

/**
 * Snapshot written to {@code status.json} for external readers (scripts, website).
 *
 * <p>Deliberately a plain mutable class, not a record: Gson (bundled in Velocity 4.0.0)
 * reflectively sets fields on instances allocated via {@code Unsafe} - it cannot populate a
 * record's implicitly-final fields.
 */
final class StatusFile {

    private boolean maintenance;
    private String reason;
    private Long plannedEndsAtEpochSeconds;
    private Map<String, Boolean> servers;
    private String updated;

    StatusFile(
            final boolean maintenance,
            final String reason,
            final Long plannedEndsAtEpochSeconds,
            final Map<String, Boolean> servers,
            final String updated) {
        this.maintenance = maintenance;
        this.reason = reason;
        this.plannedEndsAtEpochSeconds = plannedEndsAtEpochSeconds;
        this.servers = servers;
        this.updated = updated;
    }

    static StatusFile now(
            final boolean maintenance,
            final String reason,
            final Long plannedEndsAtEpochSeconds,
            final Map<String, Boolean> servers) {
        return new StatusFile(maintenance, reason, plannedEndsAtEpochSeconds, servers, Instant.now().toString());
    }

    boolean maintenance() {
        return maintenance;
    }

    String reason() {
        return reason;
    }

    Long plannedEndsAtEpochSeconds() {
        return plannedEndsAtEpochSeconds;
    }

    Map<String, Boolean> servers() {
        return servers;
    }

    String updated() {
        return updated;
    }
}
