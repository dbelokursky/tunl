package com.vlessclient.ui.view.dashboard;

import com.vlessclient.app.I18n;
import com.vlessclient.service.TrafficHistoryStore;
import com.vlessclient.service.TrafficMonitor;
import com.vlessclient.ui.view.FxTimer;
import com.vlessclient.ui.view.OnScreen;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javafx.beans.value.ObservableValue;
import javafx.css.PseudoClass;
import javafx.event.EventHandler;
import javafx.event.EventTarget;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * The dashboard's traffic-history panel: thirty daily bars and the busiest
 * servers behind them.
 *
 * <p>Collapsed by default and opened by clicking the session total in the
 * status row. The hero card had a 150px chart removed from it for saying
 * nothing; this earns its space differently — daily bars share one scale and
 * one window, so their heights are comparable to each other in a way the old
 * auto-scaling speed curve never was.</p>
 *
 * <p>Hovering a day gives its date and total; clicking one opens
 * {@link TrafficDayPopover} with the split the tooltip has no room for. The
 * click target is a full-height column rather than the bar, because a day with
 * no traffic draws two pixels and two pixels cannot be hit.</p>
 *
 * <p>The panel only reads the record. Clearing it is
 * {@link com.vlessclient.ui.view.settings.TrafficHistorySettingsSection}'s
 * job: the Clear link that used to end this header shared a corner with the
 * server line and today's bar, where the pointer already works, and one stray
 * click there plus Enter on the dialog wiped a record that never expires on
 * its own.</p>
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

    /** How often today's bar catches up while the panel is open and on screen. */
    private static final Duration REFRESH_PERIOD = Duration.ofSeconds(30);

    /**
     * Marks the open day's column. A pseudo-class rather than a style class
     * because {@link #refresh()} rebuilds each bar's style classes with
     * {@code setAll}, and a selection stored there would erase itself every
     * thirty seconds.
     */
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    /** The controls the panel drives, as injected into the FXML controller. */
    public record Controls(VBox panel, Label sessionTotal, Label title, Label servers,
                           HBox bars, Label range, Label month, StackPane barsHost) { }

    private final TrafficHistoryStore store;
    private final Controls controls;

    /** Persists the open/closed state; the section does not own settings. */
    private final Consumer<Boolean> persistExpanded;

    private final List<Region> barNodes = new ArrayList<>();
    private final List<StackPane> columnNodes = new ArrayList<>();
    private final List<Tooltip> barTooltips = new ArrayList<>();
    /** Polls today's bar; exists only while the panel is open and on screen. */
    private FxTimer.Cancellable refreshTimer;

    /** Whether the panel is in a scene whose window is showing. */
    private ObservableValue<Boolean> onScreen;

    private TrafficDayPopover popover;
    private int selectedIndex = -1;
    private EventHandler<MouseEvent> dismissOnOutsideClick;
    private EventHandler<KeyEvent> dayKeys;

    /**
     * Creates the section over its controls.
     *
     * @param store the history to read, or null when unavailable
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

        popover = new TrafficDayPopover(controls.barsHost());

        for (int i = 0; i < WINDOW_DAYS; i++) {
            Region bar = new Region();
            bar.getStyleClass().add("traffic-history-bar");
            bar.setMaxWidth(Double.MAX_VALUE);
            barNodes.add(bar);

            // The column, not the bar, is what the pointer meets: it spans the
            // full height of the row, so a day with two pixels of bar is as
            // easy to hit as the busiest one. A Region is only pickable where
            // it paints, hence both the transparent background the style
            // class gives it and pickOnBounds.
            StackPane column = new StackPane(bar);
            column.setAlignment(Pos.BOTTOM_CENTER);
            column.getStyleClass().add("traffic-history-column");
            column.setPickOnBounds(true);
            column.setMinWidth(0);
            HBox.setHgrow(column, Priority.ALWAYS);

            Tooltip tooltip = new Tooltip();
            // Installed once. Tooltip.install adds its handlers on every call,
            // so re-installing from refresh() would stack a new set every
            // thirty seconds for as long as the panel stayed open.
            Tooltip.install(column, tooltip);
            barTooltips.add(tooltip);

            final int index = i;
            column.setOnMouseClicked(event -> {
                if (selectedIndex == index) {
                    closeDay();
                } else {
                    openDay(index);
                }
            });
            columnNodes.add(column);
        }
        controls.bars().getChildren().setAll(columnNodes);

        // An unmanaged popover keeps whatever position it was given, so a
        // window resize would leave it pointing at the wrong day.
        controls.bars().widthProperty().addListener((obs, oldVal, newVal) -> reposition());

        // A language switch has to redraw the byte figures and the dates.
        I18n.localeProperty().addListener((obs, oldVal, newVal) -> refresh());

        // The view is cached and the window closes to the tray; in both cases
        // the panel keeps its nodes while nobody can see them.
        onScreen = OnScreen.of(controls.panel());
        onScreen.addListener((obs, wasOnScreen, isOnScreen) -> followScreen());

        setExpanded(store != null && expanded, false);
    }

    /** Opens the panel if it is closed and closes it if it is open. */
    public void toggle() {
        setExpanded(!controls.panel().isVisible(), true);
    }

    /**
     * Re-reads the history and repaints. Cheap enough to call on any state
     * change: it walks thirty days of a few rows each. A panel that is closed
     * or off screen is skipped, and read again when it is next seen.
     */
    public void refresh() {
        if (store == null || !controls.panel().isVisible() || !isOnScreen()) {
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
            barTooltips.get(i).setText(day.date().format(dayFormat)
                    + " — " + TrafficMonitor.formatBytes(day.total()));
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

        // The open day is re-read from the same list: today's bar is still
        // growing, and a card left showing the figures from thirty seconds ago
        // contradicts the bar it is pointing at.
        if (selectedIndex >= 0) {
            showDay(selectedIndex, days);
        }
    }

    /**
     * The day whose detail card is open, if any.
     *
     * @return the open day, or null when no day is open
     */
    public LocalDate openDate() {
        return popover == null ? null : popover.shownDate();
    }

    private void openDay(int index) {
        if (store == null) {
            return;
        }
        showDay(index, store.lastDays(WINDOW_DAYS));
        listenForDismissal();
    }

    private void showDay(int index, List<TrafficHistoryStore.DayTotal> days) {
        TrafficHistoryStore.DayTotal day = days.get(index);
        selectedIndex = index;
        markSelection();
        popover.showFor(day.date(), day, store.serversForDay(day.date()),
                columnNodes.get(index));
    }

    private void closeDay() {
        selectedIndex = -1;
        markSelection();
        if (popover != null) {
            popover.hide();
        }
        stopListeningForDismissal();
    }

    private void markSelection() {
        for (int i = 0; i < columnNodes.size(); i++) {
            columnNodes.get(i).pseudoClassStateChanged(SELECTED, i == selectedIndex);
        }
    }

    private void reposition() {
        if (selectedIndex >= 0 && store != null) {
            showDay(selectedIndex, store.lastDays(WINDOW_DAYS));
        }
    }

    /**
     * Escape and a click anywhere else close the day; the arrows walk to the
     * neighbouring one, which is the comparison the popover is covering while
     * it is open.
     *
     * <p>The filters exist only while a day is open, for the same reason the
     * refresh timer exists only while the panel is on screen: a feature nobody
     * is looking at must cost nothing.</p>
     */
    private void listenForDismissal() {
        Scene scene = controls.panel().getScene();
        if (scene == null || dismissOnOutsideClick != null) {
            return;
        }
        dismissOnOutsideClick = event -> {
            if (!isOwnNode(event.getTarget())) {
                closeDay();
            }
        };
        dayKeys = event -> {
            if (selectedIndex < 0) {
                return;
            }
            switch (event.getCode()) {
                case ESCAPE -> {
                    closeDay();
                    event.consume();
                }
                case LEFT -> step(selectedIndex - 1, event);
                case RIGHT -> step(selectedIndex + 1, event);
                default -> { }
            }
        };
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, dismissOnOutsideClick);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, dayKeys);
    }

    private void step(int index, KeyEvent event) {
        if (index >= 0 && index < columnNodes.size()) {
            openDay(index);
            event.consume();
        }
    }

    private void stopListeningForDismissal() {
        Scene scene = controls.panel().getScene();
        if (scene != null && dismissOnOutsideClick != null) {
            scene.removeEventFilter(MouseEvent.MOUSE_PRESSED, dismissOnOutsideClick);
            scene.removeEventFilter(KeyEvent.KEY_PRESSED, dayKeys);
        }
        dismissOnOutsideClick = null;
        dayKeys = null;
    }

    /** True when the event landed on the popover itself or on a day column. */
    private boolean isOwnNode(EventTarget target) {
        if (!(target instanceof Node node)) {
            return false;
        }
        for (Node current = node; current != null; current = current.getParent()) {
            if (popover.owns(current) || columnNodes.contains(current)) {
                return true;
            }
        }
        return false;
    }

    private void setExpanded(boolean expanded, boolean persist) {
        controls.panel().setVisible(expanded);
        controls.panel().setManaged(expanded);
        controls.sessionTotal().setDisable(false);
        if (!expanded) {
            // A popover floating over a panel that is no longer there would
            // outlive its own chart.
            closeDay();
        }
        followScreen();
        if (persist && persistExpanded != null) {
            persistExpanded.accept(expanded);
        }
    }

    /**
     * Today's bar grows while the panel is open, so it is polled rather than
     * left to go stale until the panel is reopened, but only while someone can
     * see it. Collapsed, cached behind another page or hidden with the window
     * in the tray, the panel costs nothing, and it is read again the moment it
     * is back.
     *
     * <p>The poll is an {@link FxTimer}, not a {@code Timeline}: an animation
     * playing for as long as the panel was open kept JavaFX pulsing at the
     * display refresh rate to deliver one update every thirty seconds.</p>
     */
    private void followScreen() {
        stopTimer();
        if (!controls.panel().isVisible() || !isOnScreen()) {
            return;
        }
        refresh();
        refreshTimer = FxTimer.every(REFRESH_PERIOD, this::refresh);
    }

    private boolean isOnScreen() {
        return onScreen != null && onScreen.getValue();
    }

    private void stopTimer() {
        if (refreshTimer != null) {
            refreshTimer.cancel();
            refreshTimer = null;
        }
    }
}
