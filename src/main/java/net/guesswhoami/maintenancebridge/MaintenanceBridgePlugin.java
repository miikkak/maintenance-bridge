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
        final Maintenance api = MaintenanceProvider.get();
        if (!(api instanceof MaintenanceProxy proxyApi)) {
            logger.warn("Maintenance plugin not found (or not a proxy build) - bridge stays inactive.");
            return;
        }

        logger.info("Found Maintenance {} - activating bridge", api.getVersion());
        new MaintenanceStatusService(proxyApi, this, dataDirectory, logger).start(server);
    }
}
