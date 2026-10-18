package com.vlessclient.service;

import com.vlessclient.model.ProxyMode;
import com.vlessclient.platform.TunLauncher;
import com.vlessclient.testing.FxToolkitExtension;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The run folder a session's stop signal goes to can be one the system
 * empties on its own: macOS removes what its per-user temporary folder has
 * not used for three days, and a runtime dir may be cleaned up as well. A TUN
 * core started through a wrapper that runs as root (the macOS password
 * prompt, the Linux PolicyKit fallback, UAC) stops only once that file
 * appears.
 */
// The wrapper is a /bin/sh script standing in for the root one.
@EnabledOnOs({OS.MAC, OS.LINUX})
@ExtendWith(FxToolkitExtension.class)
class SingBoxEngineRunFolderTest {

    @Test
    void aDisconnectReachesARootWrapperAfterTheSystemEmptiedTheRunFolder(
            @TempDir(cleanup = CleanupMode.NEVER) Path tmp) throws Exception {
        Path runFolder = Files.createDirectories(tmp.resolve("T/VlessClient/run"));
        Path stopFile = runFolder.resolve("stop-1.signal");
        // As the root wrappers do: no stdin from the app, so only the file
        // (or the app's death) ends the core.
        Path wrapper = script(tmp.resolve("wrapper.sh"), "#!/bin/sh\n"
                + "echo 'sing-box started'\n"
                + "while [ ! -f '" + stopFile + "' ]; do sleep 0.2; done\n");
        Path core = script(tmp.resolve("sing-box"),
                "#!/bin/sh\n[ \"$1\" = check ] && exit 0\nsleep 30\n");
        SingBoxEngine engine = new SingBoxEngine(core);
        AtomicReference<Process> launched = new AtomicReference<>();
        engine.setTunLauncher((binary, config, prompt) -> {
            Process process = new ProcessBuilder(wrapper.toString())
                    .redirectErrorStream(true).start();
            launched.set(process);
            return new TunLauncher.Launched(process, stopFile);
        });
        engine.start("{\"log\":{\"level\":\"info\"}}", ProxyMode.TUN);
        try {
            // What the cleanup does to a folder left idle for days.
            deleteTree(runFolder.getParent());

            engine.stop();

            assertThat(launched.get().waitFor(5, TimeUnit.SECONDS))
                    .as("the wrapper saw the stop signal and stopped its core").isTrue();
            assertThat(launched.get().exitValue()).isZero();
            assertThat(engine.isRunning()).isFalse();
        } finally {
            launched.get().destroyForcibly();
            engine.stop();
        }
    }

    private static Path script(Path file, String body) throws Exception {
        Files.writeString(file, body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return file;
    }

    private static void deleteTree(Path dir) throws Exception {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
