package com.vlessclient.app;

import java.util.List;
import java.util.Objects;

/**
 * The stylesheets a scene needs to look like the shipped app, for tests that
 * dress a view themselves instead of going through {@code ThemeManager}.
 *
 * <p>base.css carries every rule and light.css / dark.css carry only the
 * {@code -c-*} tokens it resolves, so a scene handed the theme file alone gets
 * stock Modena with a few loose colour definitions — which is not a UI this
 * project ships, and not one worth measuring.</p>
 */
public final class ThemeCss {

    private ThemeCss() {
    }

    /** Base then theme, in the order they must be applied. */
    public static List<String> of(String theme) {
        return List.of(url("/css/base.css"), url("/css/" + theme + ".css"));
    }

    /** Convenience for the many tests that only ever dress a view in light. */
    public static List<String> light() {
        return of("light");
    }

    private static String url(String resource) {
        return Objects.requireNonNull(ThemeCss.class.getResource(resource),
                "stylesheet not on the test classpath: " + resource).toExternalForm();
    }
}
