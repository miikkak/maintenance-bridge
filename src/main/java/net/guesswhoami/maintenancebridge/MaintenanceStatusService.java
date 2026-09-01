package net.guesswhoami.maintenancebridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.velocitypowered.api.proxy.ProxyServer;
import eu.kennytv.maintenance.api.event.MaintenanceChangedEvent;
import eu.kennytv.maintenance.api.event.manager.EventListener;
import eu.kennytv.maintenance.api.event.proxy.ServerMaintenanceChangedEvent;
import eu.kennytv.maintenance.api.proxy.MaintenanceProxy;
import eu.kennytv.maintenance.api.proxy.Server;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;

/**
 * Mirrors Maintenance's proxy-wide state to {@code status.json} for external readers, and applies
 * {@code request.json} drops to toggle maintenance without going through RCON.
 *
 * <p>{@code plannedEndsAtEpochSeconds} is purely informational. Nothing in this class ever clears
 * maintenance automatically - the operator (restart script) stays the sole authority on when
 * maintenance actually ends, so a stuck restart never gets waved through by a timer.
 */
final class MaintenanceStatusService {

    // Files.createTempFile() defaults to owner-only permissions on POSIX (a deliberate JDK
    // security default for temp files); since status.json/request.json.rejected are produced by
    // an atomic move of that temp file, they'd otherwise inherit those restrictive permissions
    // and be unreadable to anything but the proxy's own user - defeating the point of writing
    // status.json for external readers. Match the rest of the plugins directory (rw-rw-r--).
    // Must be applied via setPosixFilePermissions() after creation, not createTempFile()'s
    // FileAttribute varargs - confirmed empirically that this JDK silently ignores permissions
    // requested that way and creates the file at the umask-restricted default regardless.
    private static final Set<PosixFilePermission> WORLD_READABLE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-rw-r--");

    // The request.json read is polled every 2 seconds regardless of state, so a persistent
    // failure (e.g. a caller writing the file with the wrong permissions) would otherwise log
    // an error every single poll. Log the first occurrence immediately, then at most this often
    // while the same condition persists.
    private static final Duration READ_ERROR_LOG_INTERVAL = Duration.ofMinutes(5);

    // request.json is written by whatever local principal owns the restart tooling, but nothing
    // stops it from growing unbounded (bug, misconfiguration, or a compromised writer) - cap it
    // well above any legitimate request so Gson never has to parse an attacker-sized payload.
    private static final long MAX_REQUEST_FILE_SIZE_BYTES = 64 * 1024;

    private final MaintenanceProxy api;
    // Velocity's Scheduler#buildTask requires the actual @Plugin-annotated instance (it looks the
    // object up via PluginManager#fromInstance and throws IllegalArgumentException otherwise) -
    // this service isn't that instance, so the plugin object is threaded through separately.
    private final Object pluginInstance;
    private final Path dataDirectory;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path statusFile;
    private final Path requestFile;
    private final Path rejectedRequestFile;
    private final AtomicReference<Long> plannedEndsAtEpochSeconds = new AtomicReference<>();
    // Only ever touched from pollRequestFile(), which Velocity's scheduler invokes serially for
    // a single repeating task - no concurrent access, no need for atomics here.
    private Instant lastReadErrorLoggedAt = Instant.MIN;

    MaintenanceStatusService(
            final MaintenanceProxy api, final Object pluginInstance, final Path dataDirectory, final Logger logger) {
        this.api = api;
        this.pluginInstance = pluginInstance;
        this.dataDirectory = dataDirectory;
        this.logger = logger;
        this.statusFile = dataDirectory.resolve("status.json");
        this.requestFile = dataDirectory.resolve("request.json");
        this.rejectedRequestFile = dataDirectory.resolve("request.json.rejected");
    }

    void start(final ProxyServer server) {
        try {
            Files.createDirectories(dataDirectory);
        } catch (final IOException e) {
            logger.error("Could not create data directory {}: {}", dataDirectory, e.getMessage());
            return;
        }

        api.getEventManager()
                .registerListener(
                        new EventListener<MaintenanceChangedEvent>() {
                            @Override
                            public void onEvent(final MaintenanceChangedEvent event) {
                                writeStatus();
                            }
                        },
                        MaintenanceChangedEvent.class);
        api.getEventManager()
                .registerListener(
                        new EventListener<ServerMaintenanceChangedEvent>() {
                            @Override
                            public void onEvent(final ServerMaintenanceChangedEvent event) {
                                writeStatus();
                            }
                        },
                        ServerMaintenanceChangedEvent.class);

        // Reflect current state immediately - don't wait for the next toggle.
        writeStatus();

        // Cheap in-process poll; no RCON round trip involved since this is just a local file check.
        server.getScheduler()
                .buildTask(pluginInstance, this::pollRequestFile)
                .repeat(2, TimeUnit.SECONDS)
                .schedule();

        logger.info(
                "Maintenance API connected - writing {} on state changes, watching {} for requests",
                statusFile,
                requestFile);
    }

