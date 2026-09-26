package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.app.ThemeCss;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.TrafficHistoryStore;
import com.vlessclient.testing.UiTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javafx.css.PseudoClass;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

/**
 * Clicking a bar in the traffic history opens that day's breakdown.
 *
 * <p>The history is seeded through a shifted clock so the window has a busy
 * day that is not today: a card anchored to the last column would pass every
 * positioning assertion by accident.</p>
 *
 * <p>Every method gets a freshly loaded dashboard -- TestFX reuses the stage
 * but not the scene -- and the scene is dressed in the real stylesheets,
 * because the popover's size and the column's fill are both CSS.</p>
 */
@UiTest
public class DashboardTrafficDayPopoverTest extends ApplicationTest {

    private static final int WINDOW_DAYS = 30;

    /** Three days back: seeded, and far enough from either end to position. */
    private static final int BUSY_DAY = WINDOW_DAYS - 4;

    /** A day in the middle of the window that never saw a byte. */
    private static final int QUIET_DAY = 10;

    /** Five days back: more exits than the card has lines for. */
    private static final int CROWDED_DAY = WINDOW_DAYS - 6;

    private static final long BUSY_DAY_BYTES = 11_000;

    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    private static Path historyDir;
    private static Object priorStore;
    private static Object priorSettings;

    @BeforeAll
    static void seedHistory() throws IOException {
        priorStore = tryGet(TrafficHistoryStore.class);
        priorSettings = tryGet(AppSettings.class);

        AppSettings settings = new AppSettings();
        // The panel itself is not what is under test here, so every method
        // starts with it already open.
        settings.setTrafficHistoryExpanded(true);
        ServiceLocator.register(AppSettings.class, settings);

        historyDir = Files.createTempDirectory("traffic-day-popover");
        TrafficHistoryStore threeDaysAgo = new TrafficHistoryStore(historyDir,
                Clock.offset(Clock.systemDefaultZone(), Duration.ofDays(-3)));
        threeDaysAgo.record(server("a", "Amsterdam 01"), 1_000, 9_000);
        threeDaysAgo.record(server("b", "Frankfurt 02"), 500, 500);
        threeDaysAgo.flush();

        TrafficHistoryStore fiveDaysAgo = new TrafficHistoryStore(historyDir,
                Clock.offset(Clock.systemDefaultZone(), Duration.ofDays(-5)));
        fiveDaysAgo.record(server("c", "Exit 1"), 0, 1_000);
        fiveDaysAgo.record(server("d", "Exit 2"), 0, 900);
        fiveDaysAgo.record(server("e", "Exit 3"), 0, 800);
        fiveDaysAgo.record(server("f", "Exit 4"), 0, 700);
        fiveDaysAgo.record(server("g", "Exit 5"), 0, 600);
        fiveDaysAgo.flush();

        TrafficHistoryStore store = new TrafficHistoryStore(historyDir,
                Clock.systemDefaultZone());
        store.record(server("a", "Amsterdam 01"), 2_000, 3_000);
        ServiceLocator.register(TrafficHistoryStore.class, store);
    }

