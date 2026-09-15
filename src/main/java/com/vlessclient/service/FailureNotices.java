package com.vlessclient.service;

import com.vlessclient.model.ConnectionState;
import java.util.function.LongSupplier;

/**
 * Which failures of the tunnel get a system notification: the first of each
 * failure streak.
 *
 * <p>A streak is one connection request of the user's that has not reached
 * CONNECTED since its last notice. Automatic recovery retries within the
 * request, so a core that exited at every start, on a port another program
 * holds say, used to notify at every backoff step. A tunnel that came up and
 * dropped again, or a new connect, reconnect or disconnect, starts a new
 * streak.</p>
 */
final class FailureNotices {

    private final LongSupplier currentRequest;
    private boolean connectedSinceNotice = true;
    private long noticedRequest;

    /**
     * Creates the gate over the user's connection requests.
     *
     * @param currentRequest the user's current request, which automatic
     *     retries leave as it is
     */
    FailureNotices(LongSupplier currentRequest) {
        this.currentRequest = currentRequest;
    }

    /**
     * Records a state the engine reached.
     *
     * @param state the engine's new connection state
     * @return whether this state deserves a notification
     */
    synchronized boolean onState(ConnectionState state) {
        if (state == ConnectionState.CONNECTED) {
            connectedSinceNotice = true;
            return false;
        }
        if (state != ConnectionState.ERROR) {
            return false;
        }
        long request = currentRequest.getAsLong();
        if (!connectedSinceNotice && request == noticedRequest) {
            return false;
        }
        connectedSinceNotice = false;
        noticedRequest = request;
        return true;
    }
}
