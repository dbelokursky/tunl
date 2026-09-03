package com.vlessclient.ui.view;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

/**
 * Central factory for the monochrome flat icons used in the sidebar and
 * elsewhere. Every icon is a single {@link SVGPath} drawn inside a fixed
 * 24×24 viewBox — the same format Material Design Icons and Heroicons use —
 * so we get crisp, CSS-colorable glyphs without a font dependency.
 *
 * <p>The path data is copied from Material Design Icons
 * (<a href="https://materialdesignicons.com">materialdesignicons.com</a>,
 * Apache-2.0). Each {@code create*} method returns a fresh {@link Node}
 * sized to {@code size}×{@code size} points so it can be dropped into any
 * layout container.</p>
 */
public final class Icons {

    // ---- Material Design Icons path data (24x24 viewBox) ----
    // mdi-speedometer: a gauge with a needle — reads as "dashboard" better
    // than the old four-tile grid glyph.
    private static final String DASHBOARD =
            "M12,16A3,3 0 0,1 9,13C9,11.88 9.61,10.9 10.5,10.39L20.21,4.77L14.68,"
                    + "14.35C14.18,15.33 13.17,16 12,16M12,3C13.81,3 15.5,3.5 16.97,4.32L14."
                    + "87,5.53C14,5.19 13,5 12,5A8,8 0 0,0 4,13C4,15.21 4.89,17.21 6.34,18.65"
                    + "H6.35C6.74,19.04 6.74,19.67 6.35,20.06C5.96,20.45 5.32,20.45 4.93,20."
                    + "07V20.07C3.12,18.26 2,15.76 2,13A10,10 0 0,1 12,3M22,13C22,15.76 20."
                    + "88,18.26 19.07,20.07V20.07C18.68,20.45 18.05,20.45 17.66,20.06C17.27,"
                    + "19.67 17.27,19.04 17.66,18.65V18.65C19.11,17.2 20,15.21 20,13C20,12 "
                    + "19.81,11 19.46,10.1L20.67,8C21.5,9.5 22,11.19 22,13Z";
    // mdi-server-network: a server stack over data-transfer arrows — carries
    // the "servers moving traffic" idea better than a bare stack.
    private static final String SERVER =
            "M13,19H14A1,1 0 0,1 15,20H22V22H15A1,1 0 0,1 14,23H10A1,1 0 0,1 9,22H2"
                    + "V20H9A1,1 0 0,1 10,19H11V17H4A1,1 0 0,1 3,16V12A1,1 0 0,1 4,11H20A1,1 "
                    + "0 0,1 21,12V16A1,1 0 0,1 20,17H13V19M4,3H20A1,1 0 0,1 21,4V8A1,1 0 0,1"
                    + " 20,9H4A1,1 0 0,1 3,8V4A1,1 0 0,1 4,3M9,7H10V5H9V7M9,15H10V13H9V15M5,5"
                    + "V7H7V5H5M5,13V15H7V13H5Z";
    // mdi-cloud-sync-outline: pulling server-list feeds from a remote URL and
    // keeping them refreshed — fits Subscriptions better than a bare link.
    private static final String LINK =
            "M13.03 18C13.08 18.7 13.24 19.38 13.5 20H6.5C5 20 3.69 19.5 2.61"
                    + " 18.43C1.54 17.38 1 16.09 1 14.58C1 13.28 1.39 12.12 2.17 11.1S4"
                    + " 9.43 5.25 9.15C5.67 7.62 6.5 6.38 7.75 5.43S10.42 4 12 4C13.95 "
                    + "4 15.6 4.68 16.96 6.04C18.32 7.4 19 9.05 19 11C19.04 11 19.07 11"
                    + " 19.1 11C18.36 11.07 17.65 11.23 17 11.5V11C17 9.62 16.5 8.44 15"
                    + ".54 7.46C14.56 6.5 13.38 6 12 6S9.44 6.5 8.46 7.46C7.5 8.44 7 9."
                    + "62 7 11H6.5C5.53 11 4.71 11.34 4.03 12.03C3.34 12.71 3 13.53 3 1"
                    + "4.5S3.34 16.29 4.03 17C4.71 17.66 5.53 18 6.5 18H13.03M19 13.5V1"
                    + "2L16.75 14.25L19 16.5V15C20.38 15 21.5 16.12 21.5 17.5C21.5 17.9"
                    + " 21.41 18.28 21.24 18.62L22.33 19.71C22.75 19.08 23 18.32 23 17."
                    + "5C23 15.29 21.21 13.5 19 13.5M19 20C17.62 20 16.5 18.88 16.5 17."
                    + "5C16.5 17.1 16.59 16.72 16.76 16.38L15.67 15.29C15.25 15.92 15 1"
                    + "6.68 15 17.5C15 19.71 16.79 21.5 19 21.5V23L21.25 20.75L19 18.5V"
                    + "20Z";
    // mdi-call-split: traffic branching by rule — clearer than the old arrows.
    private static final String ROUTING =
            "M14,4L16.29,6.29L13.41,9.17L14.83,10.59L17.71,7.71L20,10V4M10,4H"
                    + "4V10L6.29,7.71L11,12.41V20H13V11.59L7.71,6.29";
    // mdi-script-text-outline: a scrollable log — the sing-box output stream.
    private static final String LIST =
            "M15,20A1,1 0 0,0 16,19V4H8A1,1 0 0,0 7,5V16H5V5A3,3 0 0,1 8,2H19"
                    + "A3,3 0 0,1 22,5V6H20V5A1,1 0 0,0 19,4A1,1 0 0,0 18,5V9L18,19A3,3"
                    + " 0 0,1 15,22H5A3,3 0 0,1 2,19V18H13A2,2 0 0,0 15,20M9,6H14V8H9V6"
                    + "M9,10H14V12H9V10M9,14H14V16H9V14Z";
    private static final String SETTINGS =
            "M12,15.5A3.5,3.5 0 0,1 8.5,12A3.5,3.5 0 0,1 12,8.5A3.5,3.5 0 0,1 15.5,12A3"
                    + ".5,3.5 0 0,1 12,15.5M19.43,12.97C19.47,12.65 19.5,12.33 19.5,12C1"
                    + "9.5,11.67 19.47,11.34 19.43,11L21.54,9.37C21.73,9.22 21.78,8.95 2"
                    + "1.66,8.73L19.66,5.27C19.54,5.05 19.27,4.96 19.05,5.05L16.56,6.05"
                    + "C16.04,5.66 15.5,5.32 14.87,5.07L14.5,2.42C14.46,2.18 14.25,2 14"
                    + ",2H10C9.75,2 9.54,2.18 9.5,2.42L9.13,5.07C8.5,5.32 7.96,5.66 7.4"
                    + "4,6.05L4.95,5.05C4.73,4.96 4.46,5.05 4.34,5.27L2.34,8.73C2.21,8."
                    + "95 2.27,9.22 2.46,9.37L4.57,11C4.53,11.34 4.5,11.67 4.5,12C4.5,1"
                    + "2.33 4.53,12.65 4.57,12.97L2.46,14.63C2.27,14.78 2.21,15.05 2.34"
                    + ",15.27L4.34,18.73C4.46,18.95 4.73,19.03 4.95,18.95L7.44,17.94C7."
                    + "96,18.34 8.5,18.68 9.13,18.93L9.5,21.58C9.54,21.82 9.75,22 10,22"
                    + "H14C14.25,22 14.46,21.82 14.5,21.58L14.87,18.93C15.5,18.67 16.04"
                    + ",18.34 16.56,17.94L19.05,18.95C19.27,19.03 19.54,18.95 19.66,18."
                    + "73L21.66,15.27C21.78,15.05 21.73,14.78 21.54,14.63L19.43,12.97Z";

