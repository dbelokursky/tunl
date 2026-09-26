package com.vlessclient.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The OS's text size as a factor on the app's font sizes, and the stylesheet
 * that applies it.
 *
 * <p>base.css sets every font size in pixels, which JavaFX draws as they are:
 * a user who made the system's text larger (Windows: Accessibility, Text
 * size) got it everywhere but here. JavaFX reports that setting as the
 * default font's size, 12px on Windows at the usual size and 13px on the
 * other systems. When it is larger, a stylesheet added after base.css sets
 * the same rules' sizes multiplied by that factor: the font sizes, and the
 * widths and heights of the boxes the text sits in (a button's 34, the
 * sidebar's 212). Hairlines under 20 pixels keep their size. The pixel values
 * stay what the layout is tested with, and at the usual size nothing is
 * added.</p>
 */
public final class TextScale {

    private static final Pattern RULE = Pattern.compile("([^{}]+)\\{([^{}]*)}");
    private static final Pattern FONT_SIZE =
            Pattern.compile("-fx-font-size\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)px");
    private static final Pattern BOX_SIZE = Pattern.compile(
            "(-fx-(?:min|pref|max)-(?:width|height))\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)(?:px)?\\s*;");

    /** Box sizes smaller than this are lines and gaps, which text does not fill. */
    private static final double SMALLEST_BOX = 20;
    private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /** Larger than this, a factor is taken as the user's: rounding stays out. */
    private static final double THRESHOLD = 1.01;

    /** Beyond this the windows are unusable anyway; the text stops growing. */
    private static final double MAX = 3.0;

    private TextScale() {
    }

    /**
     * The factor the OS's text size asks for.
     *
     * @param defaultFontSize the size of JavaFX's default font, in pixels
     * @param windows         whether the OS is Windows, whose usual size is 12px
     * @return 1 at the usual size or smaller, else the ratio to it, at most 3
     */
    public static double factor(double defaultFontSize, boolean windows) {
        double usual = windows ? 12 : 13;
        double factor = defaultFontSize / usual;
        return factor < THRESHOLD ? 1.0 : Math.min(factor, MAX);
    }

    /**
     * The rules of {@code css} that set a font size in pixels, or a box size of
     * 20 pixels or more, with those sizes multiplied by {@code factor};
     * nothing else.
     *
     * @param css    a stylesheet
     * @param factor the text size factor
     * @return a stylesheet to add after {@code css}
     */
    public static String scaledRules(String css, double factor) {
        StringBuilder out = new StringBuilder();
        Matcher rule = RULE.matcher(COMMENT.matcher(css).replaceAll(""));
        while (rule.find()) {
            StringBuilder declarations = new StringBuilder();
            Matcher font = FONT_SIZE.matcher(rule.group(2));
            if (font.find()) {
                declarations.append(" -fx-font-size: ")
                        .append(scaled(font.group(1), factor)).append("px;");
            }
            Matcher box = BOX_SIZE.matcher(rule.group(2));
            while (box.find()) {
                if (Double.parseDouble(box.group(2)) >= SMALLEST_BOX) {
                    declarations.append(' ').append(box.group(1)).append(": ")
                            .append(scaled(box.group(2), factor)).append("px;");
                }
            }
            if (!declarations.isEmpty()) {
                out.append(rule.group(1).strip()).append(" {").append(declarations)
                        .append(" }\n");
            }
        }
        return out.toString();
    }

    private static String scaled(String size, double factor) {
        return String.format(Locale.ROOT, "%.1f", Double.parseDouble(size) * factor);
    }

    /**
     * A stylesheet as a {@code data:} URL, which a scene takes like a file's.
     *
     * @param css the stylesheet
     * @return its URL
     */
    public static String dataUrl(String css) {
        return "data:text/css;base64,"
                + Base64.getEncoder().encodeToString(css.getBytes(StandardCharsets.UTF_8));
    }
}