    private static <T> T tryGet(Class<T> type) {
        try {
            return ServiceLocator.get(type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @AfterAll
    static void restoreServices() {
        if (priorStore instanceof TrafficHistoryStore restored) {
            ServiceLocator.register(TrafficHistoryStore.class, restored);
        }
        ServiceLocator.register(AppSettings.class,
                priorSettings instanceof AppSettings restored ? restored : new AppSettings());
    }

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/DashboardView.fxml"));
        Parent root = loader.load();
        Scene scene = new Scene(root, 900, 700);
        // The card's own width and the column's transparent fill are both
        // stylesheet rules; an undressed scene would measure neither.
        scene.getStylesheets().addAll(ThemeCss.of("dark"));
        stage.setScene(scene);
        stage.show();
    }

    @BeforeEach
    void panelIsOpen() {
        assertThat(lookup("#trafficHistoryPanel").query().isVisible())
                .as("these tests start from an open panel")
                .isTrue();
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    void clickingADayOpensThatDaysBreakdown() {
        clickColumn(BUSY_DAY);

        assertThat(card().isVisible()).isTrue();
        assertThat(cardText())
                .as("the split the tooltip has no room for is the whole reason "
                        + "the card exists")
                .contains(TrafficText.bytes(BUSY_DAY_BYTES))
                .contains("Amsterdam 01")
                .contains("Frankfurt 02");
        assertThat(column(BUSY_DAY).getPseudoClassStates())
                .as("a floating card with nothing marking its day leaves the "
                        + "reader guessing which bar it belongs to")
                .contains(SELECTED);
    }

    @Test
    void theBreakdownNamesOnlyTheDayThatWasClicked() {
        clickColumn(WINDOW_DAYS - 1);

        assertThat(cardText())
                .as("today carried 5 KB of the window's 16 KB; a card summing "
                        + "the window would be a second copy of the panel")
                .contains(TrafficText.bytes(5_000))
                .doesNotContain(TrafficText.bytes(BUSY_DAY_BYTES));
    }

    /**
     * Five exits in one day against four lines: the card must still add up.
     */
    @Test
    void aDayWithMoreServersThanLinesFoldsTheRemainder() {
        clickColumn(CROWDED_DAY);

        assertThat(cardText())
                .as("the three busiest are named and the rest are summed, so "
                        + "the lines still total the figure above them")
                .contains("Exit 1", "Exit 2", "Exit 3")
                .doesNotContain("Exit 4", "Exit 5")
                .contains(TrafficText.bytes(700 + 600));
    }

    @Test
    void theSameDayClickedTwiceCloses() {
        clickColumn(BUSY_DAY);
        clickColumn(BUSY_DAY);

        assertThat(card().isVisible()).isFalse();
        assertThat(column(BUSY_DAY).getPseudoClassStates()).doesNotContain(SELECTED);
    }

    @Test
    void escapeClosesTheOpenDay() {
        clickColumn(BUSY_DAY);

        Node root = lookup("#trafficHistoryPanel").query().getScene().getRoot();
        interact(() -> root.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "",
                KeyCode.ESCAPE, false, false, false, false)));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(card().isVisible())
                .as("a card that floats over the chart has to be dismissable "
                        + "without hunting for the bar that opened it")
                .isFalse();
    }

    /**
     * An open day's arrow keys are filters on the whole scene. A page switch
     * left them there: on the next page an arrow meant for a text field
     * never reached it, and moved the day instead.
     */
    @Test
    void anArrowOnAnotherPageIsNotTakenByAnOpenDay() {
        clickColumn(BUSY_DAY);
        Scene scene = lookup("#trafficHistoryPanel").query().getScene();
        StackPane elsewhere = new StackPane();
        AtomicInteger arrived = new AtomicInteger();
        elsewhere.addEventHandler(KeyEvent.KEY_PRESSED, event -> arrived.incrementAndGet());
        // What a page switch does to the dashboard: it leaves the scene.
        interact(() -> scene.setRoot(elsewhere));
        interact(() -> elsewhere.fireEvent(keyPressed(KeyCode.RIGHT)));

        assertThat(arrived)
                .as("arrows that reached the page an open day was left behind on")
                .hasValue(1);
    }

    /**
     * The filters were removed through the panel's scene, which a page switch
     * had already taken away, so a click on the next page left them in place.
     * A day opened again added a second pair, and an arrow stepped two days.
     */
    @Test
    void aDayOpenedAgainAfterAPageSwitchStepsOneDayAtATime() {
        clickColumn(BUSY_DAY);
        Scene scene = lookup("#trafficHistoryPanel").query().getScene();
        Parent dashboard = scene.getRoot();
        TextField elsewhere = new TextField();
        interact(() -> scene.setRoot(new StackPane(elsewhere)));
        interact(() -> elsewhere.fireEvent(mousePressed()));
        interact(() -> scene.setRoot(dashboard));
        WaitForAsyncUtils.waitForFxEvents();

        clickColumn(BUSY_DAY);
        interact(() -> dashboard.fireEvent(keyPressed(KeyCode.RIGHT)));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(column(BUSY_DAY + 1).getPseudoClassStates()).contains(SELECTED);
        assertThat(column(BUSY_DAY + 2).getPseudoClassStates())
                .as("a second pair of filters stepping the day again")
                .doesNotContain(SELECTED);
    }

    private static KeyEvent keyPressed(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
    }

    @Test
    void aClickAnywhereElseClosesTheOpenDay() {
        clickColumn(BUSY_DAY);

        Node elsewhere = lookup("#connectButton").query();
        interact(() -> elsewhere.fireEvent(mousePressed()));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(card().isVisible()).isFalse();
    }

    /**
     * The regression the whole column exists for: twenty-five of the thirty
     * bars in a typical window are the two-pixel minimum, and a two-pixel
     * target cannot be hit.
     */
    @Test
    void aQuietDayIsStillAFullSizedTarget() {
        assertThat(ServiceLocator.get(TrafficHistoryStore.class)
                .lastDays(WINDOW_DAYS).get(QUIET_DAY).total())
                .as("the day really is a quiet one, so the target below is the "
                        + "case that matters")
                .isZero();

        Region bars = lookup("#trafficHistoryBars").query();
        Region column = column(QUIET_DAY);

        assertThat(column.getHeight())
                .as("the column spans the whole row, so every day is the same "
                        + "size to the pointer")
                .isEqualTo(bars.getHeight());
        assertThat(column.isPickOnBounds())
                .as("a Region is picked only where it paints, and this one is "
                        + "transparent")
                .isTrue();
    }

    @Test
    void aQuietDaySaysSoInsteadOfShowingThreeZeroes() {
        clickColumn(QUIET_DAY);

        assertThat(cardText())
                .as("most days in the window are this day; three zeroes read "
                        + "as a broken card rather than as an idle Tuesday")
                .doesNotContain(TrafficText.bytes(0));
        assertThat(card().isVisible()).isTrue();
    }

    /**
     * The reason this shape was chosen over an in-panel block: opening a day
     * must not reflow the dashboard under the pointer.
     */
    @Test
    void openingADayMovesNothingUnderneathIt() {
        Region panel = lookup("#trafficHistoryPanel").query();
        Label month = lookup("#trafficHistoryMonth").query();
        double panelHeight = panel.getHeight();
        Bounds before = month.localToScene(month.getBoundsInLocal());

        clickColumn(BUSY_DAY);

        assertThat(panel.getHeight()).isEqualTo(panelHeight);
        assertThat(month.localToScene(month.getBoundsInLocal()))
                .as("the card is unmanaged, so nothing it does reaches the "
                        + "layout pass")
                .isEqualTo(before);
    }

    @Test
    void theCardFloatsClearOfTheBarsItPointsAt() {
        clickColumn(BUSY_DAY);

        Region card = card();
        assertThat(card.getLayoutY() + card.getHeight())
                .as("the card sits above the bar row, not over the bars it is "
                        + "meant to be read against")
                .isLessThanOrEqualTo(0);
        assertThat(card.getLayoutX())
                .as("and never off the left edge of the panel")
                .isGreaterThanOrEqualTo(0);
    }

    private Region column(int index) {
        HBox bars = lookup("#trafficHistoryBars").query();
        return (Region) bars.getChildren().get(index);
    }

    /**
     * A synthetic MOUSE_CLICKED, not a robot click: Monocle's pointer lands on
     * this row on macOS and Linux x64 and misses it on Windows and Linux
     * arm64. Reachability is asserted separately, by size, in
     * {@link #aQuietDayIsStillAFullSizedTarget()}.
     */
    private void clickColumn(int index) {
        Region column = column(index);
        interact(() -> column.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED,
                0, 0, 0, 0, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, false, false, false, null)));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static MouseEvent mousePressed() {
        return new MouseEvent(MouseEvent.MOUSE_PRESSED,
                0, 0, 0, 0, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, false, false, false, null);
    }

    private Region card() {
        return lookup(".traffic-history-popover").queryAs(Region.class);
    }

    /**
     * What the card actually shows. The rows are built once and hidden rather
     * than rebuilt, so their stale text is still in the tree and a reader that
     * ignored visibility would assert against figures nobody can see.
     */
    private String cardText() {
        Region card = card();
        return card.lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .filter(node -> onScreen(node, card))
                .map(node -> ((Label) node).getText())
                .collect(Collectors.joining(" | "));
    }

    private static boolean onScreen(Node node, Node card) {
        for (Node current = node; current != null && current != card.getParent();
                current = current.getParent()) {
            if (!current.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private static ServerConfig server(String id, String name) {
        ServerConfig config = new ServerConfig();
        config.setId(id);
        config.setName(name);
        return config;
    }
}
