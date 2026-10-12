package com.vlessclient.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The app never tells macOS it may be killed without notice.
 *
 * <p>{@code Desktop.enableSuddenTermination()} declares that the app's state
 * is saved and it needs no notice to end, so at logout, restart or shutdown
 * macOS may end it with SIGKILL instead of sending the quit event: no quit
 * handler, no {@code stop()}, no shutdown hook. The app enabled it "for
 * consistency", while it always holds traffic history flushed once a minute,
 * a server-list write that may be on its way, and a running core whose
 * system-proxy registration its stop puts back. The quit event is the
 * orderly path, so the app asks for it explicitly.</p>
 *
 * <p>Read from the source: the call is made only where AWT supports it, on
 * macOS, and these tests run headless.</p>
 */
class NoSuddenTerminationTest {

    @Test
    void theAppAsksForTheQuitEventInsteadOfASuddenKill() throws IOException {
        String app = Files.readString(Path.of("src", "main", "java", "com", "vlessclient", "app",
                "VlessClientApp.java"), StandardCharsets.UTF_8);

        assertThat(app).doesNotContain(".enableSuddenTermination(")
                .contains(".disableSuddenTermination(");
    }
}
