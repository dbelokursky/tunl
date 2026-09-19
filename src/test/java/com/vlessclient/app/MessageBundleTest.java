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

    /**
     * A properties file quietly drops the backslash of an escape it does not
     * know: the Russian prompt for deleting a rule said
     * "xabDOMAIN_SUFFIX google.comxbb" for a value written as "\\xab{0}\\xbb".
     * Only the escapes the format means are allowed: \\t \\n \\f \\r, a
     * four-digit \\u, and an escaped separator, backslash or line end.
     */
    @Test
    void bundlesUseOnlyTheEscapesThePropertiesFormatMeans() throws IOException {
        for (String bundle : List.of("/i18n/messages_en.properties",
                "/i18n/messages_ru.properties")) {
            String text;
            try (var in = MessageBundleTest.class.getResourceAsStream(bundle)) {
                text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            List<String> bad = new java.util.ArrayList<>();
            for (int i = 0; i < text.length() - 1; i++) {
                if (text.charAt(i) != '\\') {
                    continue;
                }
                char next = text.charAt(i + 1);
                boolean fourHex = next == 'u' && i + 6 <= text.length()
                        && text.substring(i + 2, i + 6).matches("[0-9a-fA-F]{4}");
                if (!fourHex && "tnfr\\:=#! \r\n".indexOf(next) < 0) {
                    int lineStart = text.lastIndexOf('\n', i) + 1;
                    bad.add(text.substring(lineStart, text.indexOf('=', lineStart)));
                }
                i++;
            }
            assertThat(bad).as("keys with an escape %s does not mean", bundle).isEmpty();
        }
    }

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
