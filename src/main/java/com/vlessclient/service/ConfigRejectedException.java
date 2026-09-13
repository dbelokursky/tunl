package com.vlessclient.service;

import com.vlessclient.app.I18n;
import java.io.IOException;

/**
 * The core refused the configuration in {@code sing-box check}, so nothing was
 * launched. The message is ready to show; {@link #reason()} keeps the core's
 * own words for a caller that can tell which server they are about.
 */
public final class ConfigRejectedException extends IOException {

    private static final long serialVersionUID = 1L;

    /** The core's reason, without the app's framing. */
    private final String reason;

    ConfigRejectedException(String reason) {
        this(I18n.get("engine.config.rejected", reason), reason);
    }

    ConfigRejectedException(String message, String reason) {
        super(message);
        this.reason = reason;
    }

    /**
     * What the core said.
     *
     * @return the reason as the core put it, for example
     *     {@code initialize outbound[2]: unsupported flow: xtls-rprx-direct}
     */
    public String reason() {
        return reason;
    }
}
