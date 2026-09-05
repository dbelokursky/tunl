package com.vlessclient.platform;

import java.util.Locale;

/**
 * The CPU architecture token release assets carry: {@code arm64} or
 * {@code amd64}.
 *
 * <p>Detected in one place. It used to be derived twice, with different
 * policies for an architecture neither knew: the core installer refused it,
 * while the app updater silently downloaded the amd64 build.</p>
 */
public final class CpuArch {

    private CpuArch() {
    }

    /**
     * The token for the running JVM's architecture.
     *
     * @return {@code arm64} or {@code amd64}
     * @throws IllegalStateException for an architecture no release is built for
     */
    public static String releaseToken() {
        return releaseToken(System.getProperty("os.arch", ""));
    }

    /**
     * The token for an {@code os.arch} value.
     *
     * @param osArch the JVM's {@code os.arch} property
     * @return {@code arm64} or {@code amd64}
     * @throws IllegalStateException for an architecture no release is built for
     */
    public static String releaseToken(String osArch) {
        String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "arm64";
        }
        if (arch.contains("x86_64") || arch.contains("amd64")) {
            return "amd64";
        }
        throw new IllegalStateException("Unsupported CPU architecture: " + osArch);
    }
}
