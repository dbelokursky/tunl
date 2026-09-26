package com.vlessclient.service;

import com.vlessclient.platform.MacAppearance;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.text.Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages light/dark theme switching.
 *
 * <p>Supports three modes: {@code "auto"}, {@code "light"} and {@code "dark"}
 * ({@code "system"} is accepted as a legacy alias for {@code "auto"}). In
 * {@code "auto"} mode the theme follows the macOS appearance and switches
 * automatically <em>while the app is running</em>: a lightweight background
 * watcher polls the OS appearance and re-applies the stylesheet when the user
 * toggles macOS between Light and Dark.</p>
 *
 * <p>Each poll reads the {@code AppleInterfaceStyle} default in-process through
 * {@link MacAppearance}, rather than starting a {@code defaults} process every
 * few seconds. Reading the default was chosen deliberately over the JavaFX
 * {@code Platform.getPreferences().colorScheme} property, which does not report
 * macOS appearance changes on the JavaFX runtime this app bundles (its
 * listener never fires and its initial value is unreliable). The default, by
 * contrast, always reflects the current setting.</p>
 *
 * <p>The native title bar is not the stylesheet's to paint: JavaFX paints a
 * window's frame from its scene's color scheme, and a scene given none takes
 * that same unreliable platform value. So every scene dressed in a theme is
 * also given the theme's color scheme; until it was, the frame stayed white
 * over the dark theme.</p>
 */
public class ThemeManager {

    private static final Logger log = LoggerFactory.getLogger(ThemeManager.class);

    /**
     * Structure for every theme: applied first so the per-theme token file can
     * only ever change colour, never layout.
     */
    private static final String BASE_CSS = "/css/base.css";
    private static final String LIGHT_CSS = "/css/light.css";
    private static final String DARK_CSS = "/css/dark.css";

    /** How often {@code "auto"} mode polls the OS appearance for changes. */
    private static final long WATCH_INTERVAL_SECONDS = 3;

    private volatile String currentTheme = "auto";

    /** The scene being styled. Only ever read/written on the JavaFX thread. */
    private Scene scene;

    private ScheduledExecutorService watcher;

    /**
     * The OS's text size as a factor on base.css's font sizes, or null until
     * the first stylesheet is asked for: JavaFX knows its default font only
     * once the toolkit runs.
     */
    private Double textScale;

    /** base.css's font rules at {@link #textScale}, as a data URL; null at 1. */
    private String scaledFonts;

    /** Last OS appearance observed by the watcher (true = dark, null = unknown). */
    private volatile Boolean lastSystemDark;

    /** A manager that follows the OS's text size. */
    public ThemeManager() {
    }

    /**
     * A manager at a fixed text size, for tests.
     *
     * @param textScale the factor on base.css's font sizes
     */
    ThemeManager(double textScale) {
        this.textScale = textScale;
    }

    /**
     * Sets the theme preference. Valid values: {@code "auto"}, {@code "light"},
     * {@code "dark"}. {@code "system"} and any unknown value normalize to
     * {@code "auto"}.
     */
    public void setTheme(String theme) {
        this.currentTheme = normalize(theme);
        log.info("Theme set to: {}", currentTheme);
    }

    /**
     * Normalizes a stored/preference value, mapping the legacy {@code "system"}
     * value and any unrecognized value onto a supported theme.
     */
    public static String normalize(String theme) {
        if (theme == null) {
            return "auto";
        }
        return switch (theme) {
            case "light", "dark" -> theme;
            default -> "auto"; // "auto", legacy "system", or anything unknown
        };
    }

    /**
     * Applies the current theme to the given scene by swapping stylesheets, and
     * (re)starts the OS-appearance watcher so {@code "auto"} mode keeps
     * following the system setting while the app is running.
     */
    public void applyTheme(Scene scene) {
        this.scene = scene;
        boolean osDark = isSystemDarkMode();
        lastSystemDark = osDark;
        setStylesheet(resolveDark(osDark));
        ensureWatcher();
    }

    /**
     * Applies the current theme again to the scene it was last applied to,
     * after {@link #setTheme} changed it from somewhere that has no scene of
     * its own, such as an MCP agent. Nothing happens before the main window
     * has been themed. Runs on the JavaFX thread.
     */
    public void reapply() {
        if (scene != null) {
            applyTheme(scene);
        }
    }

    /**
     * Returns the current theme setting.
     */
    public String getCurrentTheme() {
        return currentTheme;
    }

    /**
     * The stylesheets in effect right now, for scenes this manager does not own
     * — dialogs, mostly.
     *
     * <p>Deliberately not {@link #applyTheme}: that adopts the scene it is
     * given as <em>the</em> tracked scene, so calling it for a short-lived
     * dialog would leave the main window unwatched once the dialog closed, and
     * "auto" mode would stop following the OS there. Dialogs take a snapshot of
     * the current theme instead; they do not outlive a theme change in any way
     * that matters.</p>
     *
     * @return base then theme, in the order they must be applied — the theme
     *     file carries only {@code -c-*} tokens, so a dialog handed the theme
     *     alone would have no rules at all
     */
    public List<String> currentStylesheets() {
        return stylesheetsFor(resolveDark(isSystemDarkMode()));
    }

