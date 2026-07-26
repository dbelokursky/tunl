package com.vlessclient.ui.view;

import java.util.Locale;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;

/**
 * Small country flags, drawn as shapes.
 *
 * <p><b>Why not emoji.</b> The obvious approach — regional-indicator pairs like
 * 🇳🇱 — renders as two boxed letters on Windows, which has never shipped flag
 * glyphs. The app supports three platforms, so an emoji flag would look
 * correct only on the developer's machine.</p>
 *
 * <p>Flags are built from rectangles rather than traced paths: the ones worth
 * showing for VPN endpoints are overwhelmingly stripes and crosses, which stay
 * crisp at badge size where fine heraldic detail turns to mud anyway. Anything
 * not drawn here falls back to {@link #code(String, double)} — the country's
 * letters in a rounded chip, which is honest about being an abbreviation
 * rather than a bad drawing.</p>
 */
public final class Flags {

    /** Flags render at 4:3, the shape most national flags approximate. */
    private static final double ASPECT = 4.0 / 3.0;

    private Flags() {
    }

    /**
     * A flag for the given ISO 3166-1 alpha-2 code.
     *
     * @param isoCode two-letter country code, any case; null or unknown yields
     *                the lettered fallback
     * @param height  flag height in pixels; width follows the 4:3 aspect
     * @return a node ready to drop into a layout
     */
    public static Node of(String isoCode, double height) {
        if (isoCode == null || isoCode.isBlank()) {
            return code("??", height);
        }
        String iso = isoCode.trim().toUpperCase(Locale.ROOT);
        double w = height * ASPECT;

        return switch (iso) {
            // Horizontal tricolours
            case "NL" -> horizontal(w, height, "#AE1C28", "#FFFFFF", "#21468B");
            case "RU" -> horizontal(w, height, "#FFFFFF", "#0039A6", "#D52B1E");
            case "DE" -> horizontal(w, height, "#000000", "#DD0000", "#FFCE00");
            case "AT" -> horizontal(w, height, "#ED2939", "#FFFFFF", "#ED2939");
            case "BG" -> horizontal(w, height, "#FFFFFF", "#00966E", "#D62612");
            case "HU" -> horizontal(w, height, "#CD2A3E", "#FFFFFF", "#436F4D");
            case "LT" -> horizontal(w, height, "#FDB913", "#006A44", "#C1272D");
            case "EE" -> horizontal(w, height, "#0072CE", "#000000", "#FFFFFF");
            case "UA" -> horizontal(w, height, "#0057B7", "#0057B7", "#FFD700");
            case "PL" -> horizontal(w, height, "#FFFFFF", "#FFFFFF", "#DC143C");
            case "ID" -> horizontal(w, height, "#FF0000", "#FF0000", "#FFFFFF");
            case "SG" -> horizontal(w, height, "#ED2939", "#ED2939", "#FFFFFF");

            // Vertical tricolours
            case "FR" -> vertical(w, height, "#002395", "#FFFFFF", "#ED2939");
            case "IT" -> vertical(w, height, "#008C45", "#F4F5F0", "#CD212A");
            case "IE" -> vertical(w, height, "#169B62", "#FFFFFF", "#FF883E");
            case "BE" -> vertical(w, height, "#000000", "#FAE042", "#ED2939");
            case "RO" -> vertical(w, height, "#002B7F", "#FCD116", "#CE1126");

            // Nordic crosses
            case "SE" -> nordicCross(w, height, "#006AA7", "#FECC00");
            case "FI" -> nordicCross(w, height, "#FFFFFF", "#003580");
            case "NO" -> nordicCross(w, height, "#BA0C2F", "#FFFFFF");
            case "DK" -> nordicCross(w, height, "#C8102E", "#FFFFFF");
            case "IS" -> nordicCross(w, height, "#02529C", "#FFFFFF");

            // One-offs simple enough to be recognisable at this size
            case "JP" -> disc(w, height, "#FFFFFF", "#BC002D", 0.30);
            case "CH" -> swissCross(w, height);
            case "ES" -> horizontal(w, height, "#AA151B", "#F1BF00", "#AA151B");
            case "PT" -> vertical(w, height, "#046A38", "#046A38", "#DA291C");
            case "AE" -> horizontal(w, height, "#00732F", "#FFFFFF", "#000000");

            // Drawn properly rather than abbreviated: these carry the detail
            // that makes them recognisable, so leaving it out would have meant
            // showing another country's flag.
            case "US" -> starsAndStripes(w, height);
            case "GB" -> unionJack(w, height);
            case "CN" -> chinaStars(w, height);
            case "TR" -> turkey(w, height);
            case "CA" -> canada(w, height);
            case "AU" -> australia(w, height);
            case "VN" -> vietnam(w, height);
            case "MY" -> malaysia(w, height);

            // Only reached for a country with no drawing yet — the letters say
            // "code known, flag not drawn" rather than guessing.
            default -> code(iso, height);
        };
    }

