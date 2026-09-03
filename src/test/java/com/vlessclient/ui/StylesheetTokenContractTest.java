package com.vlessclient.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contract that lets one base stylesheet serve both themes.
 *
 * <p>base.css holds every rule and names colour only through {@code -c-*}
 * tokens; light.css and dark.css hold nothing but those tokens. Break either
 * half and JavaFX says nothing useful — an undefined looked-up colour renders
 * as a silent fallback, and a colour literal that creeps back into base.css
 * simply looks wrong in one theme and right in the other. Both are cheap to
 * catch by reading the files, which is all this test does; it needs no JavaFX
 * toolkit and so runs everywhere.</p>
 *
 * <p>Companion to {@code ThemeTokenResolutionTest}, which proves the tokens
 * actually resolve at runtime. This one proves the two files agree on what
 * exists.</p>
 */
class StylesheetTokenContractTest {

    private static final String BASE = "/css/base.css";
    private static final String LIGHT = "/css/light.css";
    private static final String DARK = "/css/dark.css";

    /** A token being defined: {@code -c-name:} at the start of a declaration. */
    private static final Pattern DEFINITION = Pattern.compile("(-c-[a-z0-9-]+)\\s*:");

    /** Hex triples/quads and the rgb()/rgba() functions. Named colours are not
     *  matched: "transparent" is legitimate and theme-independent, and the CSS
     *  carries no other colour names. */
    private static final Pattern COLOUR_LITERAL =
            Pattern.compile("#[0-9a-fA-F]{3,8}\\b|\\brgba?\\s*\\(");

    @Test
    void bothThemesDefineTheSameTokens() {
        Set<String> light = definitions(LIGHT);
        Set<String> dark = definitions(DARK);

        assertThat(new TreeSet<>(dark))
                .withFailMessage("dark.css is missing tokens light.css defines: %s%n"
                        + "A token defined in only one theme resolves to a silent JavaFX "
                        + "fallback in the other.", new TreeSet<>(difference(light, dark)))
                .containsAll(light);
        assertThat(new TreeSet<>(light))
                .withFailMessage("light.css is missing tokens dark.css defines: %s",
                        new TreeSet<>(difference(dark, light)))
                .containsAll(dark);
    }

    @Test
    void baseStylesheetNamesNoColourDirectly() {
        Matcher matcher = COLOUR_LITERAL.matcher(withoutComments(read(BASE)));

        Set<String> found = new LinkedHashSet<>();
        while (matcher.find()) {
            found.add(matcher.group());
        }

        assertThat(found)
                .withFailMessage("base.css names colour directly: %s%n"
                        + "Every colour there has to be a -c-* token, or it is a value the "
                        + "two themes cannot disagree about — which is the whole point of "
                        + "splitting base.css out.", found)
                .isEmpty();
    }

    @Test
    void everyTokenBaseUsesIsDefinedByBothThemes() {
        Set<String> used = references(BASE);

        assertThat(new TreeSet<>(definitions(LIGHT)))
                .withFailMessage("base.css uses tokens light.css does not define: %s",
                        new TreeSet<>(difference(used, definitions(LIGHT))))
                .containsAll(used);
        assertThat(new TreeSet<>(definitions(DARK)))
                .withFailMessage("base.css uses tokens dark.css does not define: %s",
                        new TreeSet<>(difference(used, definitions(DARK))))
                .containsAll(used);
    }

    /**
     * A token nothing reads is dead weight that still has to be kept in step
     * across two files — exactly the maintenance cost this layout removes.
     */
    @Test
    void everyDefinedTokenIsUsed() {
        Set<String> unused = difference(definitions(LIGHT), references(BASE));

        assertThat(new TreeSet<>(unused))
                .withFailMessage("tokens defined by the themes that base.css never reads: %s",
                        new TreeSet<>(unused))
                .isEmpty();
    }

    /**
     * The theme files are values, not rules. A structural declaration there is
     * a second copy of a base.css rule and will drift from it — which is how
     * the separator ended up 3px high in one theme and 2px in the other.
     */
    @Test
    void themeFilesCarryOnlyTokensAndTheDocumentedOverrides() {
        assertOnlyTokenDeclarations(LIGHT);
        assertOnlyTokenDeclarations(DARK);
    }

    private static void assertOnlyTokenDeclarations(String stylesheet) {
        String body = withoutComments(read(stylesheet));
        Set<String> fxProperties = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("(-fx-[a-z-]+)\\s*:").matcher(body);
        while (matcher.find()) {
            fxProperties.add(matcher.group(1));
        }

        // -fx-opacity is the one documented non-colour override: JavaFX
        // looked-up values are colours only, so a per-theme number cannot be
        // expressed as a token. Fewer is fine; more is drift.
        assertThat(fxProperties)
                .withFailMessage("%s declares JavaFX properties beyond the documented "
                        + "non-colour overrides: %s%n"
                        + "Structure belongs in base.css.", stylesheet, fxProperties)
                .isSubsetOf("-fx-opacity");
    }

    private static Set<String> definitions(String stylesheet) {
        return matches(DEFINITION, withoutComments(read(stylesheet)));
    }

    private static Set<String> references(String stylesheet) {
        Set<String> found = new LinkedHashSet<>();
        String body = withoutComments(read(stylesheet));
        Matcher matcher = Pattern.compile("(-c-[a-z0-9-]+)(\\s*:)?").matcher(body);
        while (matcher.find()) {
            if (matcher.group(2) == null) {
                found.add(matcher.group(1));
            }
        }
        return found;
    }

    private static Set<String> matches(Pattern pattern, String body) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(body);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static Set<String> difference(Set<String> from, Set<String> remove) {
        Set<String> copy = new LinkedHashSet<>(from);
        copy.removeAll(remove);
        return copy;
    }

    /** Comments are documentation; the example colours in them are not rules. */
    private static String withoutComments(String css) {
        return css.replaceAll("(?s)/\\*.*?\\*/", "");
    }

    private static String read(String resource) {
        try (InputStream in = StylesheetTokenContractTest.class.getResourceAsStream(resource)) {
            assertThat(in).withFailMessage("%s is not on the classpath", resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
