package com.vlessclient.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens to a config file the app cannot read.
 *
 * <p>Falling back to defaults is right — the app has to start. What was wrong
 * is what came next: the fallback left the damaged file in place, and the first
 * save overwrote it. Writes here are atomic ({@code SecureFiles} writes a temp
 * file and {@code ATOMIC_MOVE}s it), so a file that will not parse was damaged
 * from outside — a full disk, a half-restored backup, a sync client — and is
 * very often still repairable by hand. Was, until the app wrote over it.</p>
 */
class CorruptConfigQuarantineTest {

    @TempDir
    Path dir;

    private List<Path> quarantined(String prefix) throws IOException {
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith(prefix + ".corrupt-"))
                    .toList();
        }
    }

    @Test
    @DisplayName("an unreadable servers.json is moved aside, not left to be overwritten")
    void corruptServersFileIsQuarantined() throws IOException {
        Path file = dir.resolve("servers.json");
        String damaged = "{\"version\":1,\"servers\":[{\"name\":\"Tokyo\",";
        Files.writeString(file, damaged, StandardCharsets.UTF_8);

        ConfigStore store = new ConfigStore(dir);

        assertThat(store.getServers()).as("the app still starts").isEmpty();
        List<Path> aside = quarantined("servers.json");
        assertThat(aside).hasSize(1);
        assertThat(Files.readString(aside.get(0)))
                .as("the bytes are preserved exactly, so they can still be repaired")
                .isEqualTo(damaged);
        assertThat(file).as("and the unreadable file is gone from the live path")
                .doesNotExist();
    }

    @Test
    @DisplayName("an unreadable routing.json is moved aside too")
    void corruptRoutingFileIsQuarantined() throws IOException {
        Path file = dir.resolve("routing.json");
        String damaged = "{\"sections\":[ truncated";
        Files.writeString(file, damaged, StandardCharsets.UTF_8);

        new RoutingService(dir);

        List<Path> aside = quarantined("routing.json");
        assertThat(aside).hasSize(1);
        assertThat(Files.readString(aside.get(0))).isEqualTo(damaged);
    }

    @Test
    @DisplayName("a readable file is never moved")
    void aValidFileIsLeftAlone() throws IOException {
        Path file = dir.resolve("servers.json");
        Files.writeString(file, "{\"version\":1,\"servers\":[]}", StandardCharsets.UTF_8);

        new ConfigStore(dir);

        assertThat(quarantined("servers.json"))
                .as("quarantining a healthy file would look exactly like data loss")
                .isEmpty();
        assertThat(file).exists();
    }
}
