package com.vlessclient.testing;

import com.vlessclient.platform.CorePlatform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The sing-box core the build bundles into {@code target/classes/native} at
 * generate-resources, for the smoke suites that run it.
 *
 * <p>A suite without its core skips itself, as in an IDE run that never
 * bundled one, except under {@code -Psmoke}. CI and the release jobs run the
 * smoke suites to check the core they build or ship, and a skipped suite
 * passes the step, so there a missing core fails the suite.</p>
 */
public final class BundledCore {

    /** Set to {@code true} by the {@code smoke} Maven profile. */
    public static final String REQUIRED_PROPERTY = "tunl.smoke.requireCore";

    private BundledCore() {
    }

    /**
     * Returns the bundled core for this host's OS and architecture. Without
     * it the calling suite fails when {@value #REQUIRED_PROPERTY} is set and
     * is skipped otherwise.
     */
    public static Path locate() {
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String arch = osArch.contains("aarch64") || osArch.contains("arm64")
                ? "arm64" : "amd64";
        CorePlatform core = CorePlatform.current();
        Path binary = Path.of("target", "classes", "native",
                        core.osKey() + "-" + arch, core.binaryName())
                .toAbsolutePath();
        String missing = "bundled sing-box not found at " + binary
                + " — run the generate-resources phase first";
        if (Boolean.getBoolean(REQUIRED_PROPERTY)) {
            assertThat(Files.isExecutable(binary)).as(missing).isTrue();
        } else {
            assumeTrue(Files.isExecutable(binary), missing);
        }
        return binary;
    }
}
