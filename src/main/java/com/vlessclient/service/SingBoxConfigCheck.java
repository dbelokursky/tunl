package com.vlessclient.service;

import com.vlessclient.app.I18n;
import com.vlessclient.platform.SecureFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks the core to validate a written configuration ({@code sing-box check})
 * before anything is launched.
 *
 * <p>The core refuses to build a configuration that uses a field a core update
 * removed, a flow or transport it does not implement, or a malformed REALITY
 * key. Without this check the refusal only surfaced once the core ran: in TUN
 * mode after the administrator or UAC prompt, and again after every automatic
 * recovery retry. A check takes about 35 ms, needs no privileges and does not
 * create the TUN device.</p>
 *
 * <p>Only a refusal stops the start. When the check cannot run at all (a
 * timeout, a binary that will not execute), the start goes ahead: the launch
 * reports a broken binary on its own, and a slow first antivirus scan of the
 * binary must not keep anyone from connecting.</p>
 */
final class SingBoxConfigCheck {

    private static final Logger log = LoggerFactory.getLogger(SingBoxConfigCheck.class);

    /** How long a check may take before the start goes ahead without it. */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    /** Room for any one core error; longer output is cut rather than shown whole. */
    private static final int MAX_REASON_LENGTH = 300;

    /** Terminal colour codes, in case a core ignores {@code --disable-color}. */
    private static final Pattern ANSI_COLOR = Pattern.compile("\\x1B\\[[0-9;]*m");
    private static final Pattern LOG_PREFIX = Pattern.compile("^[A-Z]+\\[\\d+\\]\\s*");

    private final Duration timeout;

    SingBoxConfigCheck(Duration timeout) {
        this.timeout = timeout;
    }

    /**
     * Runs {@code <binary> check -c <config>}.
     *
     * @return the core's reason when it refuses the configuration; empty when it
     *     accepts it or the check could not run
     */
    Optional<String> rejection(Path binary, Path config) {
        Path output = null;
        Process process = null;
        try {
            output = Files.createTempFile("singbox-check-", ".log");
            ProcessBuilder pb = new ProcessBuilder(
                    binary.toAbsolutePath().toString(), "check",
                    "-c", config.toAbsolutePath().toString(), "--disable-color");
            pb.directory(SecureFiles.parentDirectory(binary).toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(output.toFile());
            process = pb.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                log.warn("sing-box check did not finish within {}; starting without it", timeout);
                return Optional.empty();
            }
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                return Optional.empty();
            }
            // Lenient decoding: a stray invalid byte must not turn a refusal
            // into an I/O error, which would let the start go ahead.
            String reason = describe(
                    new String(Files.readAllBytes(output), StandardCharsets.UTF_8),
                    exitCode, config);
            log.warn("sing-box refused the configuration: {}", reason);
            return Optional.of(reason);
        } catch (IOException e) {
            log.warn("Could not run sing-box check; starting without it: {}", e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            deleteQuietly(output);
        }
    }

    /**
     * The line someone can act on: the core stops at its first FATAL line, after
     * any deprecation ERROR lines, so the last FATAL line is the reason. Colour
     * codes, the log-level prefix and the path of our temporary file are
     * removed; the path is noise, and it names a file that is already gone.
     *
     * @param output   everything the check printed
     * @param exitCode its exit code, reported when it printed nothing
     * @param config   the checked file, whose path the core may quote
     * @return a single-line reason
     */
    static String describe(String output, int exitCode, Path config) {
        List<String> lines = ANSI_COLOR.matcher(output).replaceAll("").lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
        if (lines.isEmpty()) {
            return I18n.get("engine.config.rejected.exit", String.valueOf(exitCode));
        }
        String line = lines.reversed().stream()
                .filter(candidate -> candidate.startsWith("FATAL"))
                .findFirst()
                .orElse(lines.getLast());
        String path = config.toAbsolutePath().toString();
        Path fileName = config.getFileName();
        String reason = LOG_PREFIX.matcher(line).replaceFirst("")
                .replace("decode config at " + path + ": ", "")
                .replace(path, fileName != null ? fileName.toString() : path);
        return reason.length() <= MAX_REASON_LENGTH
                ? reason
                : reason.substring(0, MAX_REASON_LENGTH - 1) + "…";
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // Windows keeps the file while a killed check's child still holds it.
            log.debug("Could not delete {}: {}", file, e.getMessage());
        }
    }
}
