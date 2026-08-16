package com.vlessclient.service;

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
     * @param alreadyStaged whether a verified installer is already waiting to
     *                      be applied at the next start
     * @return true to start the download now
     */
    public static boolean shouldDownloadNow(boolean alreadyStaged) {
        // Re-fetching on top of a verified installer that is already waiting
        // is the one case that is never worth the bytes.
        return !alreadyStaged;
    }
}
