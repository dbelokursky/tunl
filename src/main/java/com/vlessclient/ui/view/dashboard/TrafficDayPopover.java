package com.vlessclient.ui.view.dashboard;

import com.vlessclient.app.I18n;
import com.vlessclient.service.TrafficHistoryStore;
import com.vlessclient.service.TrafficMonitor;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * The card that opens above a day's bar in the traffic history and says what
 * that day was made of.
 *
 * <p>It is an <em>unmanaged</em> child of the bar row rather than a JavaFX
 * {@link javafx.stage.Popup}. Both float over the chart, but only this one
 * stays inside the scene: no second window to inherit stylesheets into, no
 * per-platform focus behaviour to work around, and a headless test can find it
 * with an ordinary {@code lookup()} instead of walking {@code
 * Window.getWindows()}. What it gives up is the ability to spill outside the
 * app window, which a card 210px wide anchored mid-dashboard never needed.</p>
 *
 * <p>Unmanaged is the whole point: the node is laid out by nobody, so opening
 * a day cannot move a pixel of the card underneath it. The cost is that
 * position is ours to maintain -- see {@link #showFor}, which is re-run on
 * every refresh and every resize.</p>
 *
 * <p>Every row exists from construction and is only shown, hidden and
 * relabelled. Building them on each open was the obvious shape and the wrong
 * one: a node added after its scene has been styled resolves its {@code -c-*}
 * lookups outside the normal CSS pass, and the suite caught it as a stylesheet
 * warning the moment another test swapped the scene out from under it.</p>
 */
public final class TrafficDayPopover {

    /** Distance from the tip of the caret down to the top of the bars. */
    private static final double GAP = 8;

    /** Side of the (square, rotated) caret before rotation. */
    private static final double CARET = 10;

    /** Half the caret's diagonal: how far it reaches below its own centre. */
    private static final double CARET_REACH = CARET * Math.sqrt(2) / 2;

    /**
     * Server lines the card holds. Four, of which the last is the remainder
     * when a day used more than four exits: a card that grew with the server
     * list could end up taller than the dashboard it floats over, and the
     * question a day is opened with is "what was this, and roughly through
     * what", not "account for every exit".
     */
    private static final int SERVER_ROWS = 4;

    private final StackPane host;
    private final VBox card = new VBox(3);
    private final Region caret = new Region();
    private final Label heading = new Label();
    private final VBox body = new VBox(2);

    private final Label idle = new Label();
    private final Row total = new Row(true);
    private final Row upload = new Row(false);
    private final Row download = new Row(false);
    private final Region separator = new Region();
    private final List<Row> serverRows = new ArrayList<>();

    private LocalDate shownDate;

    /**
     * Builds the card and attaches it, hidden, to the bar row.
     *
     * @param host the {@code StackPane} wrapping the bars, which supplies the
     *     coordinate space the card is positioned in
     */
    public TrafficDayPopover(StackPane host) {
        this.host = host;

        heading.getStyleClass().add("traffic-history-popover-heading");
        heading.setWrapText(true);
        idle.getStyleClass().add("traffic-history-popover-label");

        separator.getStyleClass().add("traffic-history-popover-separator");
        // A VBox sizes a child to the child's own maximum, and a Region with
        // no content computes that as zero -- the rule would draw nothing.
        separator.setMaxWidth(Double.MAX_VALUE);
        VBox.setMargin(separator, new Insets(4, 0, 4, 0));

        body.getChildren().addAll(idle, total.node, upload.node, download.node, separator);
        for (int i = 0; i < SERVER_ROWS; i++) {
            Row row = new Row(false);
            serverRows.add(row);
            body.getChildren().add(row.node);
        }

        card.getStyleClass().add("traffic-history-popover");
        card.getChildren().setAll(heading, body);
        card.setManaged(false);
        card.setVisible(false);

        caret.getStyleClass().add("traffic-history-popover-caret");
        caret.setPrefSize(CARET, CARET);
        caret.setRotate(45);
        caret.setManaged(false);
        caret.setVisible(false);

        // Caret last, so its filled half paints over the card's bottom border
        // and leaves the two edges that actually point at the bar.
        host.getChildren().addAll(card, caret);
    }

    /**
     * The card itself.
     *
     * @return the popover's node, for tests and for hit-testing outside
     *     clicks
     */
    public Region node() {
        return card;
    }

    /**
     * Whether a node belongs to this popover.
     *
     * @param node the node an event landed on
     * @return true for the card and for the caret, which is its sibling
     *     rather than its child and would otherwise read as an outside click
     */
    public boolean owns(Node node) {
        return node == card || node == caret;
    }

    /**
     * The day on screen.
     *
     * @return the day currently open, or null when the card is hidden
     */
    public LocalDate shownDate() {
        return card.isVisible() ? shownDate : null;
    }

    /**
     * Fills the card with one day and floats it above that day's column.
     *
     * <p>Safe to call again for the day already open: that is how the card
     * keeps up while today's bar is still growing.</p>
     *
     * @param date the day being opened
     * @param day that day's totals
     * @param servers that day's traffic split by server, busiest first
     * @param column the bar column to anchor to
     */
    public void showFor(LocalDate date, TrafficHistoryStore.DayTotal day,
                        List<TrafficHistoryStore.ServerTotal> servers, Region column) {
        shownDate = date;
        fill(date, day, servers);
        card.setVisible(true);
        caret.setVisible(true);
        position(column);
    }

    /** Closes the card. A no-op when it is already closed. */
    public void hide() {
        card.setVisible(false);
        caret.setVisible(false);
        shownDate = null;
    }

    /**
     * Writes the day into the card.
     *
     * <p>The order is deliberate and is the whole editorial decision in this
     * class: the day's total first, because that is what the bar the user
     * clicked represents and the one number they came for; the two directions
     * under it, because "12 GB down, 1 GB up" is what tells a download from a
     * backup; the servers last, because they only matter once the size has
     * registered. A day with nothing in it says so in one line rather than
     * showing three zeroes -- most days in a 30-day window are that day, and
     * three zeroes read as a broken card.</p>
     */
    private void fill(LocalDate date, TrafficHistoryStore.DayTotal day,
                      List<TrafficHistoryStore.ServerTotal> servers) {
        heading.setText(date.format(DateTimeFormatter
                .ofLocalizedDate(FormatStyle.FULL)
                .withLocale(I18n.getLocale())));

        boolean quiet = day.total() == 0;
        idle.setText(I18n.get("dashboard.traffic.history.day.idle"));
        show(idle, quiet);
        show(total.node, !quiet);
        show(upload.node, !quiet);
        show(download.node, !quiet);
        show(separator, !quiet && !servers.isEmpty());

        total.set(I18n.get("dashboard.traffic.history.day.total"),
                TrafficMonitor.formatBytes(day.total()));
        upload.set(I18n.get("dashboard.traffic.history.day.upload"),
                TrafficMonitor.formatBytes(day.upload()));
        download.set(I18n.get("dashboard.traffic.history.day.download"),
                TrafficMonitor.formatBytes(day.download()));
        fillServers(quiet ? List.of() : servers);
    }

    /**
     * Names the day's servers, folding whatever does not fit into the last
     * line so the figures on screen still add up to the total above them.
     */
    private void fillServers(List<TrafficHistoryStore.ServerTotal> servers) {
        boolean folding = servers.size() > SERVER_ROWS;
        int named = folding ? SERVER_ROWS - 1 : servers.size();
        for (int i = 0; i < SERVER_ROWS; i++) {
            Row row = serverRows.get(i);
            boolean isRemainder = folding && i == SERVER_ROWS - 1;
            if (i >= named && !isRemainder) {
                show(row.node, false);
                continue;
            }
            show(row.node, true);
            if (isRemainder) {
                long rest = servers.subList(named, servers.size()).stream()
                        .mapToLong(TrafficHistoryStore.ServerTotal::total).sum();
                row.set(I18n.get("dashboard.traffic.history.day.others"),
                        TrafficMonitor.formatBytes(rest));
            } else {
                TrafficHistoryStore.ServerTotal server = servers.get(i);
                String name = server.serverName() == null || server.serverName().isBlank()
                        ? I18n.get("dashboard.traffic.history.unknown.server")
                        : server.serverName();
                row.set(name, TrafficMonitor.formatBytes(server.total()));
            }
        }
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    /**
     * Floats the card above the column, clamped to the bar row so a day at
     * either end of the month does not push it off the card.
     */
    private void position(Region column) {
        card.applyCss();
        card.autosize();
        caret.autosize();

        Bounds anchor = host.sceneToLocal(column.localToScene(column.getBoundsInLocal()));
        double centre = (anchor.getMinX() + anchor.getMaxX()) / 2;
        double width = card.getWidth();
        double bottom = -(GAP + CARET_REACH);

        double x = Math.max(0, Math.min(centre - width / 2,
                Math.max(0, host.getWidth() - width)));
        card.relocate(x, bottom - card.getHeight());
        // The caret stays on the column even when the card has been clamped
        // sideways -- it is the part that says which day is open.
        caret.relocate(centre - CARET / 2, bottom - CARET / 2);
    }

    /** One "label ... value" line, built once and relabelled on each open. */
    private static final class Row {

        private final Label name = new Label();
        private final Label amount = new Label();
        private final HBox node;

        Row(boolean strong) {
            name.getStyleClass().add("traffic-history-popover-label");
            // The name is the half allowed to give up width: a truncated
            // server name is still recognisable, a truncated byte figure is a
            // wrong number.
            name.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(name, Priority.ALWAYS);

            amount.getStyleClass().add(strong
                    ? "traffic-history-popover-total" : "traffic-history-popover-value");
            amount.setMinWidth(Region.USE_PREF_SIZE);

            node = new HBox(8, name, amount);
            node.getStyleClass().add("traffic-history-popover-row");
        }

        void set(String label, String value) {
            name.setText(label);
            amount.setText(value);
        }
    }
}
