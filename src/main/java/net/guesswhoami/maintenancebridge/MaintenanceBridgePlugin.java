package net.guesswhoami.maintenancebridge;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

@Plugin(
        id = "maintenance-bridge",
        name = "maintenance-bridge",
        version = "0.1.0-SNAPSHOT",
        description = "Bridges maintenance-mode state with mc-healthcheck / minecraft-limbo-waiting-container")
public class MaintenanceBridgePlugin {

    private final ProxyServer server;
    private final Logger logger;

    @Inject
    public MaintenanceBridgePlugin(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        logger.info("maintenance-bridge initialized");
    }
}
