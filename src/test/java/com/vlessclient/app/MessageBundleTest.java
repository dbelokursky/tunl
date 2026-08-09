package com.vlessclient.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the two ways a translation goes wrong without anything failing to
 * compile: a key the code asks for that no bundle answers, and a key one
 * language has and the other does not.
 *
 * <p>Neither is loud at runtime. {@link I18n#get} returns the key itself when
 * it is missing, so the user reads "dashboard.copied" off a button; and a key
 * absent from {@code messages_ru} silently falls back to English, which looks
 * like a translation nobody got round to rather than a mistake.</p>
 */
public class MessageBundleTest {

    /** Keys as they are written at the call site. */
    private static final Pattern LOOKUP = Pattern.compile(
            "(?:I18n\\.(?:get|binding)|ButtonLabels\\.(?:bind|bindStatic|bindAddAction|show"
                    + "|flash))\\(([^;]*?)\\)");
    private static final Pattern KEY_LITERAL = Pattern.compile("\"([a-z][a-z0-9]*(?:\\.[a-z0-9]+)+)\"");

    @Test
    void bothLanguagesDefineTheSameKeys() throws IOException {
        Set<String> english = keysOf("/i18n/messages_en.properties");
        Set<String> russian = keysOf("/i18n/messages_ru.properties");

        assertThat(difference(english, russian))
                .withFailMessage("defined in English but not Russian, so these silently "
                        + "fall back and read as untranslated: %s", difference(english, russian))
                .isEmpty();
        assertThat(difference(russian, english))
                .withFailMessage("defined in Russian but not English: %s",
                        difference(russian, english))
                .isEmpty();
    }

    @Test
    void everyKeyTheCodeAsksForIsDefined() throws IOException {
        Set<String> defined = keysOf("/i18n/messages_en.properties");
        Set<String> missing = new TreeSet<>();

        for (Path source : javaSources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            Matcher call = LOOKUP.matcher(text);
            while (call.find()) {
                Matcher key = KEY_LITERAL.matcher(call.group(1));
                while (key.find()) {
                    if (!defined.contains(key.group(1))) {
                        missing.add(key.group(1) + "  (" + source.getFileName() + ")");
                    }
                }
            }
        }

        assertThat(missing)
                .withFailMessage("asked for by the code but absent from the bundle — I18n.get "
                        + "returns the key itself, so the user reads it off the control: %s",
                        missing)
                .isEmpty();
    }

    private static Set<String> difference(Set<String> from, Set<String> without) {
        Set<String> only = new TreeSet<>(from);
        only.removeAll(without);
        return only;
    }

    private static Set<String> keysOf(String resource) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = MessageBundleTest.class.getResourceAsStream(resource)) {
            assertThat(in).withFailMessage("%s is missing", resource).isNotNull();
            properties.load(in);
        }
        return new TreeSet<>(properties.stringPropertyNames());
    }

    private static List<Path> javaSources() throws IOException {
        try (Stream<Path> tree = Files.walk(Path.of("src", "main", "java"))) {
            return tree.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }
}
