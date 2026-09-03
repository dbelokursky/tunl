package com.vlessclient.ui.view.dashboard;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.platform.UpdateApplier;
import com.vlessclient.service.UpdateManager;
import com.vlessclient.ui.view.RestartToUpdate;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

/**
 * The Dashboard's update banner: the one place a user who never opens
 * Settings finds out that a new release exists.
 *
 * <p>Split out of the controller because it is self-contained — four controls,
 * four properties on {@link UpdateManager} and one decision — and because that
 * decision is worth testing on its own. It mirrors
 * {@code settings.UpdatesSection}, which does the same job in Settings.</p>
 */
public class UpdateBannerSection {

    /**
     * The FXML-injected controls this section drives. They stay owned (and
     * declared) by the Dashboard controller; this record just carries them.
     */
    public record Controls(HBox banner, Label title, Label hint, Button button) {
    }

    /** What the dashboard should say about an update, if anything. */
    public enum State {

        /** Nothing newer exists, or there is no updater to ask. */
        HIDDEN,

        /** A newer release exists but this install updates through apt/AUR. */
        PACKAGE_MANAGER,

        /** Newer release seen, and its installer is being fetched right now. */
        DOWNLOADING,

        /** Seen, but nothing is fetching it at this moment. */
        AVAILABLE,

        /** Verified and staged — one restart away. */
        READY
    }

    private final HBox banner;
    private final Label title;
    private final Label hint;
    private final Button button;

    private UpdateManager updateManager;

    /** Creates the section over the given controls; nothing is wired until {@link #init()}. */
    public UpdateBannerSection(Controls controls) {
        this.banner = controls.banner();
        this.title = controls.title();
        this.hint = controls.hint();
        this.button = controls.button();
    }

    /**
     * Chooses the banner's state. Kept as a pure function of the facts it
     * depends on, so the combinations can be checked without a scene.
     *
     * <p>{@code downloading} is asked rather than inferred. The banner used to
     * treat "found but not staged" as proof a download was running and say so;
     * on a network where the fetch times out — the network this client exists
     * for — it then announced a background download that had already failed,
     * and went on announcing it until the next check hours later.</p>
     *
     * @param updateAvailable whether a newer release was found
     * @param staged          whether its installer is already verified on disk
     * @param downloading     whether that installer is being fetched right now
     * @param selfUpdates     whether this platform installs updates in-app
     * @return the state to render
     */
    public static State stateFor(boolean updateAvailable, boolean staged,
                                 boolean downloading, boolean selfUpdates) {
        if (!updateAvailable) {
            return State.HIDDEN;
        }
        if (!selfUpdates) {
            return State.PACKAGE_MANAGER;
        }
        if (staged) {
            return State.READY;
        }
        return downloading ? State.DOWNLOADING : State.AVAILABLE;
    }

    /**
     * Wires the banner to the updater's own properties, so a release found by
     * the background check surfaces here without the user going looking for it
     * in Settings.
     */
    public void init() {
        try {
            updateManager = ServiceLocator.get(UpdateManager.class);
        } catch (IllegalArgumentException e) {
            updateManager = null;
            refresh();
            return;
        }
        // The button's label is bound by the controller, which lives in the
        // package that owns ButtonLabels; everything about when the button is
        // shown and what it does belongs here.
        // The action is wired here rather than through the FXML's onAction, so
        // the handler sits with the state it acts on.
        button.setOnAction(e -> onRestartClicked());
        updateManager.updateAvailableProperty().addListener((o, was, is) -> refresh());
        updateManager.latestVersionProperty().addListener((o, was, is) -> refresh());
        // The one that turns "downloading" into an offer to restart. Without
        // it the banner never hears that the download finished: by then the
        // two properties above are already at their final values and fire
        // nothing more.
        updateManager.stagedProperty().addListener((o, was, is) -> refresh());
        // The fetch starting and stopping is now a fact the banner reads rather
        // than one it guesses, so it has to hear about both edges.
        updateManager.downloadingProperty().addListener((o, was, is) -> refresh());
        // The title carries a version number, so it cannot be a plain binding;
        // re-render instead when the language changes under it.
        I18n.localeProperty().addListener((o, was, is) -> refresh());
        refresh();
    }

    /** Re-renders the banner from the updater's current state. */
    public void refresh() {
        if (banner == null) {
            return;
        }
        State state = updateManager == null
                ? State.HIDDEN
                : stateFor(updateManager.updateAvailableProperty().get(),
                        updateManager.hasStagedUpdate(),
                        updateManager.downloadingProperty().get(),
                        UpdateApplier.current().selfUpdates());

        banner.setVisible(state != State.HIDDEN);
        banner.setManaged(state != State.HIDDEN);
        if (state == State.HIDDEN) {
            return;
        }

        String version = updateManager.latestVersionProperty().get();
        title.setText(state == State.READY
                ? I18n.get("dashboard.update.ready", version)
                : I18n.get("dashboard.update.available", version));
        String hintKey = switch (state) {
            case READY -> "dashboard.update.hint.staged";
            case PACKAGE_MANAGER -> "dashboard.update.hint.packagemanager";
            case AVAILABLE -> "dashboard.update.hint.pending";
            default -> "dashboard.update.hint.downloading";
        };
        hint.setText(I18n.get(hintKey));

        // Only the staged state has anything to press: the download runs on
        // its own, and a package-managed install is not ours to touch.
        boolean actionable = state == State.READY;
        button.setVisible(actionable);
        button.setManaged(actionable);
    }

    private void onRestartClicked() {
        if (RestartToUpdate.start() == RestartToUpdate.Outcome.FAILED) {
            hint.setText(I18n.get("settings.update.restart.failed"));
        }
    }
}
