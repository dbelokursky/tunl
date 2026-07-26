package com.vlessclient.platform;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(paths.downloadsDir()).isEqualTo(Path.of(home, "Downloads"));
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
        assertThat(paths.downloadsDir()).isEqualTo(home.resolve("Downloads"));
    }

    @Test
    void linux_downloadsHonorUserDirs(@org.junit.jupiter.api.io.TempDir Path home) throws Exception {
        Path config = home.resolve(".config");
        java.nio.file.Files.createDirectories(config);
        java.nio.file.Files.writeString(config.resolve("user-dirs.dirs"), """
                # This file is written by xdg-user-dirs-update
                XDG_DESKTOP_DIR="$HOME/Desktop"
                XDG_DOWNLOAD_DIR="$HOME/Stuff/Downloads"
                """);

        LinuxPlatformPaths paths = new LinuxPlatformPaths(home, null);

        assertThat(paths.downloadsDir())
                .isEqualTo(home.resolve("Stuff").resolve("Downloads"));
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
        assertThat(mapOs("Mac OS X")).isEqualTo(Platform.MAC);
        assertThat(mapOs("Windows 11")).isEqualTo(Platform.WINDOWS);
        assertThat(mapOs("Linux")).isEqualTo(Platform.LINUX);
        assertThat(mapOs("FreeBSD")).isEqualTo(Platform.OTHER);
    }

    private static Platform mapOs(String osName) {
        String prev = System.getProperty("os.name");
        System.setProperty("os.name", osName);
        try {
            return Platform.detect();
        } finally {
            System.setProperty("os.name", prev);
        }
    }
}
