package com.vlessclient.ui.view.settings;

import com.vlessclient.ui.view.settings.UpdatesSection.AppRowState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Updates row in Settings &gt; About, checked as the pure state machine it
 * is — the same way the dashboard banner is checked, and for the same reason:
 * every combination is reachable, and none of them needs a scene.
 *
 * <p>The rule worth pinning is that only a staged update has anything to
 * press. The row used to offer "Download" as soon as a release was found,
 * which duplicated a fetch that had already started on its own — pressing it
 * ran a second download of the same installer into the same staging path.</p>
 */
class UpdatesRowStateTest {

    /** Reads as the row does: available, staged, downloading, selfUpdates. */
    private static AppRowState state(
            boolean available, boolean staged, boolean downloading, boolean selfUpdates) {
        return UpdatesSection.rowState(available, staged, downloading, selfUpdates);
    }

    @Test
    void nothingIsSaidWithoutANewerRelease() {
        assertThat(state(false, false, false, true)).isEqualTo(AppRowState.UP_TO_DATE);
        // Stale flags from an earlier run cannot talk the row into an offer.
        assertThat(state(false, true, true, true)).isEqualTo(AppRowState.UP_TO_DATE);
    }

    @Test
    void aStagedUpdateIsTheOnlyStateWithSomethingToPress() {
        assertThat(state(true, true, false, true)).isEqualTo(AppRowState.STAGED);

        // Everything else an available update can be: no button in any of them.
        assertThat(state(true, false, true, true)).isEqualTo(AppRowState.DOWNLOADING);
        assertThat(state(true, false, false, true)).isEqualTo(AppRowState.AVAILABLE);
        assertThat(state(false, false, false, true)).isEqualTo(AppRowState.UP_TO_DATE);
        assertThat(state(true, false, false, false)).isEqualTo(AppRowState.PACKAGE_MANAGER);
    }

    /**
     * The row reads this to decide whether the button appears at all, so it is
     * the flag a new state would have to opt into deliberately — rather than
     * inheriting an offer by being written next to the wrong branch.
     */
    @Test
    void onlyTheStagedStateShowsAButton() {
        assertThat(AppRowState.values())
                .filteredOn(AppRowState::offersRestart)
                .containsExactly(AppRowState.STAGED);
    }

    /**
     * The gap between "a release was found" and "its bytes are on disk" is a
     * state of its own, not a prompt. It resolves itself: the same check that
     * found the release starts the download.
     */
    @Test
    void aFoundUpdateReportsTheDownloadRatherThanOfferingIt() {
        assertThat(state(true, false, true, true)).isEqualTo(AppRowState.DOWNLOADING);
        assertThat(state(true, false, false, true)).isEqualTo(AppRowState.AVAILABLE);
    }

    /**
     * Linux installs through apt/AUR/Homebrew, so nothing here could apply an
     * installer even if one were fetched — the row must not imply otherwise,
     * whatever the other flags claim.
     */
    @Test
    void aPackageManagedInstallIsNeverOfferedARestart() {
        for (boolean staged : new boolean[] {false, true}) {
            for (boolean downloading : new boolean[] {false, true}) {
                assertThat(state(true, staged, downloading, false))
                        .as("staged=%s downloading=%s", staged, downloading)
                        .isEqualTo(AppRowState.PACKAGE_MANAGER);
            }
        }
    }
}
