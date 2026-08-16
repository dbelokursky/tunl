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
