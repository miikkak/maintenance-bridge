package net.guesswhoami.maintenancebridge;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import eu.kennytv.maintenance.api.Maintenance;
import eu.kennytv.maintenance.api.MaintenanceProvider;
import eu.kennytv.maintenance.api.proxy.MaintenanceProxy;
import java.nio.file.Path;
import org.slf4j.Logger;

@Plugin(
        id = "maintenance-bridge",
        name = "maintenance-bridge",
        version = BuildInfo.VERSION,
        description = "Bridges maintenance-mode state with mc-healthcheck / minecraft-limbo-waiting-container",
        authors = {"miikkak"},
        dependencies = {@Dependency(id = "maintenance", optional = true)})
public class MaintenanceBridgePlugin {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    @Inject
    public MaintenanceBridgePlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        // Maintenance is an optional (soft) dependency: maintenance-api-proxy is compileOnly, so
        // when the plugin isn't installed MaintenanceProvider isn't even on the runtime classpath
        // and MaintenanceProvider.get() would throw NoClassDefFoundError - check PluginManager
        // first so absence is a normal "stay inactive" path rather than a startup crash.
        if (!server.getPluginManager().isLoaded("maintenance")) {
            logger.warn("Maintenance plugin not found - bridge stays inactive.");
            return;
        }

        final Maintenance api;
        try {
            api = MaintenanceProvider.get();
        } catch (final IllegalStateException | LinkageError e) {
            // IllegalStateException: Maintenance is loaded but hasn't finished its own
            // initialization yet. LinkageError (covers NoClassDefFoundError): belt-and-suspenders
            // in case the isLoaded() check above raced Maintenance's own plugin load.
            logger.warn("Maintenance plugin is present but its API is not available: {}", e.getMessage());
            return;
        }
        if (!(api instanceof MaintenanceProxy proxyApi)) {
            logger.warn("Maintenance plugin is not a proxy build - bridge stays inactive.");
            return;
        }

        logger.info("Found Maintenance {} - activating bridge", api.getVersion());
        new MaintenanceStatusService(proxyApi, this, dataDirectory, logger).start(server);
    }
}
