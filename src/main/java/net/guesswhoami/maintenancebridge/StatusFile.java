package net.guesswhoami.maintenancebridge;

import java.time.Instant;
import java.util.Map;

/**
 * Snapshot written to {@code status.json} for external readers (scripts, website).
 *
 * <p>A plain mutable class, not a record — historically because Gson's default deserialization
 * path (Unsafe-allocated instances with reflective field-set) can't populate a record's
 * implicitly-final fields, but Gson has supported records via a dedicated canonical-constructor
 * path since 2.10, and the actually-bundled version (2.14.0, see the {@code gson} dependency
 * comment in {@code build.gradle.kts}) postdates that. Left as a plain class for now as a style
 * choice, not a technical constraint; converting to a record would be a reasonable follow-up.
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
