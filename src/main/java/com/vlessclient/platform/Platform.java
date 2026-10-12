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
        return detect(System.getProperty("os.name", ""));
    }

    /**
     * The platform an {@code os.name} names. Apart from {@link #detect()} so
     * a test can try names without setting the JVM-wide property: one that
     * did, and was the first to touch this class, fixed {@link #current()}
     * to the name it was trying for the rest of the run.
     *
     * @param osName a value of the {@code os.name} property
     * @return the platform it names
     */
    static Platform detect(String osName) {
        String os = osName.toLowerCase(Locale.ROOT);
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
