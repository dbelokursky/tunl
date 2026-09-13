package com.vlessclient.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The in-process appearance reader held against {@code defaults(1)}, the
 * process it replaces in the theme watcher.
 */
class MacAppearanceTest {

    @Test
    @EnabledOnOs(OS.MAC)
    void agreesWithDefaultsAboutTheCurrentAppearance() throws Exception {
        Result result = defaults("read", "-g", "AppleInterfaceStyle");
        boolean darkPerDefaults = result.exit() == 0
                && result.output().trim().equalsIgnoreCase("Dark");

        assertThat(MacAppearance.isDark()).isEqualTo(darkPerDefaults);
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void aKeyThatIsNotSetReadsAsEmpty() {
        assertThat(MacAppearance.readGlobalString("VlessClientTestUnset" + UUID.randomUUID()))
                .isEmpty();
    }

    /**
     * The watcher polls, so a read must see what another process changed, and
     * must not mistake a non-string value for a string. Writes a throwaway
     * preferences domain, so it runs on CI only, like the Keychain round-trip
     * in {@link SecretSealerPlatformTest}.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    @EnabledIfEnvironmentVariable(named = "CI", matches = "true")
    void seesWhatAnotherProcessChanges() throws Exception {
        String domain = "com.vlessclient.test." + UUID.randomUUID();
        try {
            defaults("write", domain, "Style", "-string", "Light");
            assertThat(MacAppearance.readString(domain, "Style")).contains("Light");

            defaults("write", domain, "Style", "-string", "Dark");
            assertThat(MacAppearance.readString(domain, "Style")).contains("Dark");

            defaults("write", domain, "Count", "-int", "1");
            assertThat(MacAppearance.readString(domain, "Count")).as("an integer").isEmpty();

            defaults("delete", domain, "Style");
            assertThat(MacAppearance.readString(domain, "Style")).isEmpty();
        } finally {
            defaults("delete", domain);
        }
    }

    @Test
    @DisabledOnOs(OS.MAC)
    void isNeverDarkOffMac() {
        assertThat(MacAppearance.isDark()).isFalse();
    }

    private record Result(int exit, String output) {
    }

    private static Result defaults(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("defaults"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("defaults exited").isTrue();
        return new Result(process.exitValue(), output);
    }
}