    private static final String DOWNLOAD =
            "M5,20H19V18H5M19,9H15V3H9V9H5L12,16L19,9Z";
    // mdi-chevron-double-up / -down: stacked chevrons read as a continuous
    // stream in one direction — the dashboard's upload/download markers.
    private static final String CHEVRON_DOUBLE_UP =
            "M7.41,18.41L6,17L12,11L18,17L16.59,18.41L12,13.83L7.41,18.41M7.41,"
                    + "12.41L6,11L12,5L18,11L16.59,12.41L12,7.83L7.41,12.41Z";
    private static final String CHEVRON_DOUBLE_DOWN =
            "M16.59,5.59L18,7L12,13L6,7L7.41,5.59L12,10.17L16.59,5.59M16.59,"
                    + "11.59L18,13L12,19L6,13L7.41,11.59L12,16.17L16.59,11.59Z";
    // mdi-bug: the diagnostics bundle a bug report is attached to.
    private static final String BUG =
            "M14,12H10V10H14M14,16H10V14H14M20,8H17.19C16.74,7.22 16.12,6.55 15.37,"
                    + "6.04L17,4.41L15.59,3L13.42,5.17C12.96,5.06 12.5,5 12,5C11.5,5 11.04,"
                    + "5.06 10.59,5.17L8.41,3L7,4.41L8.62,6.04C7.88,6.55 7.26,7.22 6.81,8H4"
                    + "V10H6.09C6.04,10.33 6,10.66 6,11V12H4V14H6V15C6,15.34 6.04,15.67 6.09"
                    + ",16H4V18H6.81C7.85,19.79 9.78,21 12,21C14.22,21 16.15,19.79 17.19,18H"
                    + "20V16H17.91C17.96,15.67 18,15.34 18,15V14H20V12H18V11C18,10.66 17.96,"
                    + "10.33 17.91,10H20V8Z";
    private static final String TRASH =
            "M19,4H15.5L14.5,3H9.5L8.5,4H5V6H19M6,19A2,2 0 0,0 8,21H16A2,2 0 0,0 18,"
                    + "19V7H6V19Z";

