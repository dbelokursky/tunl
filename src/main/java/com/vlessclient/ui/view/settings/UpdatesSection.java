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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The "Updates" block of the Settings view, and the two version rows above it
 * that it keeps current.
 *
 * <p>Both rows read the same way: the version that is running, and in
 * parentheses whatever is worth knowing beyond it. The app row used to repeat
 * itself — a version on one line and a status block below restating it — so
 * the block is gone and the app row now says its piece the way the sing-box
 * row always has.</p>
 *
 * <p>The sing-box core is not updatable from here: it ships pinned with the
 * app and moves only when the app is updated. Its row does say when a newer
 * core exists, which is a nudge to open a bump pull request, not an offer to
 * install one. Extracted from
 * {@link com.vlessclient.ui.view.SettingsViewController}, which stays the FXML
 * endpoint and hands its injected controls over via {@link Controls}.</p>
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
            Label appVersionValue,
            Label singboxVersionValue,
            Button checkUpdatesButton,
            Button appUpdateButton) {
    }

    private final Label appVersionValue;
    private final Label singboxVersionValue;
    private final Button checkUpdatesButton;
    private final Button appUpdateButton;

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
        this.appVersionValue = controls.appVersionValue();
        this.singboxVersionValue = controls.singboxVersionValue();
        this.checkUpdatesButton = controls.checkUpdatesButton();
        this.appUpdateButton = controls.appUpdateButton();
    }

    /**
     * Builds the whole block: shows a placeholder while the sing-box version
     * is detected off the FX thread, then wires the app version row and the
     * "Check for updates" button.
     */
    public void init() {
        // detectSingBoxVersion() spawns a process and waits for it — keep
        // that off the FX thread so opening Settings never stalls.
        singboxVersionValue.setText(I18n.get("settings.updates.checking"));
        refreshSingBoxVersionAsync();
        initAppVersionRow();
        checkUpdatesButton.setOnAction(e -> runAppCheck());
        if (updateManager == null) {
            checkUpdatesButton.setVisible(false);
            checkUpdatesButton.setManaged(false);
        }
    }

    /**
     * Re-checks when the Settings view becomes visible again. The view is
     * cached, so {@link #init()} runs once per app run — without this hook the
     * row keeps showing the verdict of a check made before a release.
     * Re-checks at most every 5 minutes, and quietly: a failure leaves the last
     * verdict in place.
     */
    public void refreshOnOpen() {
        long now = System.currentTimeMillis();
        if (updateManager != null && now - lastOpenRefreshMs > OPEN_REFRESH_THROTTLE_MS) {
            lastOpenRefreshMs = now;
            runAppCheck();
        }
    }

    // ----- app version row -----

    private void initAppVersionRow() {
        try {
            updateManager = ServiceLocator.get(UpdateManager.class);
        } catch (IllegalArgumentException e) {
            updateManager = null;
            appVersionValue.setText(AppVersion.VERSION);
            hideRestartButton();
            return;
        }
        // The background periodic check updates these on the FX thread, so the
        // row reflects a newer release even without pressing the button.
        updateManager.updateAvailableProperty().addListener((o, ov, nv) -> renderAppVersion());
        updateManager.latestVersionProperty().addListener((o, ov, nv) -> renderAppVersion());
        // Same reason as the dashboard banner: the download finishing changes
        // no other property, so without this the row keeps saying an update is
        // merely available after it has been staged.
        updateManager.stagedProperty().addListener((o, ov, nv) -> renderAppVersion());
        // And this is what moves the row on to "downloading" and back off it.
        updateManager.downloadingProperty().addListener((o, ov, nv) -> renderAppVersion());
        appUpdateButton.setOnAction(e -> onRestartClicked());
        renderAppVersion();
    }

    /** What the app version row is saying beyond the version itself. */
    enum AppRowState {

        /** No newer release known. */
        UP_TO_DATE,

        /** Newer release exists, but this install updates through its packager. */
        PACKAGE_MANAGER,

        /** Its installer is being fetched right now. */
        DOWNLOADING,

        /** Found, and not being fetched at this instant. */
        AVAILABLE,

        /** Verified on disk and waiting for the restart that installs it. */
        STAGED;

        /**
         * Whether this state has anything for the user to press. True for
         * exactly one of them: a restart is the only step the app cannot take
         * on its own, since it means dropping the tunnel.
         */
        boolean offersRestart() {
            return this == STAGED;
        }
    }

    /**
     * Chooses the row's state. Pure, and a copy of the shape
     * {@code DashboardViewController.bannerState} already uses, so every
     * combination can be checked without standing up a scene.
     *
     * <p>{@link AppRowState#STAGED} is the only state with anything to press.
     * The download is never offered: every path that can notice an update also
     * starts fetching it, so a button here could only duplicate a download
     * already under way — and pressing it did exactly that, running a second
     * fetch of the same installer into the same staging path.</p>
     *
     * @param updateAvailable whether a newer release was found
     * @param staged          whether its installer is already verified on disk
     * @param downloading     whether that installer is being fetched right now
     * @param selfUpdates     whether this platform installs updates in-app
     * @return the state to render
     */
    static AppRowState rowState(boolean updateAvailable, boolean staged,
                                boolean downloading, boolean selfUpdates) {
        if (!updateAvailable) {
            return AppRowState.UP_TO_DATE;
        }
        if (!selfUpdates) {
            // Linux: the package belongs to whatever installed it, so the row
            // reports the new version and offers nothing to press.
            return AppRowState.PACKAGE_MANAGER;
        }
        if (staged) {
            return AppRowState.STAGED;
        }
        return downloading ? AppRowState.DOWNLOADING : AppRowState.AVAILABLE;
    }

    private void renderAppVersion() {
        if (updateManager == null) {
            return;
        }
        AppRowState state = rowState(
                updateManager.updateAvailableProperty().get(),
                updateManager.hasStagedUpdate(),
                updateManager.downloadingProperty().get(),
                UpdateApplier.current().selfUpdates());
        appUpdateButton.setVisible(state.offersRestart());
        appUpdateButton.setManaged(state.offersRestart());
        appVersionValue.setText(versionText(state));
    }

    /**
     * The running version, plus what is happening to it. Up to date is the one
     * state that adds nothing: a bare version number already says it, and a
     * parenthetical confirming the absence of news is noise on the row a user
     * reads most often.
     */
    private String versionText(AppRowState state) {
        String latest = updateManager.latestVersionProperty().get();
        return switch (state) {
            case UP_TO_DATE -> AppVersion.VERSION;
            case PACKAGE_MANAGER ->
                    I18n.get("settings.version.packagemanager", AppVersion.VERSION, latest);
            case DOWNLOADING ->
                    I18n.get("settings.version.downloading", AppVersion.VERSION, latest);
            case AVAILABLE -> I18n.get("settings.version.available", AppVersion.VERSION, latest);
            case STAGED -> I18n.get("settings.version.ready", AppVersion.VERSION, latest);
        };
    }

    private void hideRestartButton() {
        appUpdateButton.setVisible(false);
        appUpdateButton.setManaged(false);
    }

    /** Applies the staged update now; the dashboard banner offers the same. */
    private void onRestartClicked() {
        if (RestartToUpdate.start() == RestartToUpdate.Outcome.FAILED) {
            appVersionValue.setText(I18n.get("settings.version.applyfailed", AppVersion.VERSION));
        }
    }

    private void runAppCheck() {
        if (updateManager == null) {
            return;
        }
        appVersionValue.setText(I18n.get("settings.version.checking", AppVersion.VERSION));
        Thread t = new Thread(() -> {
            UpdateManager.CheckResult result = updateManager.checkForUpdates();
            Platform.runLater(() -> renderCheckResult(result));
            // The same follow-through the scheduled check and the tunnel-up
            // check do. Pressing "Check for updates" and being told one exists,
            // while nothing fetches it for up to six hours, was the gap left by
            // dropping the Download button.
            updateManager.autoDownloadIfAllowed();
            Platform.runLater(() -> renderDownloadOutcome(result));
        }, "app-update-check");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Says so when the download this check kicked off did not land.
     *
     * <p>Only for the manual path: the user pressed a button and is watching
     * the row, so silence reads as "nothing happened". The scheduled checks
     * leave the row alone — an error raised by a check nobody asked for would
     * still be sitting there hours later, describing a network that has since
     * come back.</p>
     */
    private void renderDownloadOutcome(UpdateManager.CheckResult result) {
        if (updateManager == null || result != UpdateManager.CheckResult.UPDATE_AVAILABLE) {
            return;
        }
        // Not downloading, or "nothing staged" is not a verdict yet. A fetch
        // started by the six-hourly check or by the tunnel coming up can still
        // be running: this check's own attempt was then declined rather than
        // failed — one installer at a time — and calling that a failed download
        // reports an error over a download that is going fine.
        if (UpdateApplier.current().selfUpdates()
                && !updateManager.hasStagedUpdate()
                && !updateManager.downloadingProperty().get()) {
            appVersionValue.setText(I18n.get("settings.version.downloadfailed",
                    AppVersion.VERSION, updateManager.latestVersionProperty().get()));
            return;
        }
        renderAppVersion();
    }

    /**
     * Renders what a check actually established.
     *
     * <p>Every outcome used to fall through to the one flag that says an update
     * exists — so a check that never reached GitHub was shown as "up to date",
     * with a green dot. That is the failure mode worth being careful about: it
     * is indistinguishable from good news, and it appears exactly in the
     * networks this client is used in.</p>
     */
    private void renderCheckResult(UpdateManager.CheckResult result) {
        boolean failed = result == UpdateManager.CheckResult.RATE_LIMITED
                || result == UpdateManager.CheckResult.UNREACHABLE;
        // A failed check does not un-know what an earlier one found: with an
        // update already waiting, that stays the more useful thing to say.
        if (!failed || updateManager.updateAvailableProperty().get()) {
            renderAppVersion();
            return;
        }
        appVersionValue.setText(I18n.get(result == UpdateManager.CheckResult.RATE_LIMITED
                ? "settings.version.ratelimited"
                : "settings.version.checkfailed", AppVersion.VERSION));
        hideRestartButton();
    }

    // ----- sing-box version label -----

    private void refreshSingBoxVersionAsync() {
        Thread t = new Thread(() -> {
            // Two steps, not one. The core's own version comes from a local
            // marker and is known at once; the release check behind
            // withAvailableCore() can block for ten seconds where
            // api.github.com is slow or blocked — which is the network this
            // app exists for. Chaining them left the row on its "checking…"
            // placeholder for that whole time, every time Settings opened,
            // to add a suffix that is usually not even there.
            String version = detectSingBoxVersion();
            Platform.runLater(() -> singboxVersionValue.setText(version));

            String annotated = withAvailableCore(version);
            if (!annotated.equals(version)) {
                Platform.runLater(() -> singboxVersionValue.setText(annotated));
            }
        }, "singbox-version-refresh");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Appends the newer core version when one has been released, in the same
     * shape the app row uses a few pixels above it.
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
                .map(latest -> I18n.get("settings.version.available", version, latest))
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
        // Bare "1.13.14", matching the app version row above it — the label
        // already says "sing-box version", so repeating it in the value read
        // as stutter.
        return version != null ? version : I18n.get("settings.version.unknown");
    }
}