    /** The country's letters in a rounded chip, for anything not drawn. */
    public static Node code(String isoCode, double height) {
        double w = height * ASPECT;
        Rectangle chip = new Rectangle(w, height);
        chip.setArcWidth(height * 0.35);
        chip.setArcHeight(height * 0.35);
        chip.getStyleClass().add("flag-chip");

        javafx.scene.text.Text text = new javafx.scene.text.Text(
                isoCode == null ? "??" : isoCode.trim().toUpperCase(Locale.ROOT));
        text.getStyleClass().add("flag-chip-text");
        text.setStyle("-fx-font-size: " + Math.round(height * 0.62) + "px;");
        // Centre the letters inside the chip.
        text.setX((w - text.getLayoutBounds().getWidth()) / 2);
        text.setY(height - (height - text.getLayoutBounds().getHeight()) / 2
                - text.getLayoutBounds().getHeight() * 0.18);

        return framed(new Group(chip, text), w, height);
    }

    private static Node horizontal(double w, double h, String top, String mid, String bottom) {
        double band = h / 3;
        return framed(new Group(
                band(0, 0, w, band, top),
                band(0, band, w, band, mid),
                band(0, band * 2, w, h - band * 2, bottom)), w, h);
    }

    private static Node vertical(double w, double h, String left, String mid, String right) {
        double band = w / 3;
        return framed(new Group(
                band(0, 0, band, h, left),
                band(band, 0, band, h, mid),
                band(band * 2, 0, w - band * 2, h, right)), w, h);
    }

    /** Off-centre cross, as every Nordic flag places it. */
    private static Node nordicCross(double w, double h, String field, String cross) {
        double arm = h * 0.22;
        double x = w * 0.32;
        return framed(new Group(
                band(0, 0, w, h, field),
                band(0, (h - arm) / 2, w, arm, cross),
                band(x - arm / 2, 0, arm, h, cross)), w, h);
    }

    private static Node swissCross(double w, double h) {
        double arm = h * 0.20;
        double len = h * 0.62;
        return framed(new Group(
                band(0, 0, w, h, "#FF0000"),
                band((w - len) / 2, (h - arm) / 2, len, arm, "#FFFFFF"),
                band((w - arm) / 2, (h - len) / 2, arm, len, "#FFFFFF")), w, h);
    }

    private static Node disc(double w, double h, String field, String discColor, double radius) {
        Circle circle = new Circle(w / 2, h / 2, h * radius);
        circle.setFill(Color.web(discColor));
        return framed(new Group(band(0, 0, w, h, field), circle), w, h);
    }

    /**
     * A five-pointed star as a ten-vertex polygon: outer points on one radius,
     * inner points on another, alternating. Drawn geometrically rather than as
     * a traced path so it stays sharp at any badge size.
     *
     * @param rotationDeg 0 points the star straight up
     */
    private static javafx.scene.shape.Polygon star(double cx, double cy, double outer,
                                                   double rotationDeg, String color) {
        javafx.scene.shape.Polygon star = new javafx.scene.shape.Polygon();
        double inner = outer * 0.382;   // golden-ratio waist: what reads as a star
        for (int i = 0; i < 10; i++) {
            double radius = (i % 2 == 0) ? outer : inner;
            double angle = Math.toRadians(rotationDeg - 90 + i * 36);
            star.getPoints().addAll(cx + radius * Math.cos(angle),
                                    cy + radius * Math.sin(angle));
        }
        star.setFill(Color.web(color));
        return star;
    }

