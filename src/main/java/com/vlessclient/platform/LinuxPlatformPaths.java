package com.vlessclient.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Linux: XDG base directories. App data (and logs/core underneath it) lives
 * in {@code $XDG_DATA_HOME/vless-client}, defaulting to
 * {@code ~/.local/share/vless-client}. Downloads resolve through the
 * {@code user-dirs.dirs} mechanism with {@code ~/Downloads} as the fallback.
 */
public final class LinuxPlatformPaths implements PlatformPaths {

    private final Path home;
    private final String xdgDataHome;

    public LinuxPlatformPaths() {
        this(Path.of(System.getProperty("user.home")), System.getenv("XDG_DATA_HOME"));
    }

    /** Test seam: inject the home directory and the {@code $XDG_DATA_HOME} value. */
    LinuxPlatformPaths(Path home, String xdgDataHome) {
        this.home = home;
        this.xdgDataHome = xdgDataHome;
    }

    @Override
    public Path dataDir() {
        Path base = xdgDataHome != null && !xdgDataHome.isBlank()
                ? Path.of(xdgDataHome)
                : home.resolve(".local").resolve("share");
        return base.resolve("vless-client");
    }


    /**
     * Reads {@code XDG_DOWNLOAD_DIR} from {@code ~/.config/user-dirs.dirs}
     * (the file xdg-user-dirs maintains; there is no environment variable for
     * it in practice). Lines look like
     * {@code XDG_DOWNLOAD_DIR="$HOME/Downloads"}.
     */
    private Path parseUserDirsDownload() {
        Path userDirs = home.resolve(".config").resolve("user-dirs.dirs");
        if (!Files.isRegularFile(userDirs)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(userDirs)) {
                String trimmed = line.strip();
                if (!trimmed.startsWith("XDG_DOWNLOAD_DIR=")) {
                    continue;
                }
                String value = trimmed.substring("XDG_DOWNLOAD_DIR=".length())
                        .replace("\"", "")
                        .replace("$HOME", home.toString())
                        .strip();
                if (!value.isEmpty()) {
                    return Path.of(value);
                }
            }
        } catch (IOException ignored) {
            // unreadable file — fall back to ~/Downloads
        }
        return null;
    }
}
