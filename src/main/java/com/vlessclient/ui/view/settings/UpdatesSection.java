package com.vlessclient.ui.view.settings;

import com.vlessclient.app.AppVersion;
import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.platform.UpdateApplier;
import com.vlessclient.service.SingBoxInstaller;
import com.vlessclient.service.SingBoxReleases;
import com.vlessclient.service.UpdateManager;
import com.vlessclient.ui.view.RestartToUpdate;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The "Updates" block of the Settings view: the app-update row and async
 * detection of the sing-box version shown under About. The sing-box core
 * itself is not updatable from here — it ships pinned with the app and moves
 * only when the app is updated. The row does say when a newer core exists,
 * which is a nudge to open a bump pull request, not an offer to install one.
 * Extracted from {@link com.vlessclient.ui.view.SettingsViewController},
 * which stays the FXML endpoint and hands its injected controls over via
 * {@link Controls}.
 */
public final class UpdatesSection {

    private static final Logger log = LoggerFactory.getLogger(UpdatesSection.class);

    /** Re-check for a new app release at most this often on Settings opens. */
    private static final long OPEN_REFRESH_THROTTLE_MS = 5L * 60 * 1000;

    /**
     * The FXML-injected controls this section drives. They remain owned (and
     * declared) by the Settings controller; this record just carries them.
     */
    public record Controls(
            Label singboxVersionValue,
            Button checkUpdatesButton,
            VBox appUpdateRow,
            Circle appUpdateDot,
            Label appUpdateDetail,
            Button downloadAppButton,
            java.util.function.Consumer<String> appButtonLabel) {
    }

    private final Label singboxVersionValue;
    private final Button checkUpdatesButton;
    private final VBox appUpdateRow;
    private final Circle appUpdateDot;
    private final Label appUpdateDetail;
    private final Button downloadAppButton;
    /** Shows one of the button's bound labels; see {@link Controls}. */
    private final java.util.function.Consumer<String> appButtonLabel;

    /** Asked once per run, from the background thread below. */
    private final SingBoxReleases singBoxReleases = new SingBoxReleases();

    private UpdateManager updateManager;
    /** When the last open-triggered app check started; 0 = never. */
    private long lastOpenRefreshMs;

    /**
     * Creates the section over the given controls; nothing is wired until
     * {@link #init()} runs.
     */
    public UpdatesSection(Controls controls) {
        this.singboxVersionValue = controls.singboxVersionValue();
        this.checkUpdatesButton = controls.checkUpdatesButton();
        this.appUpdateRow = controls.appUpdateRow();
        this.appUpdateDot = controls.appUpdateDot();
        this.appUpdateDetail = controls.appUpdateDetail();
        this.downloadAppButton = controls.downloadAppButton();
        this.appButtonLabel = controls.appButtonLabel();
    }

    /**
     * Builds the whole block: shows a placeholder while the sing-box version
     * is detected off the FX thread, then wires the app-update row and the
     * "Check for updates" button.
     */
    public void init() {
        // detectSingBoxVersion() spawns a process and waits for it — keep
        // that off the FX thread so opening Settings never stalls.
        singboxVersionValue.setText(I18n.get("settings.updates.checking"));
        refreshSingBoxVersionAsync();
        initAppUpdateRow();
        checkUpdatesButton.setOnAction(e -> runAppCheck());
        if (updateManager == null) {
            checkUpdatesButton.setVisible(false);
            checkUpdatesButton.setManaged(false);
        }
    }

    /**
     * Refreshes the app-update row when the Settings view becomes visible
     * again. The view is cached, so {@link #init()} runs once per app run —
     * without this hook the row keeps showing the verdict of a check made
     * before a release. Re-checks at most every 5 minutes, and quietly:
     * a failure leaves the last verdict in place.
     */
    public void refreshOnOpen() {
        long now = System.currentTimeMillis();
        if (updateManager != null && now - lastOpenRefreshMs > OPEN_REFRESH_THROTTLE_MS) {
            lastOpenRefreshMs = now;
            runAppCheck();
        }
    }