    /**
     * A crescent: a filled disc with a second, offset disc punched out of it
     * in the field colour. That is how the shape actually works, and it beats
     * approximating the horns with an arc.
     */
    private static Group crescent(double cx, double cy, double radius,
                                  double offset, String moon, String field) {
        Circle full = new Circle(cx, cy, radius);
        full.setFill(Color.web(moon));
        Circle bite = new Circle(cx + offset, cy, radius * 0.82);
        bite.setFill(Color.web(field));
        return new Group(full, bite);
    }

    /** Clips a group to the flag rectangle so nothing bleeds past the edge. */
    private static Group clipped(Group group, double w, double h) {
        group.setClip(new Rectangle(w, h));
        return group;
    }

    private static Rectangle band(double x, double y, double w, double h, String color) {
        Rectangle rect = new Rectangle(x, y, w, h);
        rect.setFill(Color.web(color));
        return rect;
    }

    /**
     * Thirteen stripes, a blue canton, and a star grid standing in for fifty
     * stars — at badge height fifty would merge into a smudge, so a readable
     * 5x4 lattice conveys "star field" honestly.
     */
    private static Node starsAndStripes(double w, double h) {
        Group flag = new Group(band(0, 0, w, h, "#FFFFFF"));
        double stripe = h / 13;
        for (int i = 0; i < 13; i += 2) {
            flag.getChildren().add(band(0, i * stripe, w, stripe, "#B22234"));
        }
        double cantonW = w * 0.40;
        double cantonH = stripe * 7;
        flag.getChildren().add(band(0, 0, cantonW, cantonH, "#3C3B6E"));

        double cols = 5;
        double rows = 4;
        double r = Math.min(cantonW / cols, cantonH / rows) * 0.34;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                double cx = cantonW * (col + 0.5) / cols;
                double cy = cantonH * (row + 0.5) / rows;
                flag.getChildren().add(star(cx, cy, r, 0, "#FFFFFF"));
            }
        }
        return framed(clipped(flag, w, h), w, h);
    }

    /**
     * The Union Jack: white saltire, red saltire offset within it, then the
     * white-bordered red cross on top. Built from rotated rectangles, which is
     * what the diagonals actually are.
     */
    private static Node unionJack(double w, double h) {
        Group flag = new Group(band(0, 0, w, h, "#012169"));
        double diagonal = Math.hypot(w, h);
        double angle = Math.toDegrees(Math.atan2(h, w));

        for (double[] spec : new double[][] {{h * 0.30, 0}, {h * 0.16, 0}}) {
            String color = spec[0] > h * 0.2 ? "#FFFFFF" : "#C8102E";
            for (double sign : new double[] {1, -1}) {
                Rectangle arm = band(-diagonal / 2, -spec[0] / 2, diagonal, spec[0], color);
                arm.setRotate(sign * angle);
                Group holder = new Group(arm);
                holder.setTranslateX(w / 2);
                holder.setTranslateY(h / 2);
                flag.getChildren().add(holder);
            }
        }

        double crossOuter = h * 0.32;
        double crossInner = h * 0.19;
        flag.getChildren().addAll(
                band(0, (h - crossOuter) / 2, w, crossOuter, "#FFFFFF"),
                band((w - crossOuter) / 2, 0, crossOuter, h, "#FFFFFF"),
                band(0, (h - crossInner) / 2, w, crossInner, "#C8102E"),
                band((w - crossInner) / 2, 0, crossInner, h, "#C8102E"));
        return framed(clipped(flag, w, h), w, h);
    }

    /** One large star with four smaller ones arcing around it. */
    private static Node chinaStars(double w, double h) {
        Group flag = new Group(band(0, 0, w, h, "#EE1C25"));
        double big = h * 0.17;
        double bigX = w * 0.17;
        double bigY = h * 0.28;
        flag.getChildren().add(star(bigX, bigY, big, 0, "#FFDE00"));

        double small = big * 0.36;
        double[][] satellites = {{0.33, 0.12}, {0.40, 0.26}, {0.40, 0.44}, {0.33, 0.58}};
        for (double[] pos : satellites) {
            flag.getChildren().add(
                    star(w * pos[0], h * pos[1], small, 20, "#FFDE00"));
        }
        return framed(clipped(flag, w, h), w, h);
    }

    /** Crescent and star on red — the detail a plain disc was missing. */
    private static Node turkey(double w, double h) {
        Group flag = new Group(band(0, 0, w, h, "#E30A17"));
        flag.getChildren().add(
                crescent(w * 0.36, h / 2, h * 0.22, h * 0.07, "#FFFFFF", "#E30A17"));
        flag.getChildren().add(star(w * 0.55, h / 2, h * 0.11, 0, "#FFFFFF"));
        return framed(clipped(flag, w, h), w, h);
    }

    /** Red-white-red bands with a maple leaf — without it this is Peru. */
    private static Node canada(double w, double h) {
        // Points are computed in flag coordinates rather than drawn as a
        // scaled SVGPath: scaling leaves the node's layout bounds at their
        // original size, which stretched the whole badge and pushed the leaf
        // out of view.
        double size = h * 0.80;
        double cx = w / 2;
        double cy = h / 2;
        double[] shape = {
            50, 8, 57, 30, 72, 22, 66, 40, 86, 38, 74, 50, 92, 60, 70, 62,
            76, 78, 57, 70, 54, 92, 46, 92, 43, 70, 24, 78, 30, 62, 8, 60,
            26, 50, 14, 38, 34, 40, 28, 22, 43, 30,
        };
        javafx.scene.shape.Polygon leaf = new javafx.scene.shape.Polygon();
        for (int i = 0; i < shape.length; i += 2) {
            leaf.getPoints().addAll(
                    cx + (shape[i] - 50) / 100.0 * size,
                    cy + (shape[i + 1] - 50) / 100.0 * size);
        }
        leaf.setFill(Color.web("#D80621"));

        Group flag = new Group(
                band(0, 0, w * 0.25, h, "#D80621"),
                band(w * 0.25, 0, w * 0.50, h, "#FFFFFF"),
                band(w * 0.75, 0, w * 0.25, h, "#D80621"),
                leaf);
        return framed(clipped(flag, w, h), w, h);
    }

    /** Union canton, Commonwealth star, and the Southern Cross. */
    private static Node australia(double w, double h) {
        Group flag = new Group(band(0, 0, w, h, "#012169"));
        Group canton = new Group(unionJack(w * 0.5, h * 0.5));
        flag.getChildren().add(canton);
        flag.getChildren().add(star(w * 0.25, h * 0.75, h * 0.13, 0, "#FFFFFF"));
        double[][] cross = {{0.72, 0.22}, {0.83, 0.45}, {0.72, 0.70}, {0.61, 0.47}, {0.78, 0.55}};
        for (int i = 0; i < cross.length; i++) {
            double r = i == cross.length - 1 ? h * 0.05 : h * 0.09;
            flag.getChildren().add(star(w * cross[i][0], h * cross[i][1], r, 0, "#FFFFFF"));
        }
        return framed(clipped(flag, w, h), w, h);
    }

    /** Red field, yellow star. */
    private static Node vietnam(double w, double h) {
        Group flag = new Group(band(0, 0, w, h, "#DA251D"));
        flag.getChildren().add(star(w / 2, h / 2, h * 0.30, 0, "#FFFF00"));
        return framed(clipped(flag, w, h), w, h);
    }

    /** Fourteen stripes, blue canton, crescent and star. */
    private static Node malaysia(double w, double h) {
        Group flag = new Group(band(0, 0, w, h, "#FFFFFF"));
        double stripe = h / 14;
        for (int i = 0; i < 14; i += 2) {
            flag.getChildren().add(band(0, i * stripe, w, stripe, "#CC0001"));
        }
        flag.getChildren().add(band(0, 0, w * 0.5, stripe * 8, "#010066"));
        flag.getChildren().add(
                crescent(w * 0.20, stripe * 4, h * 0.17, h * 0.055, "#FFCC00", "#010066"));
        flag.getChildren().add(star(w * 0.34, stripe * 4, h * 0.11, 0, "#FFCC00"));
        return framed(clipped(flag, w, h), w, h);
    }

    /**
     * Adds the hairline border every flag needs: without it a white or light
     * band bleeds into the row background and the flag loses its edge.
     */
    private static Node framed(Group flag, double w, double h) {
        Rectangle border = new Rectangle(w, h);
        border.setFill(Color.TRANSPARENT);
        border.getStyleClass().add("flag-border");
        flag.getChildren().add(border);
        return flag;
    }
}
