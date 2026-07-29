package com.vlessclient.service.mcp;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.SingBoxEngine;

/**
 * Production {@link AppControlService} backed by the real application services.
 *
 * <p>Dependencies are injected rather than pulled from the global
 * {@code ServiceLocator} so the class stays unit-testable and so a freshly
 * downloaded {@code SingBoxEngine} can be swapped in without rebuilding the MCP
 * layer (see {@link #setEngine}).</p>
 */
public class DefaultAppControlService implements AppControlService {

    private final ConfigStore configStore;
    private volatile SingBoxEngine engine;

    public DefaultAppControlService(ConfigStore configStore, SingBoxEngine engine) {
        this.configStore = configStore;
        this.engine = engine;
    }

    /**
     * Replaces the engine reference — used when the sing-box binary is
     * downloaded after startup and a new engine instance is registered.
     */
    public void setEngine(SingBoxEngine engine) {
        this.engine = engine;
    }

    @Override
    public StatusInfo getStatus() {
        AppSettings settings = configStore.getSettings();
        SingBoxEngine current = engine;

        ConnectionState state = current != null
                ? current.connectionStateProperty().get()
                : ConnectionState.DISCONNECTED;
        String error = current != null ? current.errorMessageProperty().get() : "";

        ServerConfig active = configStore.getServers().stream()
                .filter(ServerConfig::isActive)
                .findFirst()
                .orElse(null);

        return new StatusInfo(
                state.name(),
                state == ConnectionState.CONNECTED,
                active != null ? active.getId() : null,
                active != null ? active.getName() : null,
                settings.getProxyMode().getValue(),
                settings.getSocksPort(),
                settings.getHttpPort(),
                settings.getClashApiPort(),
                error != null ? error : "");
    }
}
