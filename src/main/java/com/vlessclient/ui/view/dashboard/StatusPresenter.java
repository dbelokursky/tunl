package com.vlessclient.ui.view.dashboard;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TunnelHealth;
import com.vlessclient.model.TunnelStatus;
import com.vlessclient.service.CountryResolver;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.ui.view.Flags;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/**
 * Paints the Dashboard's hero card: the status dot and its halo, the title and
 * subtitle, the exit-country flag, and the connect button's label and style.
 *
 * <p>Split in two on purpose, and the split is the reason this class exists.
 * What the dot and the wording say is a question about the <em>tunnel</em> —
 * the process state refined by the reachability verdict, through
 * {@link TunnelStatus#of} — while what the button offers is a question about
 * the <em>process</em>. A tunnel that is up but carries nothing reads as a
 * failure and still offers Disconnect, not Retry. Keeping both in one place
 * makes that distinction visible instead of leaving it spread through a
 * thousand-line controller.</p>
 */
public class StatusPresenter {

    /**
     * The FXML-injected controls this presenter paints. They stay owned (and
     * declared) by the Dashboard controller; this record just carries them.
     */
    public record Controls(Circle statusCircle, StackPane statusHalo, StackPane statusFlag,
                           Label statusTitle, Label statusLabel, Label serverNameLabel,
                           Button connectButton) {
    }

    private final Circle statusCircle;
    private final StackPane statusHalo;
    private final StackPane statusFlag;
    private final Label statusTitle;
    private final Label statusLabel;
    private final Label serverNameLabel;
    private final Button connectButton;

    private final Supplier<ServerConfig> activeServer;
    private final Supplier<TunnelHealth> health;
    private final Supplier<SingBoxEngine> engine;
    private final Runnable refreshConnectAvailability;

    /**
     * Creates the presenter.
     *
     * @param controls                   the hero-card controls
     * @param activeServer               the server the card describes, read on
     *                                   every repaint because it changes under
     *                                   the card
     * @param health                     the current reachability verdict
     * @param engine                     the current engine, for the guard on a
     *                                   late-arriving country lookup
     * @param refreshConnectAvailability re-asks the controller whether the
     *                                   connect button should be enabled
     */
    public StatusPresenter(Controls controls,
                           Supplier<ServerConfig> activeServer,
                           Supplier<TunnelHealth> health,
                           Supplier<SingBoxEngine> engine,
                           Runnable refreshConnectAvailability) {
        this.statusCircle = controls.statusCircle();
        this.statusHalo = controls.statusHalo();
        this.statusFlag = controls.statusFlag();
        this.statusTitle = controls.statusTitle();
        this.statusLabel = controls.statusLabel();
        this.serverNameLabel = controls.serverNameLabel();
        this.connectButton = controls.connectButton();
        this.activeServer = activeServer;
        this.health = health;
        this.engine = engine;
        this.refreshConnectAvailability = refreshConnectAvailability;
    }

    /**
     * Repaints the whole card for a process state, refining it with the
     * current health verdict.
     *
     * @param state the engine's connection state
     */
    public void update(ConnectionState state) {
        TunnelStatus status = TunnelStatus.of(state, currentHealth());

        paintStatusIndicator(status, state);
        paintStatusSubtitle(status);
        paintConnectButton(state);

        // serverNameLabel is no longer rendered in the hero card (the state
        // subtitle conveys that information), but update it for anyone still
        // observing the field.
        ServerConfig server = activeServer.get();
        serverNameLabel.setText(server != null ? server.getName() : "");

        if (state != ConnectionState.CONNECTED && state != ConnectionState.CONNECTING) {
            refreshConnectAvailability.run();
        }
    }

    /**
     * Shows the engine's own failure text, which says more than the generic
     * subtitle for the same state.
     *
     * @param message the engine's error message
     */
    public void showEngineError(String message) {
        statusLabel.setText(message);
    }

    /**
     * Renders the active server's name, or the empty-state wording when there
     * is none.
     *
     * @param server the newly active server, possibly null
     */
    public void showActiveServerName(ServerConfig server) {
        serverNameLabel.setText(server != null
                ? server.getName()
                : I18n.get("dashboard.no.server"));
    }

    private TunnelHealth currentHealth() {
        TunnelHealth current = health.get();
        return current != null ? current : TunnelHealth.UNMONITORED;
    }

    /**
     * The style-class suffix and fill shared by the dot, its halo and the
     * title. Several statuses map onto one look on purpose — "verifying",
     * "partly reachable" and "unverified" are all the same amber "do not trust
     * this yet" — so the existing four style classes still cover the range.
     */
    private static String toneSuffix(TunnelStatus.Tone tone) {
        return switch (tone) {
            case OK -> "connected";
            case PENDING -> "connecting";
            case BAD -> "error";
            case IDLE -> "disconnected";
        };
    }

    private static Color toneFill(TunnelStatus.Tone tone) {
        return switch (tone) {
            case OK -> Color.web("#2e7d32");
            case PENDING -> Color.web("#ef6c00");
            case BAD -> Color.web("#c62828");
            case IDLE -> Color.web("#9e9e9e");
        };
    }

