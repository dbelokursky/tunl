package com.vlessclient.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PlatformPathsTest {

    @Test
    void mac_usesLibraryApplicationSupport() {
        String home = System.getProperty("user.home");
        MacPlatformPaths paths = new MacPlatformPaths();

        assertThat(paths.dataDir())
                .isEqualTo(Path.of(home, "Library", "Application Support", "VlessClient"));
        // coreBinDir/logsDir are derived — they must match the pre-refactor
        // hardcoded locations exactly so nothing moves on macOS.
        assertThat(paths.coreBinDir())
                .isEqualTo(Path.of(home, "Library", "Application Support", "VlessClient", "bin"));
        assertThat(paths.logsDir())
                .isEqualTo(Path.of(home, "Library", "Application Support", "VlessClient", "logs"));
    }

    @Test
    void windows_usesAppDataRoaming() {
        Path appData = Path.of("C:", "Users", "dima", "AppData", "Roaming");
        WindowsPlatformPaths paths = new WindowsPlatformPaths(appData);

        assertThat(paths.dataDir()).isEqualTo(appData.resolve("VlessClient"));
        assertThat(paths.coreBinDir()).isEqualTo(appData.resolve("VlessClient").resolve("bin"));
        assertThat(paths.logsDir()).isEqualTo(appData.resolve("VlessClient").resolve("logs"));
    }

    @Test
    void linux_usesXdgDataHomeWhenSet(@org.junit.jupiter.api.io.TempDir Path home) {
        LinuxPlatformPaths paths = new LinuxPlatformPaths(home, "/custom/xdg-data");

        assertThat(paths.dataDir())
                .isEqualTo(Path.of("/custom/xdg-data", "vless-client"));
        assertThat(paths.logsDir())
                .isEqualTo(Path.of("/custom/xdg-data", "vless-client", "logs"));
    }

    @Test
    void linux_defaultsToLocalShare(@org.junit.jupiter.api.io.TempDir Path home) {
        LinuxPlatformPaths paths = new LinuxPlatformPaths(home, null);

        assertThat(paths.dataDir())
                .isEqualTo(home.resolve(".local").resolve("share").resolve("vless-client"));
    }

    // --- where a session's plain-text configuration and stop signal go ----

    /**
     * A session's configuration carries every server's credential in plain
     * text. In {@code %APPDATA%} it went with the roaming profile, and onto
     * the file server where AppData is redirected; the local part of the
     * profile does neither.
     */
    @Test
    void windows_keepsSessionFilesInTheLocalProfile() {
        Path profile = Path.of("C:", "Users", "John O'Brien", "AppData");
        WindowsPlatformPaths paths = new WindowsPlatformPaths(
                profile.resolve("Roaming"), profile.resolve("Local"));

        assertThat(paths.runDir().getParent())
                .isEqualTo(profile.resolve("Local").resolve("VlessClient").resolve("run"));
        assertThat(paths.runDir().startsWith(paths.dataDir())).as("out of the roaming data dir")
                .isFalse();
    }

    /**
     * Copies given an {@code APPDATA} of their own share the profile's
     * {@code LOCALAPPDATA}, and the single-instance lock, in the data dir,
     * lets them run side by side. Each session start sweeps its run folder,
     * so in one shared folder each copy's connect deleted the configuration
     * the other was to start its core on once UAC was answered.
     */
    @Test
    void windows_givesEachDataDirItsOwnRunFolder() {
        Path profile = Path.of("C:", "Users", "dima", "AppData");
        Path local = profile.resolve("Local");
        WindowsPlatformPaths first = new WindowsPlatformPaths(profile.resolve("Roaming"), local);
        WindowsPlatformPaths second = new WindowsPlatformPaths(
                Path.of("D:", "Portable", "Roaming"), local);

        assertThat(first.dataDir()).isNotEqualTo(second.dataDir());
        assertThat(first.runDir()).isNotEqualTo(second.runDir());
        assertThat(List.of(first.runDir().getParent(), second.runDir().getParent()))
                .containsOnly(local.resolve("VlessClient").resolve("run"));
        assertThat(new WindowsPlatformPaths(profile.resolve("Roaming"), local).runDir())
                .as("the same data dir finds its folder again").isEqualTo(first.runDir());
    }

    /**
     * {@code $XDG_RUNTIME_DIR} is a tmpfs only its user can open, which no
     * home backup copies; the data dir is in the home that Déjà Dup and
     * most other backups take whole.
     */
    @Test
    void linux_keepsSessionFilesInThePrivateRuntimeDir(@TempDir Path tmp) throws IOException {
        assumePosix();
        Path runtime = directory(tmp.resolve("run user 1000"), "rwx------");
        LinuxPlatformPaths paths = new LinuxPlatformPaths(tmp.resolve("home"), null,
                runtime.toString());

        assertThat(paths.runDir().getParent()).isEqualTo(runtime.resolve("vless-client"));
    }

    /**
     * The runtime dir is the user's, but the single-instance lock is the data
     * dir's: copies with profiles of their own ({@code XDG_DATA_HOME}) run
     * side by side. Each session start sweeps its run folder, so in one
     * shared folder each copy's connect deleted the configuration the other
     * was to start its core on once its PolicyKit prompt was answered.
     */
    @Test
    void linux_givesEachDataDirItsOwnRunFolder(@TempDir Path tmp) throws IOException {
        assumePosix();
        Path runtime = directory(tmp.resolve("run user 1000"), "rwx------");
        Path home = tmp.resolve("home");
        List<Path> runDirs = new ArrayList<>();
        for (String xdgDataHome : new String[] {null, tmp.resolve("profile a").toString(),
                tmp.resolve("profile b").toString()}) {
            LinuxPlatformPaths paths = new LinuxPlatformPaths(home, xdgDataHome,
                    runtime.toString());
            assertThat(paths.runDir().getParent()).isEqualTo(runtime.resolve("vless-client"));
            assertThat(new LinuxPlatformPaths(home, xdgDataHome, runtime.toString()).runDir())
                    .as("the same data dir finds its folder again").isEqualTo(paths.runDir());
            runDirs.add(paths.runDir());
        }

        assertThat(runDirs).doesNotHaveDuplicates();
    }

    /**
     * A run folder is named after its data dir the same way by every release:
     * a configuration a crash left is swept only by a session start in that
     * folder, and %LOCALAPPDATA% is never emptied.
     */
    @Test
    void aRunFolderKeepsItsNameFromOneReleaseToTheNext(@TempDir Path tmp) throws IOException {
        assumePosix();
        Path runtime = directory(tmp.resolve("runtime"), "rwx------");

        assertThat(new LinuxPlatformPaths(Path.of("/home/dima"), null, runtime.toString())
                .runDir()).isEqualTo(runtime.resolve("vless-client").resolve("786b4e02a40ceeb2"));
    }

    /**
     * No runtime dir, or one that is not this user's alone (set by hand,
     * inherited through su, or left by a session manager that sets it loosely),
     * keeps the data dir's run folder: never a folder another user can write in.
     */
    @Test
    void linux_keepsSessionFilesInTheDataDirWithoutAPrivateRuntimeDir(@TempDir Path tmp)
            throws IOException {
        assumePosix();
        Path home = tmp.resolve("home");
        Path shared = directory(tmp.resolve("shared"), "rwxr-xr-x");
        Path dataRun = home.resolve(".local").resolve("share").resolve("vless-client")
                .resolve("run");

        for (String runtime : new String[] {null, "", "  ", "relative/runtime",
                tmp.resolve("missing").toString(), shared.toString()}) {
            assertThat(new LinuxPlatformPaths(home, null, runtime).runDir())
                    .as("XDG_RUNTIME_DIR=%s", runtime).isEqualTo(dataRun);
        }
    }

    /**
     * macOS's per-user temporary folder ({@code $TMPDIR}, under
     * /private/var/folders) is the user's alone and left out of Time Machine;
     * the data dir in ~/Library/Application Support is backed up every hour
     * while the tunnel is up.
     */
    @Test
    void mac_keepsSessionFilesInThePerUserTemporaryFolder(@TempDir Path tmp) throws IOException {
        assumePosix();
        Path perUser = directory(tmp.resolve("T"), "rwx------");

        assertThat(new MacPlatformPaths(perUser).runDir().getParent())
                .isEqualTo(perUser.resolve("VlessClient").resolve("run"));
    }

    /** A java.io.tmpdir pointed at a shared /tmp keeps the data dir's run folder. */
    @Test
    void mac_keepsSessionFilesInTheDataDirWhenTheTemporaryFolderIsShared(@TempDir Path tmp)
            throws IOException {
        assumePosix();
        MacPlatformPaths paths = new MacPlatformPaths(directory(tmp.resolve("tmp"), "rwxrwxrwx"));

        assertThat(paths.runDir()).isEqualTo(paths.dataDir().resolve("run"));
    }

    private static void assumePosix() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "owner-only modes are POSIX");
    }

    private static Path directory(Path dir, String mode) throws IOException {
        Files.createDirectory(dir);
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString(mode));
        return dir;
    }

    @Test
    void current_matchesDetectedPlatform() {
        // The suite runs with the data dir redirected, so current() hands back
        // the wrapper; the per-OS choice being asserted here is its delegate.
        PlatformPaths current = PlatformPaths.current();
        PlatformPaths osChoice = current instanceof PlatformPaths.OverriddenPlatformPaths o
                ? o.delegate()
                : current;
        switch (Platform.current()) {
            case WINDOWS -> assertThat(osChoice).isInstanceOf(WindowsPlatformPaths.class);
            case LINUX -> assertThat(osChoice).isInstanceOf(LinuxPlatformPaths.class);
            default -> assertThat(osChoice).isInstanceOf(MacPlatformPaths.class);
        }
    }

    @Test
    void platformDetect_knownOsNames() {
        String before = System.getProperty("os.name");

        assertThat(Platform.detect("Mac OS X")).isEqualTo(Platform.MAC);
        assertThat(Platform.detect("Darwin")).isEqualTo(Platform.MAC);
        assertThat(Platform.detect("Windows 11")).isEqualTo(Platform.WINDOWS);
        assertThat(Platform.detect("Linux")).isEqualTo(Platform.LINUX);
        assertThat(Platform.detect("FreeBSD")).isEqualTo(Platform.OTHER);
        assertThat(System.getProperty("os.name"))
                .as("names are tried without touching the JVM's own").isEqualTo(before);
        assertThat(Platform.current()).isEqualTo(Platform.detect(before));
    }
}
