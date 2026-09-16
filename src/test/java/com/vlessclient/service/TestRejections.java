package com.vlessclient.service;

/**
 * The engine's refusal of a configuration, for tests outside this package:
 * {@link ConfigRejectedException}'s constructors are package-private.
 */
public final class TestRejections {

    private TestRejections() {
    }

    /** What the engine throws when sing-box refuses a configuration for {@code reason}. */
    public static ConfigRejectedException refusal(String reason) {
        return new ConfigRejectedException(reason);
    }
}
