package com.vlessclient.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Guards the invariants of the i18n bundles: en/ru key parity, resolution of every key
 * referenced from production code, MessageFormat validity of placeholder patterns, the same
 * arguments in both languages, apostrophes MessageFormat leaves alone, and UTF-8 throughout.
 */
class I18nBundleConsistencyTest {

    private static final String EN_BUNDLE = "/i18n/messages_en.properties";
    private static final String RU_BUNDLE = "/i18n/messages_ru.properties";

    /** Matches string-literal keys passed to I18n.get("...") or I18n.binding("..."). */
    private static final Pattern KEY_REFERENCE =
            Pattern.compile("I18n\\.(?:get|binding)\\(\\s*\"([^\"]+)\"");

    /** Matches values containing a MessageFormat placeholder such as {0} or {1,number}. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d");

    /** The argument number of each MessageFormat placeholder in a value. */
    private static final Pattern ARGUMENT = Pattern.compile("\\{(\\d+)");

    @Test
    void enAndRuKeySetsMatch() {
        Set<String> enKeys = loadBundle(EN_BUNDLE).stringPropertyNames();
        Set<String> ruKeys = loadBundle(RU_BUNDLE).stringPropertyNames();
        assertThat(enKeys).isNotEmpty();

        Set<String> missingInRu = new TreeSet<>(enKeys);
        missingInRu.removeAll(ruKeys);
        Set<String> missingInEn = new TreeSet<>(ruKeys);
        missingInEn.removeAll(enKeys);

        assertThat(missingInRu).as("keys present in en but missing in ru").isEmpty();
        assertThat(missingInEn).as("keys present in ru but missing in en").isEmpty();
    }

    @Test
    void everyKeyReferencedInCodeExists() throws IOException {
        Path mainSources = Path.of("src", "main", "java");
        Assumptions.assumeTrue(Files.isDirectory(mainSources),
                "main sources not present in working directory; skipping source scan");

        Set<String> enKeys = loadBundle(EN_BUNDLE).stringPropertyNames();
        Map<String, Set<String>> referencedKeys = scanReferencedKeys(mainSources);
        assertThat(referencedKeys).isNotEmpty();

        List<String> missing = referencedKeys.entrySet().stream()
                .filter(entry -> !enKeys.contains(entry.getKey()))
                .map(entry -> entry.getKey() + " (referenced from " + entry.getValue() + ")")
                .toList();

        assertThat(missing)
                .as("keys referenced via I18n.get/I18n.binding but absent from " + EN_BUNDLE)
                .isEmpty();
    }

    @Test
    void messageFormatPatternsAreWellFormed() {
        List<String> malformed = new ArrayList<>();
        int checked = collectMalformedPatterns("en", loadBundle(EN_BUNDLE), malformed)
                + collectMalformedPatterns("ru", loadBundle(RU_BUNDLE), malformed);

        assertThat(checked).as("bundle values containing {n} placeholders").isPositive();
        assertThat(malformed)
                .as("bundle values with {n} placeholders rejected by MessageFormat")
                .isEmpty();
    }

    /**
     * A translation names the same arguments as the English it translates.
     * MessageFormat prints an argument as often as a pattern names it and
     * drops one it never names, so a Russian {@code dashboard.port.moved}
     * written with {1} where {0} belongs named the wrong port, with every
     * test green: each pattern on its own was well formed.
     */
    @Test
    void enAndRuNameTheSameArguments() {
        // The check itself: a translator's {1} for {0}.
        assertThat(argumentIndices("Port {0} is busy; using {1}"))
                .isNotEqualTo(argumentIndices("Порт {1} занят; используется {1}"));

        Properties en = loadBundle(EN_BUNDLE);
        Properties ru = loadBundle(RU_BUNDLE);
        List<String> drift = new ArrayList<>();
        for (String key : new TreeSet<>(en.stringPropertyNames())) {
            String translated = ru.getProperty(key);
            if (translated == null) {
                continue;
            }
            Set<Integer> english = argumentIndices(en.getProperty(key));
            Set<Integer> russian = argumentIndices(translated);
            if (!english.equals(russian)) {
                drift.add(key + ": en " + english + ", ru " + russian);
            }
        }
        assertThat(drift).as("keys whose en and ru values name different arguments").isEmpty();
    }

    /**
     * In a pattern that takes arguments an apostrophe is MessageFormat's
     * quote, not a letter. A lone one quotes everything after it, so "The VPN
     * core's output for {0}" came out as "The VPN cores output for {0}": the
     * apostrophe gone and the argument printed as written. Only a doubled
     * {@code ''} is a literal apostrophe there. Four English values carry an
     * apostrophe and are safe only because they take no arguments.
     */
    @Test
    void apostrophesInPatternsWithArgumentsSurviveFormatting() {
        // The check itself, on what went wrong and on what is right.
        assertThat(apostropheDamage("The VPN core's output for {0} is the only copy")).isPresent();
        assertThat(apostropheDamage("{0} isn't reachable")).isPresent();
        assertThat(apostropheDamage("Delete ''{0}''?")).isEmpty();

        List<String> damaged = new ArrayList<>();
        for (String bundle : List.of(EN_BUNDLE, RU_BUNDLE)) {
            Properties values = loadBundle(bundle);
            for (String key : new TreeSet<>(values.stringPropertyNames())) {
                String value = values.getProperty(key);
                if (PLACEHOLDER.matcher(value).find()) {
                    apostropheDamage(value).ifPresent(why -> damaged.add(
                            bundle + " " + key + ": " + why));
                }
            }
        }
        assertThat(damaged).as("values whose apostrophes MessageFormat eats").isEmpty();
    }

