package com.vlessclient.ui.view.dashboard;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.LatencyTester;
import java.util.List;
import java.util.Map;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

/**
 * Start/stop state of the Dashboard's latency test: a once-a-second loop that
 * measures every configured server and renders one result row each, until the
 * user presses Stop. Extracted from
 * {@link com.vlessclient.ui.view.DashboardViewController}, which stays the
 * FXML endpoint and passes its injected controls in.
 */
public final class LatencyTestSession {

    private final LatencyTester latencyTester;
    private final Button testLatencyButton;
    private final VBox latencyResultList;

    private Timeline latencyTimeline;
    private volatile boolean latencyInFlight;

    /** Invoked when the user picks a server from the results. */
    private java.util.function.Consumer<ServerConfig> onServerChosen = server -> { };

    /**
     * Creates the session over the controller's button and result list.
     * {@code latencyTester} may be null when the service is unavailable; the
     * toggle then only shows a status line.
     */
    public LatencyTestSession(LatencyTester latencyTester, Button testLatencyButton,
                              VBox latencyResultList) {
        this.latencyTester = latencyTester;
        this.testLatencyButton = testLatencyButton;
        this.latencyResultList = latencyResultList;
    }

    /**
     * Makes result rows actionable. Without this the measurement is read-only:
     * the user learns which server is fastest and then has to go find it in
     * another tab to actually use it.
     *
     * @param handler receives the chosen server, on the FX thread
     */
    public void setOnServerChosen(java.util.function.Consumer<ServerConfig> handler) {
        this.onServerChosen = handler != null ? handler : server -> { };
    }

    /**
     * Starts the measurement loop, or stops it when one is already running.
     * Wired to the "Test Latency" button.
     */
    public void toggle() {
        if (latencyTester == null) {
            showLatencyStatus(I18n.get("dashboard.latency.unavailable"));
            return;
        }

        // Toggle: second click stops the running measurement loop.
        if (latencyTimeline != null) {
            stopLatencyLoop();
            return;
        }

        try {
            ConfigStore configStore = ServiceLocator.get(ConfigStore.class);
            List<ServerConfig> servers = configStore.getServers();
            if (servers.isEmpty()) {
                showLatencyStatus(I18n.get("dashboard.no.servers"));
                return;
            }

            startLatencyLoop(servers);
        } catch (IllegalArgumentException e) {
            showLatencyStatus(I18n.get("dashboard.no.servers"));
        }
    }

    private void startLatencyLoop(List<ServerConfig> servers) {
        testLatencyButton.setText(I18n.get("dashboard.stop"));
        showLatencyStatus(I18n.get("dashboard.testing"));

        // Fire once immediately so the user sees feedback within the first
        // second, then every second afterwards.
        tickLatency(servers);

        latencyTimeline = new Timeline(
                new KeyFrame(Duration.seconds(1), e -> tickLatency(servers))
        );
        latencyTimeline.setCycleCount(Animation.INDEFINITE);
        latencyTimeline.play();
    }

    private void stopLatencyLoop() {
        if (latencyTimeline != null) {
            latencyTimeline.stop();
            latencyTimeline = null;
        }
        testLatencyButton.setText(I18n.get("button.test.latency"));
        latencyInFlight = false;
    }

    private void tickLatency(List<ServerConfig> servers) {
        if (latencyInFlight || latencyTester == null) {
            return;
        }
        latencyInFlight = true;
        latencyTester.testAll(servers).whenComplete((results, err) ->
                Platform.runLater(() -> {
                    latencyInFlight = false;
                    // A tick can be outstanding for seconds (5s connect
                    // timeout). If the user pressed Stop meanwhile, drop the
                    // late result instead of popping the list back into view.
                    if (latencyTimeline == null) {
                        return;
                    }
                    if (err != null) {
                        showLatencyStatus(I18n.get("dashboard.test.failed"));
                        return;
                    }
                    displayLatencyResults(results, servers);
                }));
    }

    private void displayLatencyResults(Map<String, LatencyTester.Result> results,
                                       List<ServerConfig> servers) {
        if (results.isEmpty()) {
            showLatencyStatus(I18n.get("dashboard.no.results"));
            return;
        }

        latencyResultList.getChildren().clear();
        // Fastest first, unreachable last: the ranking is the answer the user
        // pressed the button for, and an unsorted list makes them do it by eye.
        List<ServerConfig> ranked = new java.util.ArrayList<>(servers);
        ranked.sort(java.util.Comparator.comparingLong(server -> {
            LatencyTester.Result result = results.get(server.getId());
            return result == null || !result.reachable() ? Long.MAX_VALUE : result.millis();
        }));
        for (ServerConfig server : ranked) {
            LatencyTester.Result result = results.get(server.getId());
            if (result == null) {
                continue;
            }
            latencyResultList.getChildren().add(buildLatencyRow(server, result));
        }
        if (latencyResultList.getChildren().isEmpty()) {
            showLatencyStatus(I18n.get("dashboard.no.results"));
            return;
        }
        setLatencyListVisible(true);
    }

    /**
     * One latency row: green dot + name + "NNN ms", or red dot + "timeout".
     * Clicking a reachable row makes that server active — the result is worth
     * acting on, not just reading.
     *
     * <p>The tooltip says which measurement produced the number. The two are
     * not comparable — a through-proxy number includes the handshake this
     * server would actually perform, a TCP one only proves the address answers
     * — and a row that does not say so invites ranking them against each
     * other.</p>
     */
    private HBox buildLatencyRow(ServerConfig server, LatencyTester.Result result) {
        boolean ok = result.reachable();
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        if (ok) {
            row.getStyleClass().add("latency-row-actionable");
            row.setCursor(javafx.scene.Cursor.HAND);
            row.setOnMouseClicked(event -> onServerChosen.accept(server));
            String how = I18n.get(result.throughProxy()
                    ? "dashboard.latency.via.proxy" : "dashboard.latency.via.tcp");
            Tooltip.install(row, new Tooltip(how + "\n" + I18n.get("dashboard.latency.use")));
        }

        Circle dot = new Circle(5);
        dot.getStyleClass().setAll(ok ? "status-circle-connected" : "status-circle-error");

        Label nameLabel = new Label(
                server.getName() != null ? server.getName() : server.getAddress());
        nameLabel.getStyleClass().setAll("service-name");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label value = new Label(
                ok ? result.millis() + " ms" : I18n.get("dashboard.latency.timeout"));
        value.getStyleClass().setAll(ok ? "service-ok" : "service-fail");

        row.getChildren().addAll(dot, nameLabel, spacer, value);
        return row;
    }

    /** Shows a single muted status line (Testing…, No results, errors). */
    private void showLatencyStatus(String text) {
        Label status = new Label(text);
        status.getStyleClass().setAll("service-pending");
        latencyResultList.getChildren().setAll(status);
        setLatencyListVisible(true);
    }

    private void setLatencyListVisible(boolean visible) {
        latencyResultList.setVisible(visible);
        latencyResultList.setManaged(visible);
    }
}