    private void writeStatus() {
        final Map<String, Boolean> servers = new LinkedHashMap<>();
        for (final String name : api.getServers()) {
            servers.put(name, api.isMaintenance(api.getServerOrDummy(name)));
        }

        final boolean maintenance = api.isMaintenance();
        final String reason = reportedReason(maintenance, api.getSettings().activeReason());
        final Long plannedEndsAt = reportedPlannedEndsAt(maintenance, plannedEndsAtEpochSeconds.get());
        final StatusFile status = StatusFile.now(maintenance, reason, plannedEndsAt, servers);

        logger.info(
                "Refreshing {}: maintenance={}, reason={}, servers={}",
                statusFile,
                status.maintenance(),
                status.reason(),
                status.servers());
        writeAtomic(statusFile, gson.toJson(status));
    }

    private void pollRequestFile() {
        final String json;
        try {
            final long size = Files.size(requestFile);
            if (size > MAX_REQUEST_FILE_SIZE_BYTES) {
                logger.error(
                        "Rejecting oversized maintenance request file: {} bytes (max {})",
                        size,
                        MAX_REQUEST_FILE_SIZE_BYTES);
                moveAside(requestFile, rejectedRequestFile);
                return;
            }
            json = Files.readString(requestFile, StandardCharsets.UTF_8);
        } catch (final NoSuchFileException e) {
            return; // no request pending - the common case, nothing to log
        } catch (final IOException e) {
            final Instant now = Instant.now();
            if (Duration.between(lastReadErrorLoggedAt, now).compareTo(READ_ERROR_LOG_INTERVAL) >= 0) {
                logger.error(
                        "Failed to read maintenance request file: {} (repeated identical failures logged at most every {})",
                        e.getMessage(),
                        READ_ERROR_LOG_INTERVAL);
                lastReadErrorLoggedAt = now;
            }
            return;
        }
        lastReadErrorLoggedAt = Instant.MIN; // condition cleared - a future failure logs immediately again

        final MaintenanceRequest request;
        try {
            request = gson.fromJson(json, MaintenanceRequest.class);
            if (request == null) {
                throw new IllegalArgumentException("empty request");
            }
            request.validate();
        } catch (final JsonSyntaxException | IllegalArgumentException e) {
            logger.error("Rejecting malformed maintenance request file: {}", e.getMessage());
            moveAside(requestFile, rejectedRequestFile);
            return;
        }

        logger.info(
                "Applying maintenance request from {}: maintenance={}, server={}, reason={}, minutes={}",
                requestFile,
                request.maintenance(),
                request.server(),
                request.reason(),
                request.minutes());
        try {
            applyRequest(request);
        } catch (final RuntimeException e) {
            // A request that blows up here (e.g. the backend API rejecting it) must not stay in
            // place - the poller would just retry it every 2 seconds forever with no way to
            // recover on its own. Move it aside like a malformed request instead.
            logger.error("Failed to apply maintenance request from {}: {}", requestFile, e.getMessage());
            moveAside(requestFile, rejectedRequestFile);
            return;
        }

        try {
            Files.delete(requestFile);
        } catch (final IOException e) {
            logger.error("Failed to remove consumed maintenance request file: {}", e.getMessage());
        }

        writeStatus();
    }

