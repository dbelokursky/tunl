package com.vlessclient.ui.view;

import com.vlessclient.app.UiTestServices;
import com.vlessclient.ui.view.dashboard.UpdateBannerSection;
import com.vlessclient.ui.view.dashboard.UpdateBannerSection.State;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dashboard's update banner: what it says, and — the part worth guarding —
 * that it says nothing at all until there is something to say. A banner that
 * shipped stuck open would greet every user with news of an update that does
 * not exist, on the one screen they cannot avoid.
 */
public class DashboardUpdateBannerTest extends ApplicationTest {

    @BeforeAll
    static void setupHeadless() {
        System.setProperty("testfx.robot", "glass");
        System.setProperty("testfx.headless", "true");
        System.setProperty("prism.order", "sw");
        System.setProperty("prism.text", "t2k");
        System.setProperty("java.awt.headless", "true");
        try {
            UiTestServices.initialize();
        } catch (Exception e) {
            // Tolerate service initialization failures in headless CI
        }
    }

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/DashboardView.fxml"));
        Parent root = loader.load();
        stage.setScene(new Scene(root, 640, 480));
        stage.show();
    }

    @Test
    void theBannerTakesNoSpaceWhileTheAppIsUpToDate() {
        HBox banner = lookup("#updateBanner").query();

        // Both flags matter: invisible alone still reserves the row, and the
        // dashboard would open with a gap above the status card.
        assertThat(banner.isVisible()).isFalse();
        assertThat(banner.isManaged()).isFalse();
    }

    @Test
    void nothingIsAnnouncedWithoutANewerRelease() {
        assertThat(UpdateBannerSection.stateFor(false, false, false, true))
                .isEqualTo(State.HIDDEN);
        // Staged but not newer cannot happen, and must still stay quiet.
        assertThat(UpdateBannerSection.stateFor(false, true, false, true))
                .isEqualTo(State.HIDDEN);
    }

    @Test
    void aStagedUpdateIsTheOnlyStateWithSomethingToPress() {
        assertThat(UpdateBannerSection.stateFor(true, true, false, true))
                .isEqualTo(State.READY);
        // Still downloading: the banner reports it, but there is no action —
        // the download runs on its own.
        assertThat(UpdateBannerSection.stateFor(true, false, true, true))
                .isEqualTo(State.DOWNLOADING);
    }

    /**
     * A download that is not running must not be announced as one.
     *
     * <p>The banner used to read "found but not staged" as proof a fetch was
     * under way. On a network where the fetch times out — the network this
     * client exists for — it announced a background download that had already
     * failed, and kept announcing it until the next check hours later, while
     * the restart it promised never arrived because nothing was ever staged.</p>
     */
    @Test
    void aFetchThatIsNotRunningIsNotReportedAsDownloading() {
        assertThat(UpdateBannerSection.stateFor(true, false, false, true))
                .isEqualTo(State.AVAILABLE);
    }

    @Test
    void aPackageManagedInstallIsNeverOfferedARestart() {
        // Linux: nothing was downloaded and nothing here can install it, so
        // the banner must not imply otherwise — even if a stale marker from an
        // earlier platform state claimed something was staged.
        for (boolean staged : new boolean[] {false, true}) {
            for (boolean downloading : new boolean[] {false, true}) {
                assertThat(UpdateBannerSection.stateFor(true, staged, downloading, false))
                        .as("staged=%s downloading=%s", staged, downloading)
                        .isEqualTo(State.PACKAGE_MANAGER);
            }
        }
    }
}