    /**
     * Both bundles are UTF-8 throughout. PropertyResourceBundle reads UTF-8
     * and, at the first byte that is not, silently starts over in ISO-8859-1
     * for the rest of the file. The Russian bundle has raw UTF-8 lines among
     * its escaped ones (the persistence banner, the copy suffix), so a single
     * stray byte above them garbled "Повторить запись" into mojibake and
     * named a duplicated server "X (ÐºÐ¾Ð¿Ð¸Ñ)" in servers.json, while every
     * test read the file leniently and passed.
     */
    @Test
    void bundlesAreUtf8Throughout() throws IOException {
        // The check itself: one « saved as the single Latin-1 byte 0xAB.
        assertThat(utf8Problem("a=1\nb=".getBytes(StandardCharsets.US_ASCII)))
                .isEmpty();
        assertThat(utf8Problem(new byte[] {'a', '=', '1', '\n', 'b', '=', (byte) 0xAB, '\n'}))
                .hasValueSatisfying(why -> assertThat(why).contains("line 2"));

        for (String bundle : List.of(EN_BUNDLE, RU_BUNDLE)) {
            byte[] bytes;
            try (InputStream stream = I18nBundleConsistencyTest.class.getResourceAsStream(bundle)) {
                assertThat(stream).as("classpath resource " + bundle).isNotNull();
                bytes = stream.readAllBytes();
            }
            assertThat(utf8Problem(bytes)).as(bundle).isEmpty();
        }
    }

    /** The argument numbers a pattern names: {0}, {1,number} and so on. */
    private static Set<Integer> argumentIndices(String pattern) {
        Set<Integer> indices = new TreeSet<>();
        Matcher matcher = ARGUMENT.matcher(pattern);
        while (matcher.find()) {
            indices.add(Integer.parseInt(matcher.group(1)));
        }
        return indices;
    }

    /**
     * What MessageFormat does to a pattern's apostrophes and arguments, when it
     * does anything: each argument it names must come out, and each apostrophe
     * with it, a doubled one as one.
     */
    private static Optional<String> apostropheDamage(String pattern) {
        Set<Integer> indices = argumentIndices(pattern);
        int count = indices.stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
        Object[] markers = new Object[count];
        for (int i = 0; i < count; i++) {
            markers[i] = "<arg" + i + ">";
        }
        String formatted;
        try {
            formatted = new MessageFormat(pattern, Locale.ROOT).format(markers);
        } catch (IllegalArgumentException e) {
            return Optional.of("rejected by MessageFormat: " + e.getMessage());
        }
        for (int index : indices) {
            if (!formatted.contains("<arg" + index + ">")) {
                return Optional.of("{" + index + "} is quoted away: " + formatted);
            }
        }
        if (apostrophes(formatted) != apostrophes(pattern.replace("''", "'"))) {
            return Optional.of("an apostrophe is lost: " + formatted);
        }
        return Optional.empty();
    }

    private static long apostrophes(String text) {
        return text.chars().filter(c -> c == '\'').count();
    }

    /** Where {@code bytes} stop being UTF-8, or empty when they never do. */
    private static Optional<String> utf8Problem(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(bytes);
        CharBuffer out = CharBuffer.allocate(bytes.length + 1);
        CoderResult result = decoder.decode(in, out, true);
        if (!result.isError()) {
            result = decoder.flush(out);
        }
        if (!result.isError()) {
            return Optional.empty();
        }
        int line = 1;
        for (int i = 0; i < in.position(); i++) {
            if (bytes[i] == '\n') {
                line++;
            }
        }
        return Optional.of("not UTF-8 at line " + line + ", byte " + in.position());
    }

    /**
     * Validates every placeholder-bearing value of the bundle against MessageFormat,
     * appending a diagnostic line per invalid value; returns the number of values checked.
     */
    private static int collectMalformedPatterns(
            String bundleName, Properties bundle, List<String> malformed) {
        int checked = 0;
        for (String key : new TreeSet<>(bundle.stringPropertyNames())) {
            String value = bundle.getProperty(key);
            if (!PLACEHOLDER.matcher(value).find()) {
                continue;
            }
            checked++;
            try {
                new MessageFormat(value);
            } catch (RuntimeException e) {
                malformed.add(bundleName + ":" + key + "=" + value + " -> " + e.getMessage());
            }
        }
        return checked;
    }

    /** Maps every string-literal i18n key used in main sources to the files referencing it. */
    private static Map<String, Set<String>> scanReferencedKeys(Path root) throws IOException {
        Map<String, Set<String>> keys = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        Matcher matcher = KEY_REFERENCE.matcher(readFile(path));
                        while (matcher.find()) {
                            keys.computeIfAbsent(matcher.group(1), k -> new TreeSet<>())
                                    .add(path.getFileName().toString());
                        }
                    });
        }
        return keys;
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + path, e);
        }
    }

    /**
     * Loads a bundle as UTF-8, mirroring the PropertyResourceBundle default since Java 9;
     * the escaped-ASCII ru bundle and the raw-UTF-8 en bundle both load correctly this way.
     */
    private static Properties loadBundle(String resourcePath) {
        Properties properties = new Properties();
        try (InputStream stream =
                I18nBundleConsistencyTest.class.getResourceAsStream(resourcePath)) {
            assertThat(stream).as("classpath resource " + resourcePath).isNotNull();
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + resourcePath, e);
        }
        return properties;
    }
}
