package net.guesswhoami.maintenancebridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.velocitypowered.api.proxy.ProxyServer;
import eu.kennytv.maintenance.api.event.MaintenanceChangedEvent;
import eu.kennytv.maintenance.api.event.manager.EventListener;
import eu.kennytv.maintenance.api.event.proxy.ServerMaintenanceChangedEvent;
import eu.kennytv.maintenance.api.proxy.MaintenanceProxy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
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

        final StatusFile status = StatusFile.now(
                api.isMaintenance(), api.getSettings().activeReason(), plannedEndsAtEpochSeconds.get(), servers);

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
            json = Files.readString(requestFile, StandardCharsets.UTF_8);
        } catch (final NoSuchFileException e) {
            return; // no request pending - the common case, nothing to log
        } catch (final IOException e) {
            logger.error("Failed to read maintenance request file: {}", e.getMessage());
            return;
        }

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
        applyRequest(request);

        try {
            Files.delete(requestFile);
        } catch (final IOException e) {
            logger.error("Failed to remove consumed maintenance request file: {}", e.getMessage());
        }

        writeStatus();
    }

    private void applyRequest(final MaintenanceRequest request) {
        if (request.server() == null) {
            api.setMaintenance(request.maintenance(), null);
        } else {
            api.setMaintenanceToServer(api.getServerOrDummy(request.server()), request.maintenance(), null);
        }
        if (request.reason() != null) {
            api.getSettings().setActiveReason(request.reason());
        }
        plannedEndsAtEpochSeconds.set(
                computePlannedEndsAt(request.maintenance(), request.minutes(), Instant.now()));
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
