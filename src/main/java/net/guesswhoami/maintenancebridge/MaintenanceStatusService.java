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
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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

    private final MaintenanceProxy api;
    private final Path dataDirectory;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path statusFile;
    private final Path requestFile;
    private final Path rejectedRequestFile;
    private final AtomicReference<Long> plannedEndsAtEpochSeconds = new AtomicReference<>();

    MaintenanceStatusService(final MaintenanceProxy api, final Path dataDirectory, final Logger logger) {
        this.api = api;
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
                .buildTask(this, this::pollRequestFile)
                .repeat(2, TimeUnit.SECONDS)
                .schedule();
    }

    private void writeStatus() {
        final Map<String, Boolean> servers = new LinkedHashMap<>();
        for (final String name : api.getServers()) {
            servers.put(name, api.isMaintenance(api.getServerOrDummy(name)));
        }

        final StatusFile status = StatusFile.now(
                api.isMaintenance(), api.getSettings().activeReason(), plannedEndsAtEpochSeconds.get(), servers);

        writeAtomic(statusFile, gson.toJson(status));
    }

    private void pollRequestFile() {
        if (!Files.exists(requestFile)) {
            return;
        }

        final String json;
        try {
            json = Files.readString(requestFile, StandardCharsets.UTF_8);
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
                request.minutes() != null
                        ? Instant.now().plusSeconds(request.minutes() * 60L).getEpochSecond()
                        : null);
    }

    private void writeAtomic(final Path target, final String content) {
        try {
            final Path tmp = Files.createTempFile(dataDirectory, target.getFileName().toString(), ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            logger.error("Failed to write {}: {}", target, e.getMessage());
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
