package com.vlessclient.platform;

import java.io.IOException;

/**
 * The user dismissed the administrator prompt a TUN start raised: the macOS
 * password dialog, or PolicyKit's on Linux.
 *
 * <p>That is the user cancelling the connect, not the connect failing. It was
 * reported as a failure, with "Tunnel stopped" in a notification, and a
 * dismissed one-time setup prompt was followed straight away by the
 * every-connect prompt it had just been declined in favour of.</p>
 */
public final class ElevationDeclinedException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what was declined, for the log
     */
    public ElevationDeclinedException(String message) {
        super(message);
    }
}