    // ----- app update row -----

    private void initAppUpdateRow() {
        try {
            updateManager = ServiceLocator.get(UpdateManager.class);
        } catch (IllegalArgumentException e) {
            updateManager = null;
            appUpdateRow.setVisible(false);
            appUpdateRow.setManaged(false);
            return;
        }
        // The button's label and action depend on which of the three states
        // the row is in, so renderAppRow() owns both.
        // The background periodic check updates these on the FX thread, so the
        // row reflects a newer release even without pressing the button.
        updateManager.updateAvailableProperty().addListener((o, ov, nv) -> renderAppRow());
        updateManager.latestVersionProperty().addListener((o, ov, nv) -> renderAppRow());
        renderAppRow();
    }

    private void renderAppRow() {
        if (updateManager == null) {
            return;
        }
        if (!updateManager.updateAvailableProperty().get()) {
            setUpdateRow(I18n.get("settings.updates.uptodate"), "update-status-ok");
            hideAppButton();
            return;
        }
        if (!UpdateApplier.current().selfUpdates()) {
            // Linux: the package belongs to whatever installed it, so the row
            // reports the new version and offers nothing to press.
            setUpdateRow(I18n.get("settings.update.packagemanager"), "update-status-available");
            hideAppButton();
            return;
        }
        if (updateManager.hasStagedUpdate()) {
            // Already downloaded and verified, in this run or an earlier one:
            // all that is left is the restart that lets it be installed.
            setUpdateRow(I18n.get("settings.update.staged"), "update-status-ok");
            showAppButton("settings.update.restart", this::onRestartClicked);
            return;
        }
        String latest = updateManager.latestVersionProperty().get();
        setUpdateRow(AppVersion.VERSION + "  →  " + latest, "update-status-available");
        showAppButton("settings.update.download", this::onDownloadAppClicked);
    }

    private void showAppButton(String labelKey, Runnable action) {
        appButtonLabel.accept(labelKey);
        downloadAppButton.setOnAction(e -> action.run());
        downloadAppButton.setVisible(true);
        downloadAppButton.setManaged(true);
    }

    private void hideAppButton() {
        downloadAppButton.setVisible(false);
        downloadAppButton.setManaged(false);
    }

    /** Applies the staged update now; the dashboard banner offers the same. */
    private void onRestartClicked() {
        if (RestartToUpdate.start() == RestartToUpdate.Outcome.FAILED) {
            setUpdateRow(I18n.get("settings.update.restart.failed"), "update-status-error");
        }
    }

