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
 * three properties on {@link UpdateManager} and one decision — and because that
 * decision is worth testing on its own. {@code settings.UpdatesSection} keeps
 * the details, the download under way among them.</p>
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

        /**
         * Nothing newer exists, there is no updater to ask, or this install
         * fetches the newer release itself and has not got it on disk yet.
         */
        HIDDEN,

        /** A newer release exists but this install updates through apt/AUR. */
        PACKAGE_MANAGER,

        /**
         * Newer release exists, and this macOS copy runs from where it was
         * downloaded, which cannot be updated in place.
         */
        MOVE_TO_APPLICATIONS,

        /**
         * Newer release exists, and this installation cannot install it: the
         * user downloads it from the release page.
         */
        INSTALL_MANUALLY,

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
     * <p>An install that fetches its updates itself says nothing until the
     * installer is on disk. The banner used to announce the release as soon as
     * a check found it, then "downloading in the background", then, after a
     * failed fetch, "not downloaded yet": news with nothing to press, on the
     * one screen a user cannot avoid, while the app did the fetching and
     * retried it on its own. The restart is the only step that is the user's,
     * so the banner appears when it can be taken.</p>
     *
     * @param updateAvailable whether a newer release was found
     * @param staged          whether its installer is already verified on disk
     * @param hold            what keeps this installation from updating itself
     * @return the state to render
     */
    public static State stateFor(boolean updateAvailable, boolean staged,
                                 UpdateApplier.Hold hold) {
        if (!updateAvailable) {
            return State.HIDDEN;
        }
        switch (hold) {
            case PACKAGE_MANAGER -> {
                return State.PACKAGE_MANAGER;
            }
            case MOVE_TO_APPLICATIONS -> {
                return State.MOVE_TO_APPLICATIONS;
            }
            case INSTALL_MANUALLY -> {
                return State.INSTALL_MANUALLY;
            }
            default -> {
                // Installs itself: whether there is anything to say yet.
            }
        }
        return staged ? State.READY : State.HIDDEN;
    }

    /**
     * Wires the banner to the updater's own properties, so a release found by
     * the background check surfaces here without the user going looking for it
     * in Settings.
     */
    public void init() {
        updateManager = ServiceLocator.find(UpdateManager.class).orElse(null);
        if (updateManager == null) {
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
        // The one that brings the banner up with its offer to restart. Without
        // it the banner never hears that the download finished: by then the
        // two properties above are already at their final values and fire
        // nothing more.
        updateManager.stagedProperty().addListener((o, was, is) -> refresh());
        // The title carries a version number, so it cannot be a plain binding;
        // re-render instead when the language changes under it.
        I18n.localeProperty().addListener((o, was, is) -> refresh());
        refresh();
    }

    /** Re-renders the banner from the updater's current state. */
    public void refresh() {
        if (updateManager == null) {
            render(State.HIDDEN, "");
            return;
        }
        render(stateFor(updateManager.updateAvailableProperty().get(),
                        updateManager.hasStagedUpdate(),
                        updateManager.hold()),
                updateManager.latestVersionProperty().get());
    }

    /**
     * Renders {@code state} for the release numbered {@code version}.
     *
     * <p>Apart from {@link #refresh()} so a layout test can lay the banner out
     * in a state the platform it runs on never reaches: whether an update is
     * staged at all is {@code UpdateApplier}'s answer, and on Linux it is no,
     * so the restart button never shows there.</p>
     *
     * @param state   what the banner should say about an update, if anything
     * @param version the newer release's number, as the title quotes it
     */
    public void render(State state, String version) {
        if (banner == null) {
            return;
        }
        banner.setVisible(state != State.HIDDEN);
        banner.setManaged(state != State.HIDDEN);
        if (state == State.HIDDEN) {
            return;
        }

        title.setText(state == State.READY
                ? I18n.get("dashboard.update.ready", version)
                : I18n.get("dashboard.update.available", version));
        String hintKey = switch (state) {
            case PACKAGE_MANAGER -> "dashboard.update.hint.packagemanager";
            case MOVE_TO_APPLICATIONS -> "dashboard.update.hint.move";
            case INSTALL_MANUALLY -> "dashboard.update.hint.manual";
            default -> "dashboard.update.hint.staged";
        };
        hint.setText(I18n.get(hintKey));

        // Only the staged state has anything to press, and a package-managed
        // install is not ours to touch.
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