    /**
     * The color scheme in effect right now, for the native frame of a scene
     * this manager does not own: a snapshot, like {@link #currentStylesheets()}.
     *
     * @return the scheme JavaFX is to paint the window's title bar in
     */
    public ColorScheme currentColorScheme() {
        return colorSchemeFor(resolveDark(isSystemDarkMode()));
    }

    private boolean isAutoMode() {
        return "auto".equals(currentTheme);
    }

    /**
     * Resolves whether the dark stylesheet should be used, given the supplied
     * OS appearance (so callers can avoid a redundant system query).
     */
    private boolean resolveDark(boolean osDark) {
        return switch (currentTheme) {
            case "light" -> false;
            case "dark" -> true;
            default -> osDark; // "auto"
        };
    }

    private void setStylesheet(boolean dark) {
        if (scene == null) {
            return;
        }
        scene.getStylesheets().setAll(stylesheetsFor(dark));
        // A shown window repaints its frame when the scheme changes.
        scene.getPreferences().setColorScheme(colorSchemeFor(dark));
        log.debug("Applied theme CSS: {} + {}", BASE_CSS, dark ? DARK_CSS : LIGHT_CSS);
    }

    private static ColorScheme colorSchemeFor(boolean dark) {
        return dark ? ColorScheme.DARK : ColorScheme.LIGHT;
    }

    /**
     * Base first, theme second: the theme file only defines {@code -c-*}
     * tokens, and base.css resolves them, so the order is what makes the app
     * look like anything at all.
     */
    private List<String> stylesheetsFor(boolean dark) {
        List<String> sheets = new ArrayList<>(List.of(
                externalForm(BASE_CSS), externalForm(dark ? DARK_CSS : LIGHT_CSS)));
        String fonts = scaledFonts();
        if (fonts != null) {
            // Last, so its sizes win over base.css's for the same selectors.
            sheets.add(fonts);
        }
        return sheets;
    }

    /** base.css's font rules at the OS's text size, or null at the usual size. */
    private synchronized String scaledFonts() {
        if (textScale == null) {
            textScale = TextScale.factor(Font.getDefault().getSize(),
                    System.getProperty("os.name", "").toLowerCase().contains("win"));
            if (textScale > 1) {
                log.info("Text size {}x the usual: scaling the font sizes", textScale);
            }
        }
        if (textScale <= 1) {
            return null;
        }
        if (scaledFonts == null) {
            try (InputStream in = Objects.requireNonNull(
                    getClass().getResourceAsStream(BASE_CSS), BASE_CSS)) {
                String css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                scaledFonts = TextScale.dataUrl(TextScale.scaledRules(css, textScale));
            } catch (IOException e) {
                log.warn("Could not scale the font sizes to the OS's text size", e);
                textScale = 1.0;
                return null;
            }
        }
        return scaledFonts;
    }

    private String externalForm(String cssPath) {
        return Objects.requireNonNull(
                getClass().getResource(cssPath),
                "Theme CSS not found: " + cssPath
        ).toExternalForm();
    }

    /**
     * Starts the background watcher that polls the macOS appearance setting and
     * re-applies the theme when it changes while in {@code "auto"} mode. No-op
     * on non-macOS platforms and idempotent (at most one watcher ever runs).
     */
    private synchronized void ensureWatcher() {
        if (watcher != null || !isMac()) {
            return;
        }
        watcher = Executors.newSingleThreadScheduledExecutor(
                DaemonThreads.factory("theme-watcher"));
        watcher.scheduleWithFixedDelay(this::pollSystemAppearance,
                WATCH_INTERVAL_SECONDS, WATCH_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.debug("Theme watcher started ({}s interval)", WATCH_INTERVAL_SECONDS);
    }

    /**
     * One watcher tick: detect the current OS appearance and, when in auto mode
     * and it has changed since the last observation, re-apply the stylesheet on
     * the JavaFX thread.
     */
    private void pollSystemAppearance() {
        try {
            boolean osDark = isSystemDarkMode();
            Boolean previous = lastSystemDark;
            lastSystemDark = osDark;
            if (isAutoMode() && (previous == null || previous != osDark)) {
                log.info("System appearance changed to {} — updating auto theme",
                        osDark ? "dark" : "light");
                Platform.runLater(() -> setStylesheet(osDark));
            }
        } catch (Exception e) {
            log.debug("Theme watcher poll failed", e);
        }
    }

    /**
     * Stops the background appearance watcher. Safe to call multiple times.
     */
    public synchronized void stopWatching() {
        if (watcher != null) {
            watcher.shutdownNow();
            watcher = null;
        }
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    /**
     * Detects macOS dark mode by reading the {@code AppleInterfaceStyle}
     * default. Returns true if dark mode is active, false on other platforms.
     * Every call reads the current value, so the watcher sees the user switch.
     */
    static boolean isSystemDarkMode() {
        return MacAppearance.isDark();
    }
}
