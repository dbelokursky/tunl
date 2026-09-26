package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.testing.Contrast;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.scene.paint.Color;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The secondary texts and the field borders reach WCAG AA in both themes.
 *
 * <p>They were set for looks: server addresses and the lines under a
 * subscription at 2.68:1 on the light rows, the hints of the empty pages at
 * 1.79:1, the fields' placeholders at 2.4 to 2.7, and the borders that tell
 * a field from the page at 1.2 to 1.3, where text needs 4.5:1 and the edge of
 * a control 3:1. Read from the theme files, on every background each token
 * is drawn on.</p>
 */
class ThemeTokenContrastTest {

    private static final double TEXT = Contrast.READABLE;
    private static final double BOUNDARY = 3.0;
    private static final Pattern TOKEN = Pattern.compile("(-c-[a-z-]+):\\s*(#[0-9a-fA-F]{6})");

    @ParameterizedTest
    @ValueSource(strings = {"light", "dark"})
    void secondaryTextsAndFieldEdgesAreReadable(String theme) throws IOException {
        Map<String, Color> tokens = tokens("/css/" + theme + ".css");

        assertReadable(tokens, "-c-text-meta", TEXT, "-c-row-bg", "-c-page", "-c-surface-sunken");
        assertReadable(tokens, "-c-empty-title", TEXT, "-c-page", "-c-surface");
        assertReadable(tokens, "-c-empty-hint", TEXT, "-c-page", "-c-surface");
        assertReadable(tokens, "-c-field-prompt", TEXT,
                "-c-field-bg", "-c-surface", "-c-surface-sunken");
        assertReadable(tokens, "-c-text-placeholder", TEXT, "-c-field-bg", "-c-surface");
        assertReadable(tokens, "-c-field-border", BOUNDARY,
                "-c-field-bg", "-c-surface", "-c-surface-sunken", "-c-page");
    }

    private static void assertReadable(Map<String, Color> tokens, String token, double least,
                                       String... backgrounds) {
        for (String background : backgrounds) {
            assertThat(Contrast.ratio(tokens.get(token), tokens.get(background)))
                    .as("%s on %s", token, background)
                    .isGreaterThanOrEqualTo(least);
        }
    }

    /** The colour tokens a theme file defines, by name. */
    static Map<String, Color> tokens(String resource) throws IOException {
        String css;
        try (InputStream in = ThemeTokenContrastTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, Color> tokens = new HashMap<>();
        Matcher matcher = TOKEN.matcher(css);
        while (matcher.find()) {
            tokens.put(matcher.group(1), Color.web(matcher.group(2)));
        }
        return tokens;
    }
}
