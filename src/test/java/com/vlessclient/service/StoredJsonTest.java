package com.vlessclient.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** The four ways reading a data file ends, which the stores treat apart. */
class StoredJsonTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @TempDir
    Path dir;

    @Test
    @DisplayName("no file is a first start")
    void missing() {
        assertThat(StoredJson.read(mapper, dir.resolve("servers.json")))
                .isInstanceOf(StoredJson.Missing.class);
    }

    @Test
    @DisplayName("JSON is parsed")
    void parsed() throws IOException {
        Path file = dir.resolve("servers.json");
        Files.writeString(file, "{\"servers\":[]}", StandardCharsets.UTF_8);

        assertThat(StoredJson.read(mapper, file))
                .isInstanceOfSatisfying(StoredJson.Parsed.class,
                        parsed -> assertThat(parsed.root().has("servers")).isTrue());
    }

    @Test
    @DisplayName("a file that was read and is not JSON is damaged")
    void damaged() throws IOException {
        Path file = dir.resolve("servers.json");
        Files.writeString(file, "{\"servers\":[", StandardCharsets.UTF_8);

        assertThat(StoredJson.read(mapper, file)).isInstanceOf(StoredJson.Damaged.class);
    }

    @Test
    @DisplayName("a path that cannot be opened is not damage: it says why it failed")
    void unopenable() throws IOException {
        Path file = Files.createDirectory(dir.resolve("servers.json"));

        assertThat(StoredJson.read(mapper, file))
                .isInstanceOfSatisfying(StoredJson.Unopenable.class,
                        read -> assertThat(StoredJson.reason(read.cause())).isNotBlank());
    }

    @Test
    @DisplayName("the reason is the exception's type and message, or the type alone")
    void reason() {
        assertThat(StoredJson.reason(new java.nio.file.AccessDeniedException("/data/servers.json")))
                .isEqualTo("AccessDeniedException: /data/servers.json");
        assertThat(StoredJson.reason(new IOException())).isEqualTo("IOException");
        assertThat(StoredJson.reason(null)).isEqualTo("unknown error");
    }
}
