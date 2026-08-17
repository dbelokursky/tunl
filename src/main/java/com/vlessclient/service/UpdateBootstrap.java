package com.vlessclient.service;

import com.vlessclient.app.AppVersion;
import com.vlessclient.platform.PendingUpdate;
import com.vlessclient.platform.UpdateApplier;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Installs a staged update, if there is one, before the application starts.
 *
 * <p>Runs at the very top of startup — ahead of the UI, the tray icon and the
 * tunnel — because the answer it produces is "quit immediately", and anything
 * initialized first would have to be torn down again.</p>
 *
 * <p>Nothing here clears the marker after a successful handoff. It clears
 * itself on the next run instead: the new build finds a marker naming a
 * version that is no longer newer than its own, and drops it. That way the
 * marker survives exactly as long as the update it describes is still
 * outstanding, whatever happens in between.</p>
 */
public final class UpdateBootstrap {

    private static final Logger log = LoggerFactory.getLogger(UpdateBootstrap.class);

    /**
     * How many times a staged update may be attempted before it is written off
     * as unapplicable. Two: one ordinary try, and one for the case where the
     * first was interrupted by something outside the update (a machine going
     * to sleep, a forced quit).
     */
    static final int MAX_ATTEMPTS = 2;

    /**
     * How long a staged update may sit unapplied before it is thrown away.
     *
     * <p>Not primarily about the hundred megabytes. An applier that answers
     * {@code UNSUPPORTED} — the app running translocated from a quarantined
     * copy, or from somewhere that is not a bundle at all — records no attempt,
     * because that is a condition the user can still fix by moving the app to
     * Applications, and burning the installer for it would be wrong. But the
     * download policy declines to fetch anything while an installer is already
     * staged, so a marker that never applies and never expires does not merely
     * take up room: it stops every later release from being downloaded at all.
     * A week is long enough to move an app and short enough that being stuck is
     * measured in days.</p>
     */
    static final long STALE_AFTER_MS = 7L * 24 * 60 * 60 * 1000;

    private UpdateBootstrap() {
    }

    /**
     * Applies a staged update if one is waiting.
     *
     * @return true when the caller must exit now, leaving the swap to the
     *         applier it just handed off to
     */
    public static boolean applyPendingUpdate() {
        return applyPendingUpdate(new UpdateStaging(), UpdateApplier.current(), AppVersion.VERSION);
    }

    static boolean applyPendingUpdate(
            UpdateStaging staging, UpdateApplier applier, String currentVersion) {
        return applyPendingUpdate(staging, applier, currentVersion, System.currentTimeMillis());
    }

    static boolean applyPendingUpdate(UpdateStaging staging, UpdateApplier applier,
                                      String currentVersion, long nowMs) {
        Optional<PendingUpdate> staged = staging.pending();
        if (staged.isEmpty()) {
            return false;
        }
        PendingUpdate update = staged.get();

        if (!UpdateManager.isNewerVersion(update.version(), currentVersion)) {
            // Either the swap worked and this is the new build looking at its
            // own leftovers, or the user installed it by hand. Same cleanup.
            log.info("Staged update {} is not newer than {}, clearing it",
                    update.version(), currentVersion);
            staging.clear();
            return false;
        }

        if (staging.attempts() >= MAX_ATTEMPTS) {
            log.error("Staged update {} failed to apply {} times, giving up on it",
                    update.version(), MAX_ATTEMPTS);
            staging.clear();
            return false;
        }

        // 0 means the marker predates this field; leaving it alone is the safe
        // reading, and it corrects itself the moment anything is staged again.
        long stagedAt = staging.stagedAt();
        if (stagedAt > 0 && nowMs - stagedAt > STALE_AFTER_MS) {
            log.error("Staged update {} has been waiting {} days without applying, "
                            + "clearing it so newer releases can be downloaded again",
                    update.version(), (nowMs - stagedAt) / (24 * 60 * 60 * 1000));
            staging.clear();
            return false;
        }

        if (!staging.verify(update)) {
            staging.clear();
            return false;
        }

        UpdateApplier.Outcome outcome = applier.apply(update);
        if (outcome == UpdateApplier.Outcome.HANDED_OFF) {
            // Counted here rather than before the call, so only handoffs whose
            // result we will never learn burn an attempt. The applier has
            // returned but its helper is still waiting for this process to
            // exit, so there is time to record it.
            staging.recordAttempt();
            log.info("Exiting so update {} can be installed", update.version());
            return true;
        }
        log.info("Update {} not applied ({}), continuing startup",
                update.version(), outcome);
        return false;
    }
}
