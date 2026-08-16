package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.ui.view.DashboardViewController.UpdateBannerState;
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
            ServiceLocator.initialize();
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
        assertThat(DashboardViewController.bannerState(false, false, true))
                .isEqualTo(UpdateBannerState.HIDDEN);
        // Staged but not newer cannot happen, and must still stay quiet.
        assertThat(DashboardViewController.bannerState(false, true, true))
                .isEqualTo(UpdateBannerState.HIDDEN);
    }

    @Test
    void aStagedUpdateIsTheOnlyStateWithSomethingToPress() {
        assertThat(DashboardViewController.bannerState(true, true, true))
                .isEqualTo(UpdateBannerState.READY);
        // Still downloading: the banner reports it, but there is no action —
        // the download runs on its own.
        assertThat(DashboardViewController.bannerState(true, false, true))
                .isEqualTo(UpdateBannerState.DOWNLOADING);
    }

    @Test
    void aPackageManagedInstallIsNeverOfferedARestart() {
        // Linux: nothing was downloaded and nothing here can install it, so
        // the banner must not imply otherwise — even if a stale marker from an
        // earlier platform state claimed something was staged.
        assertThat(DashboardViewController.bannerState(true, false, false))
                .isEqualTo(UpdateBannerState.PACKAGE_MANAGER);
        assertThat(DashboardViewController.bannerState(true, true, false))
                .isEqualTo(UpdateBannerState.PACKAGE_MANAGER);
    }
}
