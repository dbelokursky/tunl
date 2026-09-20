package com.vlessclient.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every text in the bundle is one the app asks for.
 *
 * <p>{@link MessageBundleTest} guards the other direction — a key the code
 * asks for that no bundle answers. This one catches the text nobody reads: a
 * screen that was cut, a button that was renamed, a shortcut list that was
 * never built. Twenty had gathered by the 2026-09-19 review (item 5.8).
 * Nothing fails over them, and they cost real work: they are translated,
 * reviewed and carried into every later change as if they were on screen.</p>
 *
 * <p>A key counts as read when its name appears as a literal in main's Java or
 * FXML. Two shapes are read without ever being written out:</p>
 *
 * <ul>
 *   <li>a plural triplet, through its base — {@code I18n.plural(base, n)}
 *       builds {@code base.one}, {@code base.few} and {@code base.many} at
 *       runtime;</li>
 *   <li>anything listed in {@link #BUILT_AT_RUNTIME}, where the key is
 *       assembled from a value. Each entry says who assembles it, so the list
 *       cannot quietly become a place to park a dead key.</li>
 * </ul>
 */
class BundleKeysAreReadTest {

    /** The suffixes {@code I18n.plural} appends to a base key. */
    private static final Set<String> PLURAL_FORMS = Set.of("one", "few", "many");

    /**
     * Prefixes whose keys are built from a value rather than written out.
     *
     * <p>Empty today: every such key in this bundle is reached through a
     * literal or a plural base. An entry belongs here only with the caller
     * named beside it.</p>
     */
    private static final Set<String> BUILT_AT_RUNTIME = Set.of();

    @Test
    void everyKeyInTheBundleIsAskedForSomewhere() throws IOException {
        List<String> keys = keysOf(Path.of("src", "main", "resources", "i18n",
                "messages_en.properties"));
        String sources = mainSources();

        Set<String> unread = new TreeSet<>();
        for (String key : keys) {
            if (!isRead(key, sources)) {
                unread.add(key);
            }
        }

        assertThat(unread)
                .withFailMessage("in the bundle but asked for nowhere, so they are translated "
                        + "and reviewed as if they were on screen: %s", unread)
                .isEmpty();
    }

    private static boolean isRead(String key, String sources) {
        if (sources.contains('"' + key + '"')) {
            return true;
        }
        int lastDot = key.lastIndexOf('.');
        if (lastDot > 0 && PLURAL_FORMS.contains(key.substring(lastDot + 1))
                && sources.contains('"' + key.substring(0, lastDot) + '"')) {
            return true;
        }
        return BUILT_AT_RUNTIME.stream().anyMatch(key::startsWith);
    }

    private static List<String> keysOf(Path bundle) throws IOException {
        List<String> keys = new ArrayList<>();
        for (String line : Files.readAllLines(bundle, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                keys.add(trimmed.substring(0, trimmed.indexOf('=')));
            }
        }
        return keys;
    }

    /** Main's Java and FXML as one string; a key is a literal in one of them. */
    private static String mainSources() throws IOException {
        StringBuilder all = new StringBuilder();
        for (Path root : List.of(Path.of("src", "main", "java"),
                Path.of("src", "main", "resources", "fxml"))) {
            try (Stream<Path> tree = Files.walk(root)) {
                for (Path file : tree.filter(Files::isRegularFile).toList()) {
                    all.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
                }
            }
        }
        return all.toString();
    }
}
