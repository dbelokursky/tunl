package com.vlessclient.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Where an update relay writes its account of what it did. */
final class UpdateHelperLog {

    static final String NAME = "apply-update.log";

    private UpdateHelperLog() {
    }

    /**
     * The file a relay appends its output to. In the logs folder rather than
     * beside the installer: the cleanup that follows a failed update empties
     * the staging folder, and the only record of why it failed went with it.
     *
     * @return the log file, its folder created if needed
     * @throws IOException if the logs folder cannot be created
     */
    static Path file() throws IOException {
        Path dir = PlatformPaths.current().logsDir();
        Files.createDirectories(dir);
        return dir.resolve(NAME);
    }
}