    private void paintStatusIndicator(TunnelStatus status, ConnectionState state) {
        String suffix = toneSuffix(status.tone());

        statusCircle.setFill(toneFill(status.tone()));
        statusCircle.getStyleClass().setAll("status-circle-" + suffix);

        if (statusHalo != null) {
            statusHalo.getStyleClass().removeAll(
                    "status-halo-connected", "status-halo-connecting",
                    "status-halo-error", "status-halo-disconnected");
            statusHalo.getStyleClass().add("status-halo-" + suffix);
        }

        if (statusTitle != null) {
            statusTitle.setText(titleFor(status));
            statusTitle.getStyleClass().setAll("status-title", "status-title-" + suffix);
        }

        // The exit-country flag belongs to a running core, whatever the probes
        // then make of it.
        if (state == ConnectionState.CONNECTED) {
            showStatusFlag(activeServer.get());
        } else {
            hideStatusFlag();
        }
    }

    private static String titleFor(TunnelStatus status) {
        String key = switch (status) {
            case CONNECTED -> "state.connected";
            case CONNECTING -> "state.connecting";
            case VERIFYING -> "state.verifying";
            case DEGRADED -> "state.degraded";
            case NO_TRAFFIC -> "state.no.traffic";
            case UNVERIFIED -> "state.unverified";
            case ERROR -> "state.error";
            case DISCONNECTED -> "state.disconnected";
        };
        return I18n.get(key);
    }

    private void paintStatusSubtitle(TunnelStatus status) {
        if (status == TunnelStatus.ERROR) {
            // The engine's own message ("Process exited ...") says more than a
            // generic line, so a repaint must not wipe it.
            if (!statusLabel.getText().startsWith("Process exited")) {
                statusLabel.setText(I18n.get("dashboard.status.check.logs"));
            }
        } else {
            statusLabel.setText(subtitleFor(status));
        }

        if (status.tone() == TunnelStatus.Tone.BAD) {
            statusLabel.getStyleClass().setAll("status-subtitle", "status-subtitle-error");
        } else {
            statusLabel.getStyleClass().setAll("status-subtitle");
        }
    }

    private String subtitleFor(TunnelStatus status) {
        ServerConfig server = activeServer.get();
        return switch (status) {
            case CONNECTED -> server != null
                    ? I18n.get("dashboard.status.routing.through", server.getName())
                    : I18n.get("dashboard.status.routing");
            case CONNECTING -> I18n.get("dashboard.status.establishing");
            case VERIFYING -> I18n.get("dashboard.status.verifying");
            case DEGRADED -> I18n.get("dashboard.status.degraded");
            case NO_TRAFFIC -> I18n.get("dashboard.status.no.traffic");
            case UNVERIFIED -> I18n.get("dashboard.status.unverified");
            case ERROR -> I18n.get("dashboard.status.check.logs");
            case DISCONNECTED -> server != null
                    ? I18n.get("dashboard.status.ready", server.getName())
                    : I18n.get("dashboard.status.add.server");
        };
    }

    private void paintConnectButton(ConnectionState state) {
        switch (state) {
            case CONNECTED -> {
                connectButton.setText(I18n.get("button.disconnect"));
                connectButton.setDisable(false);
                connectButton.getStyleClass().removeAll("connect-button");
                connectButton.getStyleClass().add("disconnect-button");
            }
            case CONNECTING -> {
                connectButton.setText(I18n.get("button.cancel"));
                connectButton.setDisable(false);
            }
            case ERROR -> {
                connectButton.setText(I18n.get("button.retry"));
                connectButton.setDisable(false);
                connectButton.getStyleClass().removeAll("disconnect-button");
                connectButton.getStyleClass().add("connect-button");
            }
            default -> {
                connectButton.setText(I18n.get("button.connect"));
                connectButton.setDisable(false);
                connectButton.getStyleClass().removeAll("disconnect-button");
                connectButton.getStyleClass().add("connect-button");
            }
        }
    }

    /**
     * Shows the exit country beside the status while connected.
     *
     * <p>Hidden — and unmanaged, so it takes no space — whenever the country
     * is unknown or the tunnel is down. An empty slot next to "Connected"
     * would read as a missing flag rather than as missing information.</p>
     */
    private void showStatusFlag(ServerConfig server) {
        if (statusFlag == null) {
            return;
        }
        hideStatusFlag();
        if (server == null) {
            return;
        }
        CountryResolver resolver;
        try {
            resolver = ServiceLocator.get(CountryResolver.class);
        } catch (IllegalArgumentException e) {
            return;
        }
        resolver.countryOf(server).ifPresent(this::paintStatusFlag);
        resolver.resolveAsync(server, code -> Platform.runLater(() -> {
            // Only paint if this is still the server we are connected to.
            SingBoxEngine current = engine.get();
            if (activeServer.get() == server
                    && current != null
                    && current.connectionStateProperty().get() == ConnectionState.CONNECTED) {
                paintStatusFlag(code);
            }
        }));
    }

    private void paintStatusFlag(String isoCode) {
        statusFlag.getChildren().setAll(Flags.of(isoCode, 18));
        statusFlag.setVisible(true);
        statusFlag.setManaged(true);
    }

    private void hideStatusFlag() {
        statusFlag.getChildren().clear();
        statusFlag.setVisible(false);
        statusFlag.setManaged(false);
    }
}
