package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.app.ThemeCss;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.service.ConnectionService;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.testing.UiTest;
import com.vlessclient.ui.view.dashboard.UpdateBannerSection;
import java.nio.file.Path;
import java.util.Locale;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * The buttons of the dashboard's banners keep the width their label asks for.
 *
 * <p>An HBox short of room shrinks every managed child, whatever its
 * {@code hgrow}: {@code growOrShrinkAreaWidths} adjusts them all when it
 * shrinks. So the wrapping text beside a button does not yield first, and
 * nothing in the FXML shields these buttons — the update banner's comment
 * used to say its {@code minWidth=0} text did. Both banners are short of room
 * here: they ask for 598 and 768 px of the 500 they get.</p>
 *
 * <p>What keeps the update and install buttons whole is
 * {@code ButtonLabels.bind}, which pins a button to its widest label. That is
 * what this guards: relabelled through {@code bindStatic}, which binds the
 * text and pins nothing, the three measured 93 px of the 141.5
 * «Перезапустить» needs, 57 of 120.9 and 82 of 175.3 — an ellipsis on each.
 * The two reconnect buttons are bound that way, so the FXML pins them with
 * {@code minWidth="-Infinity"}, as {@code recheckButton} has; without it
 * «Переподключить» came out as «Переподк…».</p>
 *
 * <p>Russian at 500 px with Verdana: the window's narrowest content width, and
 * the font the Linux runners have, where Cyrillic measures about a third wider
 * than on macOS.</p>
 */
@UiTest
public class DashboardBannerButtonWidthTest extends ApplicationTest {

    /** Window min 760 - sidebar 200 - content padding 48 = 512; a hair tighter. */
    private static final double CONTENT_WIDTH = 500;

    /** Engine whose connection state the test drives directly. */
    private static final class FakeEngine extends SingBoxEngine {
        private final SimpleObjectProperty<ConnectionState> state =
                new SimpleObjectProperty<>(ConnectionState.DISCONNECTED);

        FakeEngine() {
            super(Path.of("sing-box-not-used-in-tests"));
        }

        @Override
        public ReadOnlyObjectProperty<ConnectionState> connectionStateProperty() {
            return state;
        }
    }

    private static final FakeEngine ENGINE = new FakeEngine();
    private static final ReadOnlyBooleanWrapper DROPPED = new ReadOnlyBooleanWrapper(true);

    private Scene scene;

    /**
     * Russian, and a connection whose settings have moved on and whose tunnel
     * has dropped, so both reconnect notices are up with something to press.
     */
    @BeforeAll
    static void inRussianWithBothNotices() {
        I18n.setLocale(Locale.of("ru"));
        AppSettings settings = new AppSettings();
        settings.setHealthCheckEnabled(false);
        ServiceLocator.register(AppSettings.class, settings);
        ServiceLocator.register(SingBoxEngine.class, ENGINE);
        ServiceLocator.register(ConnectionService.class,
                new ConnectionService(null, null, null, SingBoxEngine.withoutCore()) {
                    @Override
                    public boolean runsCurrentSettings() {
                        return false;
                    }

                    @Override
                    public ReadOnlyBooleanProperty reconnectNeededProperty() {
                        return DROPPED.getReadOnlyProperty();
                    }
                });
    }

    @AfterAll
    static void backToEnglish() {
        I18n.setLocale(Locale.ENGLISH);
    }

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/DashboardView.fxml"));
        Parent root = loader.load();
        root.setStyle("-fx-font-family: \"Verdana\";");
        scene = new Scene(root, CONTENT_WIDTH, 640);
        scene.getStylesheets().addAll(ThemeCss.light());
        stage.setScene(scene);
        stage.show();
    }

    /**
     * The update banner is rendered through its own section, so the title and
     * the hint are the texts the app shows. The state is set rather than
     * reached: the button belongs to a staged update, and whether a platform
     * stages one at all is decided by {@code UpdateApplier}, which answers no
     * on the Linux runners.
     */
    @Test
    void theRestartButtonKeepsItsLabel() {
        HBox banner = lookup("#updateBanner").query();
        Button button = lookup("#updateBannerButton").queryButton();
        interact(() -> {
            new UpdateBannerSection(new UpdateBannerSection.Controls(banner,
                    lookup("#updateBannerTitle").query(), lookup("#updateBannerHint").query(),
                    button))
                    .render(UpdateBannerSection.State.READY, "1.21.0");
            layOut();
        });

        assertKeepsItsLabel(button, "#updateBannerButton");
    }

    /**
     * The install banner is up only on a machine with no sing-box, which is
     * the CI runners and not this one, so it is shown here the way
     * {@code ControlSizingTest} shows it: it is a state the app really has.
     */
    @Test
    void theInstallBannersButtonsKeepTheirLabels() {
        HBox banner = lookup("#singBoxMissingBanner").query();
        Button copy = lookup("#copyBrewButton").queryButton();
        Button retry = lookup("#retryInstallButton").queryButton();
        interact(() -> {
            banner.setVisible(true);
            banner.setManaged(true);
            layOut();
        });

        assertKeepsItsLabel(copy, "#copyBrewButton");
        assertKeepsItsLabel(retry, "#retryInstallButton");
    }

    /**
     * The two reconnect notices: one for settings the running core no longer
     * matches, one for a tunnel that dropped. Each is a wrapping label beside
     * its button, and neither button is pinned by ButtonLabels — bindStatic
     * only binds the text — so the FXML pins them.
     */
    @Test
    void theReconnectButtonsKeepTheirLabels() {
        Button pending = lookup("#pendingChangesButton").queryButton();
        Button dropped = lookup("#tunnelDroppedButton").queryButton();
        interact(() -> {
            ENGINE.state.set(ConnectionState.CONNECTED);
            layOut();
        });

        assertKeepsItsLabel(pending, "#pendingChangesButton");
        assertKeepsItsLabel(dropped, "#tunnelDroppedButton");

        interact(() -> ENGINE.state.set(ConnectionState.DISCONNECTED));
    }

    /** Lays the scene out as a shown window does, CSS first. */
    private void layOut() {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
    }

    /**
     * The button is on screen and as wide as its label asks. A button its HBox
     * shrank draws the label with an ellipsis, which no assertion on the text
     * would catch: {@code getText()} stays whole, and only the width gives it
     * away.
     */
    private void assertKeepsItsLabel(Button button, String selector) {
        double[] measured = new double[3];
        boolean[] shown = new boolean[1];
        interact(() -> {
            measured[0] = button.getWidth();
            measured[1] = button.prefWidth(-1);
            Bounds inScene = button.localToScene(button.getBoundsInLocal());
            measured[2] = inScene.getMaxX();
            shown[0] = button.isVisible() && button.isManaged();
        });

        assertThat(shown[0]).as("%s is shown", selector).isTrue();
        assertThat(measured[0])
                .withFailMessage("%s got %.1fpx of the %.1f its label needs, so it draws the "
                                + "label with an ellipsis at a %.0fpx window",
                        selector, measured[0], measured[1], CONTENT_WIDTH)
                .isGreaterThanOrEqualTo(measured[1] - 0.5);
        assertThat(measured[2])
                .withFailMessage("%s ends at %.1f, past the %.0fpx viewport, where nothing "
                                + "scrolls sideways to reach it",
                        selector, measured[2], CONTENT_WIDTH)
                .isLessThanOrEqualTo(CONTENT_WIDTH + 0.5);
    }
}
