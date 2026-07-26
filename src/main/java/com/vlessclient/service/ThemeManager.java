package com.vlessclient.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
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
 * <p>Detection reads the {@code AppleInterfaceStyle} default via a fresh
 * subprocess each poll. This was chosen deliberately over the JavaFX
 * {@code Platform.getPreferences().colorScheme} property, which does not report
 * macOS appearance changes on the JavaFX 25-ea runtime this app bundles (its
 * listener never fires and its initial value is unreliable). A fresh
 * {@code defaults} read, by contrast, always reflects the current setting.</p>
 */
public class ThemeManager {

    private static final Logger log = LoggerFactory.getLogger(ThemeManager.class);

    private static final String LIGHT_CSS = "/css/light.css";
    private static final String DARK_CSS = "/css/dark.css";

    /** How often {@code "auto"} mode polls the OS appearance for changes. */
    private static final long WATCH_INTERVAL_SECONDS = 3;

    private volatile String currentTheme = "auto";

    /** The scene being styled. Only ever read/written on the JavaFX thread. */
    private Scene scene;

    private ScheduledExecutorService watcher;
    /** Last OS appearance observed by the watcher (true = dark, null = unknown). */
    private volatile Boolean lastSystemDark;

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
     * Returns the current theme setting.
     */
    public String getCurrentTheme() {
        return currentTheme;
    }

    /**
     * The stylesheet in effect right now, for scenes this manager does not own
     * — dialogs, mostly.
     *
     * <p>Deliberately not {@link #applyTheme}: that adopts the scene it is
     * given as <em>the</em> tracked scene, so calling it for a short-lived
     * dialog would leave the main window unwatched once the dialog closed, and
     * "auto" mode would stop following the OS there. Dialogs take a snapshot of
     * the current theme instead; they do not outlive a theme change in any way
     * that matters.</p>
     *
     * @return an external-form stylesheet URL, ready for {@code getStylesheets}
     */
    public String currentStylesheet() {
        String cssPath = resolveDark(isSystemDarkMode()) ? DARK_CSS : LIGHT_CSS;
        return Objects.requireNonNull(
                getClass().getResource(cssPath),
                "Theme CSS not found: " + cssPath
        ).toExternalForm();
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
        String cssPath = dark ? DARK_CSS : LIGHT_CSS;
        String cssUrl = Objects.requireNonNull(
                getClass().getResource(cssPath),
                "Theme CSS not found: " + cssPath
        ).toExternalForm();
        scene.getStylesheets().setAll(cssUrl);
        log.debug("Applied theme CSS: {}", cssPath);
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
        watcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "theme-watcher");
            t.setDaemon(true);
            return t;
        });
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
     * default. Returns true if dark mode is active. A fresh subprocess is used
     * each call so the value always reflects the current OS setting.
     */
    static boolean isSystemDarkMode() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "defaults", "read", "-g", "AppleInterfaceStyle");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                output = reader.readLine();
            }

            boolean exited = process.waitFor(2, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                return false;
            }

            return "Dark".equalsIgnoreCase(output != null ? output.trim() : "");
        } catch (Exception e) {
            log.debug("Could not detect system dark mode, defaulting to light", e);
            return false;
        }
    }
}
