package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.platform.UpdateApplier.Hold;
import com.vlessclient.ui.view.dashboard.UpdateBannerSection;
import com.vlessclient.ui.view.dashboard.UpdateBannerSection.State;
import com.vlessclient.testing.UiTest;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dashboard's update banner: what it says, and — the part worth guarding —
 * that it says nothing at all until there is something to say. A banner that
 * shipped stuck open would greet every user with news of an update that does
 * not exist, on the one screen they cannot avoid.
 */
@UiTest
public class DashboardUpdateBannerTest extends ApplicationTest {

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
        assertThat(UpdateBannerSection.stateFor(false, false, Hold.NONE))
                .isEqualTo(State.HIDDEN);
        // Staged but not newer cannot happen, and must still stay quiet.
        assertThat(UpdateBannerSection.stateFor(false, true, Hold.NONE))
                .isEqualTo(State.HIDDEN);
    }

    @Test
    void aStagedUpdateIsAnnouncedWithItsRestart() {
        assertThat(UpdateBannerSection.stateFor(true, true, Hold.NONE))
                .isEqualTo(State.READY);
    }

    /**
     * An install that fetches its updates itself says nothing until the
     * installer is on disk.
     *
     * <p>The banner announced the release as soon as a check found it, then
     * "downloading in the background", then, once a fetch had failed, "not
     * downloaded yet": three states with nothing to press, while the app did
     * the fetching and the retrying on its own. The restart is the one step
     * that is the user's, so the banner comes up when it can be taken.</p>
     */
    @Test
    void anUpdateThatIsNotOnDiskYetIsNotAnnounced() {
        assertThat(UpdateBannerSection.stateFor(true, false, Hold.NONE))
                .isEqualTo(State.HIDDEN);
    }

    /**
     * A macOS copy running from where it was downloaded, translocated or on
     * the disk image, cannot update in place: the banner promised "installs
     * on next launch" at every launch, and the app fetched the installer
     * again every week. It asks for the move that makes updates work instead.
     */
    @Test
    void aCopyRunningFromTheDownloadIsAskedToMoveToApplications() {
        for (boolean staged : new boolean[] {false, true}) {
            assertThat(UpdateBannerSection.stateFor(true, staged,
                        Hold.MOVE_TO_APPLICATIONS))
                    .as("staged=%s", staged)
                    .isEqualTo(State.MOVE_TO_APPLICATIONS);
        }

        HBox banner = new HBox();
        Label title = new Label();
        Label hint = new Label();
        Button button = new Button();
        interact(() -> new UpdateBannerSection(
                new UpdateBannerSection.Controls(banner, title, hint, button))
                .render(State.MOVE_TO_APPLICATIONS, "1.23.0"));

        assertThat(banner.isVisible()).isTrue();
        assertThat(title.getText()).isEqualTo(I18n.get("dashboard.update.available", "1.23.0"));
        assertThat(hint.getText()).isEqualTo(I18n.get("dashboard.update.hint.move"));
        assertThat(button.isVisible()).as("a restart that installs nothing").isFalse();
    }

    @Test
    void aPackageManagedInstallIsNeverOfferedARestart() {
        // Linux: nothing was downloaded and nothing here can install it, so
        // the banner must not imply otherwise — even if a stale marker from an
        // earlier platform state claimed something was staged.
        for (boolean staged : new boolean[] {false, true}) {
            assertThat(UpdateBannerSection.stateFor(true, staged, Hold.PACKAGE_MANAGER))
                    .as("staged=%s", staged)
                    .isEqualTo(State.PACKAGE_MANAGER);
        }
    }

    /**
     * A build that cannot install this release (not an installed package, a
     * folder this account cannot write, or a release that failed to install
     * here twice) says where to get it, and offers no restart that would
     * install nothing.
     */
    @Test
    void anUpdateThisInstallCannotApplyPointsAtTheReleasePage() {
        for (boolean staged : new boolean[] {false, true}) {
            assertThat(UpdateBannerSection.stateFor(true, staged,
                        Hold.INSTALL_MANUALLY))
                    .as("staged=%s", staged)
                    .isEqualTo(State.INSTALL_MANUALLY);
        }

        HBox banner = new HBox();
        Label title = new Label();
        Label hint = new Label();
        Button button = new Button();
        interact(() -> new UpdateBannerSection(
                new UpdateBannerSection.Controls(banner, title, hint, button))
                .render(State.INSTALL_MANUALLY, "1.25.0"));

        assertThat(banner.isVisible()).isTrue();
        assertThat(hint.getText()).isEqualTo(I18n.get("dashboard.update.hint.manual"));
        assertThat(button.isVisible()).isFalse();
    }
}
