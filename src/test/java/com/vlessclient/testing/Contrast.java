package com.vlessclient.testing;

import java.util.List;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Paint;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;

/**
 * How well text reads on what it is drawn on, in the WCAG 2 terms base.css is
 * measured in.
 *
 * <p>Colours come from the computed style, as in {@code ThemeTokenResolutionTest}:
 * the property is exact, where a rendered pixel beside antialiased glyphs is
 * not.</p>
 */
public final class Contrast {

    /** WCAG AA for body text, the bar base.css already measures text against. */
    public static final double READABLE = 4.5;

    private Contrast() {
    }

    /** What a region's text is read against: the fill painted last, on top. */
    public static Color backdrop(Region region) {
        List<BackgroundFill> fills = region.getBackground().getFills();
        return flat(fills.get(fills.size() - 1).getFill());
    }

    /**
     * A paint as one colour. Modena fills its header band and buttons with a
     * gentle two-stop gradient, and the mean of the stops is the colour the eye
     * reads there.
     */
    public static Color flat(Paint paint) {
        return switch (paint) {
            case Color colour -> colour;
            case LinearGradient gradient -> mean(gradient.getStops());
            case RadialGradient gradient -> mean(gradient.getStops());
            default -> throw new AssertionError("no single colour in " + paint);
        };
    }

    /** Nearer black than white, measured the way text contrast is. */
    public static boolean isDark(Color colour) {
        return ratio(colour, Color.WHITE) > ratio(colour, Color.BLACK);
    }

    /** The WCAG 2 contrast ratio. */
    public static double ratio(Color first, Color second) {
        double lighter = Math.max(luminance(first), luminance(second));
        double darker = Math.min(luminance(first), luminance(second));
        return (lighter + 0.05) / (darker + 0.05);
    }

    private static Color mean(List<Stop> stops) {
        double red = 0;
        double green = 0;
        double blue = 0;
        for (Stop stop : stops) {
            red += stop.getColor().getRed();
            green += stop.getColor().getGreen();
            blue += stop.getColor().getBlue();
        }
        return Color.color(red / stops.size(), green / stops.size(), blue / stops.size());
    }

    /** WCAG 2 relative luminance. */
    private static double luminance(Color colour) {
        return 0.2126 * linear(colour.getRed()) + 0.7152 * linear(colour.getGreen())
                + 0.0722 * linear(colour.getBlue());
    }

    private static double linear(double channel) {
        return channel <= 0.03928 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }
}
