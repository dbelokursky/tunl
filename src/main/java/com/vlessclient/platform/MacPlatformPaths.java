package com.vlessclient.platform;

import java.nio.file.Path;

/** macOS: everything under {@code ~/Library/Application Support/VlessClient}. */
public final class MacPlatformPaths implements PlatformPaths {

    private static String home() {
        return System.getProperty("user.home");
    }

    @Override
    public Path dataDir() {
        return Path.of(home(), "Library", "Application Support", "VlessClient");
    }

}
