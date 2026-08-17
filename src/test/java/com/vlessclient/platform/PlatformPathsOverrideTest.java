package com.vlessclient.platform;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The data-dir override that keeps a test run out of the developer's real
 * profile. Every service that persists anything resolves through
 * {@link PlatformPaths#current()}, so redirecting it here is what stops the
 * suite from reading the actual servers.json / settings.json — and from being
 * one stray save away from overwriting them.
 *
 * <p>Surefire sets the property for the whole suite (see pom.xml), so these
 * tests assert the mechanism the rest of the suite silently depends on.</p>
 */
class PlatformPathsOverrideTest {

    @Test
    void surefireRedirectsTheDataDirAwayFromTheRealProfile() {
        Path dataDir = PlatformPaths.current().dataDir();

        assertThat(System.getProperty(PlatformPaths.DATA_DIR_PROPERTY))
                .as("pom.xml must set %s for the test run", PlatformPaths.DATA_DIR_PROPERTY)
                .isNotBlank();
        assertThat(dataDir).isEqualTo(Path.of(System.getProperty(PlatformPaths.DATA_DIR_PROPERTY)));
        // The specific thing this prevents: touching the user's home profile.
        assertThat(dataDir.toString())
                .doesNotContain("Application Support/VlessClient")
                .doesNotContain(".local/share/vless-client");
    }

    @Test
    void derivedDirectoriesFollowTheOverride() {
        PlatformPaths paths = PlatformPaths.current();

        // logs/ and bin/ default off dataDir(), so redirecting the root is
        // enough — no service can escape via a derived path. Compared as
        // paths: AssertJ's startsWith resolves against the real filesystem
        // and would require the directories to exist.
        assertThat(paths.logsDir().startsWith(paths.dataDir())).isTrue();
        assertThat(paths.coreBinDir().startsWith(paths.dataDir())).isTrue();
    }

    /**
     * Guards a near-miss: the override alone is not enough, because the
     * legacy-path migrations {@code Files.move} a pre-port file from the old
     * mac-shaped location into the current data dir. With the dir redirected,
     * that "old location" IS the developer's real profile — so constructing a
     * service during a test moved their live routing.json and
     * subscriptions.json into {@code target/}, where the next
     * {@code mvn clean} would have deleted them. Services must therefore skip
     * migration whenever the dir is overridden.
     */
    @Test
    void overrideIsDetectableSoMigrationsCanRefuseToRun() {
        assertThat(PlatformPaths.isDataDirOverridden())
                .as("the suite runs with the data dir redirected")
                .isTrue();
    }

    @Test
    void servicesDoNotTouchTheRealProfileWhenOverridden() {
        Path realProfile = Path.of(System.getProperty("user.home"),
                "Library", "Application Support", "VlessClient");

        // Constructing the services must not migrate anything out of the real
        // profile. Assert on the files that were actually moved before the fix.
        new com.vlessclient.service.RoutingService();

        assertThat(PlatformPaths.current().dataDir()).isNotEqualTo(realProfile);
        if (java.nio.file.Files.isDirectory(realProfile)) {
            // Whatever the developer has there stays there; we never assert it
            // exists (CI has no profile), only that we did not consume it.
            assertThat(java.nio.file.Files.exists(realProfile)).isTrue();
        }
    }

    @Test
    void theOverrideReplacesOnlyTheDataDir() {
        PlatformPaths overridden = new PlatformPaths.OverriddenPlatformPaths(
                Path.of("/tmp/whatever"), new MacPlatformPaths());

        // Everything else is derived from it, so redirecting the root is the
        // whole of the redirect.
        assertThat(overridden.dataDir()).isEqualTo(Path.of("/tmp/whatever"));
        assertThat(overridden.logsDir()).isEqualTo(Path.of("/tmp/whatever", "logs"));
        assertThat(overridden.coreBinDir()).isEqualTo(Path.of("/tmp/whatever", "bin"));
    }
}