    private void runAppCheck() {
        if (updateManager == null) {
            return;
        }
        setUpdateRow(I18n.get("settings.updates.checking"), "update-status-muted");
        Thread t = new Thread(() -> {
            UpdateManager.CheckResult result = updateManager.checkForUpdates();
            Platform.runLater(() -> renderCheckResult(result));
        }, "app-update-check");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Renders what a check actually established.
     *
     * <p>Previously every outcome fell through to {@link #renderAppRow()},
     * which reads one flag — so a check that never reached GitHub was shown as
     * "up to date", with a green dot. That is the failure mode worth being
     * careful about: it is indistinguishable from good news, and it appears
     * exactly in the networks this client is used in.</p>
     */
    private void renderCheckResult(UpdateManager.CheckResult result) {
        boolean failed = result == UpdateManager.CheckResult.RATE_LIMITED
                || result == UpdateManager.CheckResult.UNREACHABLE;
        // A failed check does not un-know what an earlier one found: with an
        // update already waiting, that stays the more useful thing to say.
        if (!failed || updateManager.updateAvailableProperty().get()) {
            renderAppRow();
            return;
        }
        setUpdateRow(I18n.get(result == UpdateManager.CheckResult.RATE_LIMITED
                ? "settings.update.check.ratelimited"
                : "settings.update.check.failed"), "update-status-error");
        hideAppButton();
    }

    private void onDownloadAppClicked() {
        if (updateManager == null) {
            return;
        }
        String url = updateManager.downloadUrlProperty().get();
        if (url == null || url.isBlank()) {
            return;
        }
        downloadAppButton.setDisable(true);
        setUpdateRow(I18n.get("settings.update.downloading"), "update-status-muted");
        Thread t = new Thread(() -> {
            java.nio.file.Path saved = updateManager.downloadUpdate(url);
            Platform.runLater(() -> {
                downloadAppButton.setDisable(false);
                if (saved != null) {
                    setUpdateRow(I18n.get("settings.update.staged"), "update-status-ok");
                    downloadAppButton.setVisible(false);
                    downloadAppButton.setManaged(false);
                } else {
                    setUpdateRow(I18n.get("settings.update.download.failed"),
                            "update-status-error");
                }
            });
        }, "app-update-download");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Sets the app row's detail text + colour and its status dot. The dot
     * carries the state (green ok / amber available / red error / grey idle),
     * so no leading glyph is needed in the text.
     */
    private void setUpdateRow(String text, String modifier) {
        appUpdateDetail.setText(text == null ? "" : text);
        // update-item-detail indents the status under the item name; its
        // padding wins over update-status because it's defined later in the CSS.
        appUpdateDetail.getStyleClass().setAll("update-status", "update-item-detail", modifier);
        appUpdateDot.getStyleClass().setAll(dotClassFor(modifier));
    }

    private static String dotClassFor(String modifier) {
        return switch (modifier) {
            case "update-status-ok" -> "status-circle-connected";
            case "update-status-available" -> "status-circle-connecting";
            case "update-status-error" -> "status-circle-error";
            default -> "status-circle-disconnected";
        };
    }

    // ----- sing-box version label -----

    private void refreshSingBoxVersionAsync() {
        Thread t = new Thread(() -> {
            String label = withAvailableCore(detectSingBoxVersion());
            Platform.runLater(() -> singboxVersionValue.setText(label));
        }, "singbox-version-refresh");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Appends the newer core version when one has been released.
     *
     * <p>Reporting only — nothing here installs a core. The pin moves through
     * a reviewed pull request, where the real-binary smoke suite gets to
     * decide whether the new version still matches the config generator; this
     * line is what tells a maintainer to go and open one.</p>
     *
     * <p>Skipped when the core's own version is unknown: there is nothing to
     * compare against, and it saves a request in exactly the case where the
     * answer could not be used anyway.</p>
     */
    private String withAvailableCore(String version) {
        if (version == null || version.isBlank()
                || version.equals(I18n.get("settings.version.unknown"))) {
            return version;
        }
        return singBoxReleases.newerThan(version)
                .map(latest -> I18n.get("settings.singbox.update.available", version, latest))
                .orElse(version);
    }

    /**
     * The running core's version for the About row. Delegates to
     * {@link SingBoxInstaller#detectVersion(java.nio.file.Path)}, which answers
     * from the cache marker for the managed binary — so opening Settings
     * usually spawns no process at all.
     */
    private String detectSingBoxVersion() {
        String singBoxPath = ServiceLocator.getSingBoxPath();
        if (singBoxPath == null) {
            return I18n.get("settings.version.unknown");
        }
        String version;
        try {
            version = ServiceLocator.get(SingBoxInstaller.class)
                    .detectVersion(java.nio.file.Path.of(singBoxPath));
        } catch (IllegalArgumentException e) {
            log.debug("SingBoxInstaller not available");
            return I18n.get("settings.version.unknown");
        }
        // Bare "1.13.14", matching the App Version row above it — the label
        // already says "sing-box Version", so repeating it in the value read
        // as stutter.
        return version != null ? version : I18n.get("settings.version.unknown");
    }
}