    private Icons() {
    }

    public static Node dashboard(double size) {
        return make(DASHBOARD, size);
    }

    public static Node server(double size) {
        return make(SERVER, size);
    }

    public static Node link(double size) {
        return make(LINK, size);
    }

    public static Node routing(double size) {
        return make(ROUTING, size);
    }

    public static Node list(double size) {
        return make(LIST, size);
    }

    public static Node settings(double size) {
        return make(SETTINGS, size);
    }

    public static Node download(double size) {
        return make(DOWNLOAD, size);
    }

    public static Node clear(double size) {
        return make(TRASH, size);
    }

    /**
     * A bug glyph, for the action that saves a diagnostics bundle.
     *
     * @param size the icon's edge length in points
     * @return a fresh node carrying the glyph
     */
    public static Node diagnostics(double size) {
        return make(BUG, size);
    }

    public static Node chevronDoubleUp(double size) {
        return make(CHEVRON_DOUBLE_UP, size);
    }

    public static Node chevronDoubleDown(double size) {
        return make(CHEVRON_DOUBLE_DOWN, size);
    }

    /**
     * Builds a sized {@link StackPane} containing an {@link SVGPath} scaled
     * from its native 24×24 viewBox to {@code size}×{@code size} points.
     * The glyph inherits its fill from the {@code .nav-icon} CSS rule so
     * theme switches don't need per-icon updates.
     */
    private static Node make(String path, double size) {
        SVGPath svg = new SVGPath();
        svg.setContent(path);
        svg.getStyleClass().add("nav-icon-glyph");
        // Fall-through fill so the icon is visible before CSS loads (and in
        // tests that don't apply a stylesheet).
        svg.setFill(Color.web("#6b7078"));

        double scale = size / 24.0;
        svg.setScaleX(scale);
        svg.setScaleY(scale);

        // Wrap in a Group so the scaled bounds are authoritative, and a
        // StackPane so the icon is centred within a square "cell".
        Group wrapper = new Group(svg);
        StackPane pane = new StackPane(wrapper);
        pane.setMinSize(size, size);
        pane.setPrefSize(size, size);
        pane.setMaxSize(size, size);
        pane.getStyleClass().add("nav-icon");
        return pane;
    }
}
