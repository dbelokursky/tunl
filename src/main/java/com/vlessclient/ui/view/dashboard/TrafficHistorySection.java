package com.vlessclient.ui.view.dashboard;

import com.vlessclient.app.I18n;
import com.vlessclient.service.TrafficHistoryStore;
import com.vlessclient.service.TrafficMonitor;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * The dashboard's traffic-history panel: thirty daily bars, the busiest
 * servers behind them, and the only control that clears the record.
 *
 * <p>Collapsed by default and opened by clicking the session total in the
 * status row. The hero card had a 150px chart removed from it for saying
 * nothing; this earns its space differently — daily bars share one scale and
 * one window, so their heights are comparable to each other in a way the old
 * auto-scaling speed curve never was.</p>
 *
 * <p>The clear control lives here rather than in Settings because nothing in
 * the history expires on its own: the panel that shows the record is the
 * place that has to offer removing it.</p>
 */
public final class TrafficHistorySection {

    /** Days the panel plots; fixed, so the bars can be built once. */
    private static final int WINDOW_DAYS = 30;

    /** How many servers the summary line names before folding the rest away. */
    private static final int TOP_SERVERS = 3;

    private static final double BAR_MAX_HEIGHT = 40;

    /**
     * A quiet day still draws a sliver. A zero-height Region disappears, and a
     * gap in a row of bars reads as missing data rather than as a day with no
     * traffic.
     */
    private static final double BAR_MIN_HEIGHT = 2;

    /** How often today's bar catches up while the panel is open. */
    private static final Duration REFRESH_PERIOD = Duration.seconds(30);

    /** The controls the panel drives, as injected into the FXML controller. */
    public record Controls(VBox panel, Label sessionTotal, Label title, Label servers,
                           Hyperlink reset, HBox bars, Label range, Label month) { }

    private final TrafficHistoryStore store;
    private final Controls controls;

    /** Persists the open/closed state; the section does not own settings. */
    private final Consumer<Boolean> persistExpanded;

    private final List<Region> barNodes = new ArrayList<>();
    private Timeline refreshTimer;

    /**
     * Creates the section over its controls.
     *
     * @param store the history to read and clear, or null when unavailable
     * @param controls the injected nodes
     * @param persistExpanded called with the new state whenever the panel is
     *     opened or closed
     */
    public TrafficHistorySection(TrafficHistoryStore store, Controls controls,
                                 Consumer<Boolean> persistExpanded) {
        this.store = store;
        this.controls = controls;
        this.persistExpanded = persistExpanded;
    }

    /**
     * Binds the static labels, builds the bars and restores the panel's last
     * state.
     *
     * @param expanded whether the panel was open when the app last closed
     */
    public void init(boolean expanded) {
        controls.title().textProperty().bind(I18n.binding("dashboard.traffic.history.title"));
        controls.reset().textProperty().bind(I18n.binding("dashboard.traffic.history.reset"));

        for (int i = 0; i < WINDOW_DAYS; i++) {
            Region bar = new Region();
            bar.getStyleClass().add("traffic-history-bar");
            bar.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(bar, Priority.ALWAYS);
            barNodes.add(bar);
        }
        controls.bars().getChildren().setAll(barNodes);

        // A language switch has to redraw the byte figures and the dates.
        I18n.localeProperty().addListener((obs, oldVal, newVal) -> refresh());

        setExpanded(store != null && expanded, false);
    }

    /** Opens the panel if it is closed and closes it if it is open. */
    public void toggle() {
        setExpanded(!controls.panel().isVisible(), true);
    }

    /**
     * Re-reads the history and repaints. Cheap enough to call on any state
     * change: it walks thirty days of a few rows each.
     */
    public void refresh() {
        if (store == null || !controls.panel().isVisible()) {
            return;
        }
        List<TrafficHistoryStore.DayTotal> days = store.lastDays(WINDOW_DAYS);
        long peak = days.stream().mapToLong(TrafficHistoryStore.DayTotal::total).max().orElse(0);

        DateTimeFormatter dayFormat = DateTimeFormatter
                .ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(I18n.getLocale());

        for (int i = 0; i < barNodes.size(); i++) {
            TrafficHistoryStore.DayTotal day = days.get(i);
            Region bar = barNodes.get(i);
            // Heights are relative to the busiest day in the window, so the
            // shape says "this day against that one" and nothing more. The
            // absolute figure is in the tooltip, where it cannot be misread
            // as a scale the chart does not have.
            double height = peak == 0 ? BAR_MIN_HEIGHT
                    : BAR_MIN_HEIGHT + (BAR_MAX_HEIGHT - BAR_MIN_HEIGHT)
                            * ((double) day.total() / peak);
            bar.setMinHeight(height);
            bar.setPrefHeight(height);
            bar.setMaxHeight(height);
            bar.getStyleClass().setAll(i == barNodes.size() - 1
                    ? "traffic-history-bar-today" : "traffic-history-bar");
            Tooltip.install(bar, new Tooltip(day.date().format(dayFormat)
                    + " — " + TrafficMonitor.formatBytes(day.total())));
        }

        long windowTotal = days.stream().mapToLong(TrafficHistoryStore.DayTotal::total).sum();
        controls.range().setText(windowTotal == 0
                ? I18n.get("dashboard.traffic.history.empty")
                : I18n.get("dashboard.traffic.history.range",
                        days.get(0).date().format(dayFormat)));
        controls.month().setText(I18n.get("dashboard.traffic.history.month",
                TrafficMonitor.formatBytes(store.totalForMonth(YearMonth.now()))));
        controls.servers().setText(store.topServers(TOP_SERVERS, WINDOW_DAYS).stream()
                .map(server -> (server.serverName() == null || server.serverName().isBlank()
                        ? I18n.get("dashboard.traffic.history.unknown.server")
                        : server.serverName())
                        + " — " + TrafficMonitor.formatBytes(server.total()))
                .collect(Collectors.joining(" · ")));
    }

    /**
     * Clears the record after confirming, because nothing here expires on its
     * own and there is no undo.
     *
     * @param confirm asks the user; the history is cleared only on true
     */
    public void reset(java.util.function.BooleanSupplier confirm) {
        if (store == null || !confirm.getAsBoolean()) {
            return;
        }
        store.reset();
        refresh();
    }

    private void setExpanded(boolean expanded, boolean persist) {
        controls.panel().setVisible(expanded);
        controls.panel().setManaged(expanded);
        controls.sessionTotal().setDisable(false);
        if (expanded) {
            refresh();
            startTimer();
        } else {
            stopTimer();
        }
        if (persist && persistExpanded != null) {
            persistExpanded.accept(expanded);
        }
    }

    /**
     * Today's bar grows while the panel is open, so it is polled rather than
     * left to go stale until the panel is reopened. The timer exists only
     * while the panel is visible: a collapsed panel must cost nothing.
     */
    private void startTimer() {
        if (refreshTimer == null) {
            refreshTimer = new Timeline(new KeyFrame(REFRESH_PERIOD, event -> refresh()));
            refreshTimer.setCycleCount(Animation.INDEFINITE);
        }
        refreshTimer.playFromStart();
    }

    private void stopTimer() {
        if (refreshTimer != null) {
            refreshTimer.stop();
        }
    }
}
