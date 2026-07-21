package net.guesswhoami.maintenancebridge;

/**
 * Dropped as {@code request.json} by external tooling (e.g. the restart script) instead of
 * going through RCON: {@code {"maintenance":true,"reason":"Planned restart","minutes":15,"server":null}}.
 * A null/absent {@code server} targets the whole proxy; a server name targets that backend only.
 *
 * <p>Plain mutable class rather than a record for the same reason as {@link StatusFile}: Gson
 * 2.8.0 (bundled in Velocity 4.0.0) can't populate a record's final fields.
 */
final class MaintenanceRequest {

    private Boolean maintenance;
    private String reason;
    private Long minutes;
    private String server;

    Boolean maintenance() {
        return maintenance;
    }

    String reason() {
        return reason;
    }

    Long minutes() {
        return minutes;
    }

    String server() {
        return server;
    }

    void validate() {
        if (maintenance == null) {
            throw new IllegalArgumentException("\"maintenance\" is required");
        }
        if (minutes != null && minutes < 0) {
            throw new IllegalArgumentException("\"minutes\" must not be negative");
        }
    }
}
