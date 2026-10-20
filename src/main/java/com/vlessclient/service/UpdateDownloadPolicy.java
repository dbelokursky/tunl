package com.vlessclient.service;

import java.time.Duration;
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

    /** The wait after the first failure. */
    private static final Duration FIRST_RETRY = Duration.ofMinutes(1);

    /** The longest wait, however many failures came before it. */
    private static final Duration LONGEST_RETRY = Duration.ofMinutes(30);

    /** Doublings of the first wait that reach the longest: 2^5 minutes is past it. */
    private static final int MAX_DOUBLINGS = 5;

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

    /**
     * How long to wait before trying a download that failed on the way again.
     *
     * <p>Only the network's and the disk's failures come here: a dropped
     * connection, a stalled transfer, a 5xx, a signature that could not be
     * fetched, a full disk. A download a check refused is not tried again.</p>
     *
     * <p>The pull is two ways. The commonest failure is the tunnel switching
     * over at startup, which is over in seconds, so the first try again should
     * come soon. But each try can cost most of an installer before it fails,
     * on links the user may pay for by the megabyte, and a network that blocks
     * GitHub fails every try: the waits have to grow, and stop growing well
     * before {@link UpdateManager#CHECK_INTERVAL_HOURS}, past which a retry
     * would only follow the next check.</p>
     *
     * <p>So: a minute after the first failure, which a tunnel coming up has
     * long finished, then twice as long after each one more, 2, 4, 8 and 16
     * minutes, then every half hour for as long as the failures last. A
     * network that recovers is used within half an hour of it; one that never
     * does costs two tries an hour, and the usual such network, one that
     * blocks GitHub, refuses them before the first byte.</p>
     *
     * @param failedAttempts downloads of this release that failed in a row,
     *                       1 after the first
     * @return the wait before the next try; positive
     */
    public static Duration retryDelay(int failedAttempts) {
        // The doubling stops before the cap, so the shift never sees a count
        // large enough to overflow it.
        int doublings = Math.min(Math.max(failedAttempts, 1) - 1, MAX_DOUBLINGS);
        Duration doubled = FIRST_RETRY.multipliedBy(1L << doublings);
        return doubled.compareTo(LONGEST_RETRY) < 0 ? doubled : LONGEST_RETRY;
    }
}
