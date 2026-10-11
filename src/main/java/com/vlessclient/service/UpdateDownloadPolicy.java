package com.vlessclient.service;

import java.util.Optional;

/**
 * Decides whether the background download of an available update may start
 * right now.
 *
 * <p>Detection is cheap and always runs — a few kilobytes of JSON once a day.
 * The download is not: an installer is roughly a hundred megabytes, and where
 * those megabytes travel is the question this class answers.</p>
 *
 * <p>The state of the tunnel deliberately does not enter into it. Downloading
 * while connected spends bandwidth the user may be paying for by the gigabyte,
 * but the alternative — waiting for a disconnect — fails worst for exactly the
 * user this client exists for: someone in a network where GitHub is throttled
 * or blocked, who therefore keeps the tunnel on permanently, and who under a
 * "only while disconnected" rule would never receive an update at all. An
 * update that arrives is worth more than the megabytes it costs.</p>
 */
public final class UpdateDownloadPolicy {

    private UpdateDownloadPolicy() {
    }

    /**
     * Reports whether {@link UpdateManager} should start downloading now.
     *
     * <p>Called on every scheduled check once a newer release is detected, so
     * a rule that says "not now" simply defers to the next check rather than
     * abandoning the update.</p>
     *
     * @param stagedVersion the version of the verified installer already
     *                      waiting to be applied at the next start, if any
     * @param candidate     the version the check found
     * @return true to start the download now
     */
    public static boolean shouldDownloadNow(Optional<String> stagedVersion, String candidate) {
        // Re-fetching on top of a verified installer that is already waiting
        // is never worth the bytes, unless what waits is older than what was
        // found: a newer release is often the hotfix for the staged one, and
        // deferring to it installed the very build the hotfix replaced.
        return stagedVersion.isEmpty()
                || UpdateManager.isNewerVersion(candidate, stagedVersion.get());
    }
}
