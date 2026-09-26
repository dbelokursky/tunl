package com.vlessclient.platform;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Tunl registers itself for tunl:// links for the current Windows user. */
class WindowsUrlSchemeTest {

    /** A per-user install under a profile whose name is not in English. */
    private static final String EXE = "C:\\Users\\Дмитрий\\AppData\\Local\\Tunl\\Tunl.exe";

    @Test
    void theRegFileRegistersTheSchemeWithTheLauncherAndTheLink() {
        byte[] file = WindowsUrlScheme.regFile(EXE);

        assertThat(Arrays.copyOf(file, 2)).as("UTF-16 byte order mark")
                .containsExactly((byte) 0xFF, (byte) 0xFE);
        String text = new String(file, 2, file.length - 2, StandardCharsets.UTF_16LE);
        // In a .reg file every backslash and quote inside a value is escaped:
        // the stored command is "C:\Users\...\Tunl.exe" "%1".
        String escapedExe = EXE.replace("\\", "\\\\");
        assertThat(text).startsWith("Windows Registry Editor Version 5.00\r\n")
                .contains("[HKEY_CURRENT_USER\\Software\\Classes\\tunl]\r\n@=\"URL:Tunl\"")
                .contains("\"URL Protocol\"=\"\"")
                .contains("[HKEY_CURRENT_USER\\Software\\Classes\\tunl\\shell\\open\\command]\r\n"
                        + "@=\"\\\"" + escapedExe + "\\\" \\\"%1\\\"\"");
    }

    @Test
    void aRegistrationAlreadyInPlaceIsLeftAlone() {
        List<List<String>> calls = new ArrayList<>();
        CommandRunner runner = command -> {
            calls.add(command);
            return new CommandRunner.Result(0, "    (Default)    REG_SZ    "
                    + WindowsUrlScheme.openCommand(EXE));
        };

        new WindowsUrlScheme(runner).register(EXE);

        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst()).startsWith("reg", "query");
    }

    @Test
    void aMissingOrStaleRegistrationIsImported() {
        AtomicReference<String> imported = new AtomicReference<>();
        CommandRunner runner = command -> {
            if (command.get(1).equals("import")) {
                byte[] bytes = Files.readAllBytes(Path.of(command.get(2)));
                imported.set(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE));
                return new CommandRunner.Result(0, "The operation completed successfully.");
            }
            return new CommandRunner.Result(1, "ERROR: The system was unable to find the key");
        };

        new WindowsUrlScheme(runner).register(EXE);

        assertThat(imported.get()).contains("shell\\open\\command");
    }

    @Test
    void onlyTheInstalledLauncherRegistersItself() {
        assertThat(WindowsUrlScheme.isInstalledLauncher(EXE)).isTrue();
        assertThat(WindowsUrlScheme.isInstalledLauncher("C:\\jdk\\bin\\java.exe")).isFalse();
    }
}
