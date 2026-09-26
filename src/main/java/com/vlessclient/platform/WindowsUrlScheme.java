package com.vlessclient.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers Tunl as the handler of {@code tunl://} links for the current
 * Windows user, under {@code HKCU\Software\Classes\tunl}.
 *
 * <p>Done by the app at every start rather than by the MSI: jpackage's MSI
 * cannot declare a URL scheme, and the app knows the path of the launcher it
 * was started from, which a per-user install puts under the user's own
 * profile. The keys are written only when they differ from what is there,
 * through {@code reg import}: a {@code .reg} file carries quotes and a path
 * in any language, which {@code reg add} on a command line would have to
 * escape.</p>
 */
public final class WindowsUrlScheme {

    private static final Logger log = LoggerFactory.getLogger(WindowsUrlScheme.class);

    static final String KEY = "HKCU\\Software\\Classes\\tunl";

    private final CommandRunner runner;

    WindowsUrlScheme(CommandRunner runner) {
        this.runner = runner;
    }

    /**
     * Registers the launcher this process runs as, when it is the installed
     * {@code Tunl.exe}: a run through {@code java.exe} (development) leaves
     * the registration alone. Blocks on {@code reg}; call it off the JavaFX
     * thread.
     */
    public static void registerCurrent() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return;
        }
        Optional<String> command = ProcessHandle.current().info().command();
        if (command.isEmpty() || !isInstalledLauncher(command.get())) {
            return;
        }
        new WindowsUrlScheme(CommandRunner.system(Duration.ofSeconds(10)))
                .register(command.get());
    }

    /** Whether {@code command} is Tunl's own launcher rather than a JVM. */
    static boolean isInstalledLauncher(String command) {
        String name = command.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return name.equals("tunl.exe");
    }

    /**
     * Points {@code tunl://} at {@code exe}, unless it already is.
     *
     * @param exe the launcher's full path
     */
    void register(String exe) {
        String command = openCommand(exe);
        try {
            CommandRunner.Result current = runner.run(
                    List.of("reg", "query", KEY + "\\shell\\open\\command", "/ve"));
            if (current.exitCode() == 0 && current.output().contains(command)) {
                return;
            }
            Path file = Files.createTempFile("tunl-url-scheme", ".reg");
            try {
                Files.write(file, regFile(exe));
                CommandRunner.Result imported = runner.run(
                        List.of("reg", "import", file.toString()));
                if (imported.exitCode() != 0) {
                    log.warn("Could not register tunl:// links: {}", imported.output().strip());
                    return;
                }
                log.info("Registered tunl:// links to {}", exe);
            } finally {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            log.warn("Could not register tunl:// links: {}", e.getMessage());
        }
    }

    /** The command Windows runs for a link: the launcher, with the link. */
    static String openCommand(String exe) {
        return "\"" + exe + "\" \"%1\"";
    }

    /**
     * The {@code .reg} file, as {@code reg import} reads one: UTF-16 with a
     * byte order mark, backslashes and quotes escaped in every value.
     */
    static byte[] regFile(String exe) {
        String hive = "[HKEY_CURRENT_USER\\Software\\Classes\\tunl";
        String text = "Windows Registry Editor Version 5.00\r\n\r\n"
                + hive + "]\r\n"
                + "@=\"URL:Tunl\"\r\n"
                + "\"URL Protocol\"=\"\"\r\n\r\n"
                + hive + "\\DefaultIcon]\r\n"
                + "@=\"" + escaped("\"" + exe + "\",0") + "\"\r\n\r\n"
                + hive + "\\shell\\open\\command]\r\n"
                + "@=\"" + escaped(openCommand(exe)) + "\"\r\n";
        byte[] body = text.getBytes(StandardCharsets.UTF_16LE);
        byte[] withMark = new byte[body.length + 2];
        withMark[0] = (byte) 0xFF;
        withMark[1] = (byte) 0xFE;
        System.arraycopy(body, 0, withMark, 2, body.length);
        return withMark;
    }

    private static String escaped(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
