package com.vlessclient.platform;

import java.util.Locale;

/**
 * The host operating system, detected once from {@code os.name}. The single
 * seam through which the rest of the app selects platform-specific behavior;
 * everything else consumes the interfaces under this package rather than
 * checking {@code os.name} directly.
 */
public enum Platform {
    MAC,
    WINDOWS,
    LINUX,
    OTHER;

    private static final Platform CURRENT = detect();

    public static Platform current() {
        return CURRENT;
    }

    public boolean isMac() {
        return this == MAC;
    }

    public boolean isWindows() {
        return this == WINDOWS;
    }

    static Platform detect() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return MAC;
        }
        if (os.contains("win")) {
            return WINDOWS;
        }
        if (os.contains("linux")) {
            return LINUX;
        }
        return OTHER;
    }
}