    private void applyRequest(final MaintenanceRequest request) {
        // Resolve the backend once via getServer() (nullable) instead of a separate
        // getServers().contains() check followed by getServerOrDummy(): the registry can change
        // between two calls, which could let a name that passed the contains() check resolve to a
        // DummyServer anyway. A null result means the name isn't registered - reject it up front,
        // caught by pollRequestFile() and moved aside like any other bad request.
        final Server targetServer;
        if (request.server() == null) {
            targetServer = null;
        } else {
            targetServer = api.getServer(request.server());
            if (targetServer == null) {
                throw new IllegalArgumentException("Unknown backend server: " + request.server());
            }
        }

        // status.json has no per-server slot for reason/ETA - only ever mutate the global fields
        // for proxy-wide requests. MaintenanceRequest.validate() already rejects reason/minutes
        // on a per-server request, but guard here too: without this, a per-server request would
        // silently corrupt the reason/ETA reported for the whole proxy (e.g. {"maintenance":
        // false,"server":"lobby"} would erase the reason for an unrelated proxy-wide window).
        if (request.server() == null) {
            // Reason/ETA are set BEFORE toggling maintenance, not after: Maintenance fires
            // MaintenanceChangedEvent synchronously from within setMaintenance()/
            // setMaintenanceToServer(), which triggers our own listener's writeStatus()
            // mid-method - if the reason/ETA were still set afterward, that event-triggered
            // write would publish maintenance=true next to the previous reason for a brief
            // window (observed on the real proxy: two refreshes logged for one request, the
            // first with a stale reason).
            if (request.maintenance()) {
                // An omitted reason/minutes preserves whatever is already active, but only across
                // an already-on-going maintenance window: activeReason()/plannedEndsAtEpochSeconds
                // can go stale if maintenance was last turned off through a path that bypasses
                // applyRequest() entirely (RCON, an in-game command, restart tooling) - none of
                // those clear our internal state, they just get suppressed while reported (see
                // reportedReason()/reportedPlannedEndsAt() below). Restoring that stale value on
                // the next turn-on would be wrong, so only preserve when we were already on.
                final boolean wasOn = api.isMaintenance();
                if (request.reason() != null) {
                    api.getSettings().setActiveReason(request.reason());
                } else if (!wasOn) {
                    api.getSettings().setActiveReason(null);
                }
                if (request.minutes() != null) {
                    plannedEndsAtEpochSeconds.set(computePlannedEndsAt(true, request.minutes(), Instant.now()));
                } else if (!wasOn) {
                    plannedEndsAtEpochSeconds.set(null);
                }
            } else {
                // Reason/ETA are only meaningful while maintenance is on - always clear both when
                // turning off, regardless of what the request says, so status.json never publishes
                // maintenance=false next to a stale reason/ETA from the previous on-cycle
                // (observed live: turning maintenance off with reason:null left the prior reason
                // in place).
                api.getSettings().setActiveReason(null);
                plannedEndsAtEpochSeconds.set(null);
            }
        }

        if (targetServer == null) {
            api.setMaintenance(request.maintenance(), null);
        } else {
            api.setMaintenanceToServer(targetServer, request.maintenance(), null);
        }
    }

    // Only meaningful when turning maintenance on - null otherwise so status.json never publishes
    // maintenance=false alongside a stale/contradictory plannedEndsAtEpochSeconds. Package-private
    // and static so this specific rule is directly unit-testable without a MaintenanceProxy.
    static Long computePlannedEndsAt(final boolean maintenance, final Long minutes, final Instant now) {
        if (!maintenance || minutes == null) {
            return null;
        }
        return now.plusSeconds(minutes * 60L).getEpochSecond();
    }

    // status.json should never report an ETA while maintenance is actually off, regardless of
    // what turned it off - mirrors reportedReason() below for the same reason: applyRequest()
    // only nulls plannedEndsAtEpochSeconds when *we* process an "off" request, but maintenance
    // can also be turned off via RCON, an in-game command, or restart tooling, none of which go
    // through applyRequest() at all. Package-private and static so this is directly unit-testable
    // without a MaintenanceProxy.
    static Long reportedPlannedEndsAt(final boolean maintenance, final Long plannedEndsAtEpochSeconds) {
        return maintenance ? plannedEndsAtEpochSeconds : null;
    }

    // status.json should never report a reason while maintenance is actually off, regardless of
    // what turned it off - applyRequest() clears Maintenance's own activeReason() when *we*
    // process an "off" request, but maintenance can also be turned off via RCON, an in-game
    // command, or restart tooling, none of which necessarily clear activeReason() themselves
    // (observed live: a restart-script-driven toggle left a stale reason in status.json).
    // Package-private and static so this is directly unit-testable without a MaintenanceProxy.
    static String reportedReason(final boolean maintenance, final String activeReason) {
        return maintenance ? activeReason : null;
    }

    // Package-private (not private) so the permissions behavior is directly unit-testable.
    void writeAtomic(final Path target, final String content) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile(dataDirectory, target.getFileName().toString(), ".tmp");
            Files.setPosixFilePermissions(tmp, WORLD_READABLE_PERMISSIONS);
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            tmp = null; // moved successfully - nothing left to clean up
        } catch (final IOException e) {
            logger.error("Failed to write {}: {}", target, e.getMessage());
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (final IOException ignored) {
                    // best effort - a leftover .tmp file isn't worth a second error log
                }
            }
        }
    }

    private void moveAside(final Path source, final Path target) {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            logger.error("Failed to move aside {}: {}", source, e.getMessage());
        }
    }
}
