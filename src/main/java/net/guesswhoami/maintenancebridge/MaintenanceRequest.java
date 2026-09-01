package net.guesswhoami.maintenancebridge;

/**
 * Dropped as {@code request.json} by external tooling (e.g. the restart script) instead of
 * going through RCON: {@code {"maintenance":true,"reason":"Planned restart","minutes":15,"server":null}}.
 * A null/absent {@code server} targets the whole proxy; a server name targets that backend only.
 * {@code reason}/{@code minutes} are only valid on proxy-wide requests - status.json has no
 * per-server slot for them, so a per-server request setting either is rejected by {@link
 * #validate()}.
 *
 * <p>Plain mutable class rather than a record for the same reason as {@link StatusFile} — see
 * that class's Javadoc.
 */
final class MaintenanceRequest {

    // status.json only ever reports one global reason/ETA (there's no per-server metadata in the
    // schema), so a per-server request setting either would silently corrupt whole-proxy state
    // the next time it's read - reject it outright instead.
    private static final long MAX_MINUTES = 30 * 24 * 60L; // 30 days - generous, but bounded so
    // computePlannedEndsAt() can't be pushed into Instant's overflow range by a bogus value.

    // Minecraft's protocol caps string fields well under this (~32,767 bytes); reason ends up in
    // both the MOTD status ping and kick screens, so an oversized value would fail to encode and
    // disconnect/refuse otherwise-fine clients. Generous but bounded well below that limit.
    private static final int MAX_REASON_LENGTH = 1000;

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
        if (minutes != null && minutes > MAX_MINUTES) {
            throw new IllegalArgumentException("\"minutes\" must not exceed " + MAX_MINUTES);
        }
        if (reason != null && reason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("\"reason\" must not exceed " + MAX_REASON_LENGTH + " characters");
        }
        if (server != null && (reason != null || minutes != null)) {
            throw new IllegalArgumentException(
                    "\"reason\" and \"minutes\" are only supported for proxy-wide requests (server: null)");
        }
    }
}
