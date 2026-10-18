package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.app.ThemeCss;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.TestConfigStores;
import com.vlessclient.service.TextScale;
import com.vlessclient.testing.SettledLayout;
import com.vlessclient.testing.TestServers;
import com.vlessclient.testing.UiTest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.Control;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pages fit the window at the OS's larger text sizes, in the window the
 * app opens at and in the smallest it allows.
 *
 * <p>TextScale scales base.css's font and box sizes by the factor the OS's
 * text size asks for (Windows: Accessibility, Text size), and every page is
 * mounted in {@link ContentScrollPane}, which has no horizontal scroll bar.
 * So a row that needed more width than the page had was cut off with nothing
 * to scroll to:</p>
 * <ul>
 *   <li>Settings' device ID row keeps its label at the scaled 315px gutter
 *       and its Reset button whole, and at 150% that made the page 512px wide
 *       in a 438px viewport: every combo's arrow was past the edge.</li>
 *   <li>At 125% in Russian the Logs toolbar put the Clear button wholly past
 *       the edge, and the log list's own scroll bar with it.</li>
 *   <li>At 125% the Servers header ellipsized its Backup and Import menus to
 *       "..." and "И...", which could no longer be told apart.</li>
 * </ul>
 *
 * <p>Each page is mounted in MainView, sidebar and all, at each size, text
 * size and language, in a scene of its own, and measured by the bounds its
 * controls have in that scene.</p>
 */
@UiTest
public class LargerTextFitTest extends ApplicationTest {

    /** The window VlessClientApp opens, and the smallest it lets the user make. */
    private static final int[][] WINDOWS = {{820, 500}, {760, 460}};

    /** Windows' usual text size and its larger steps, up to the largest it offers here. */
    private static final double[] TEXT_SIZES = {1.0, 1.25, 1.5, 2.0};

    private static final List<Locale> LANGUAGES = List.of(Locale.ENGLISH, Locale.of("ru"));

    @TempDir
    static Path tempDir;

    private String baseCss;

    @Override
    public void start(Stage stage) {
        // Servers shows its search and sort only when it has a server.
        ConfigStore store = TestConfigStores.at(tempDir.resolve("data"));
        store.getServers().clear();
        store.addServer(TestServers.server().name("Netherlands 01").build());
        ServiceLocator.register(ConfigStore.class, store);
        try (InputStream in = getClass().getResourceAsStream("/css/base.css")) {
            baseCss = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @AfterEach
    void english() {
        interact(() -> I18n.setLocale(Locale.ENGLISH));
    }

    /**
     * The device ID's label and its Reset button whole at every size, as
     * d181492 made them at 125%; the MCP command's two buttons up to 150%,
     * where they go one under the other. At 200% in the smaller windows each
     * of those two is wider than the whole card, and is cut where it is.
     */
    @Test
    void settingsFitsTheWindowAtEveryTextSize() {
        assertFits("Settings", MainViewController::showSettings, Map.of(
                "#deviceIdLabel", 2.0, "#deviceIdResetButton", 2.0,
                "#mcpCopyButton", 1.5, "#mcpRegenButton", 1.5));
    }

    @Test
    void logsFitsTheWindowAtEveryTextSize() {
        assertFits("Logs", MainViewController::showLogs, Map.of());
    }

    @Test
    void serversFitsTheWindowAtEveryTextSize() {
        assertFits("Servers", MainViewController::showServers, Map.of(
                "#backupMenuButton", 2.0, "#importMenuButton", 2.0));
    }

    /**
     * At the usual text size the rows that can now wrap stay on one line, as
     * they were laid out before, in both windows and both languages.
     *
     * <p>All but one: in the smallest window in Russian the Servers header did
     * not fit on one line at the usual size either, and its title, its menus
     * and its button were all drawn cut ("Б...", "Им..."). It goes on to a
     * second line there now, and the checks above read its menus whole.</p>
     */
    @Test
    void atTheUsualTextSizeTheRowsStayOnOneLine() {
        List<String> wrapped = new ArrayList<>();
        for (int[] window : WINDOWS) {
            for (Locale language : LANGUAGES) {
                interact(() -> I18n.setLocale(language));
                String where = "%dx%d %s".formatted(window[0], window[1], language);
                oneLine(where, mainView(window, 1.0, MainViewController::showSettings),
                        "#deviceIdLabel", "#deviceIdResetButton", wrapped);
                oneLine(where, mainView(window, 1.0, MainViewController::showLogs),
                        "#logLevelFilter", "#clearButton", wrapped);
                Scene servers = mainView(window, 1.0, MainViewController::showServers);
                boolean headerFitBefore = window[0] != 760 || !"ru".equals(language.getLanguage());
                if (headerFitBefore) {
                    oneLine(where, servers, "#titleLabel", "#addServerButton", wrapped);
                }
                oneLine(where, servers, "#searchField", "#measureButton", wrapped);
            }
        }

        assertThat(wrapped)
                .withFailMessage("rows that wrap at the usual text size:%n  %s",
                        String.join("\n  ", wrapped))
                .isEmpty();
    }

    /**
     * Every control of the page within the viewport's right edge at every
     * window, text size and language, and each control of {@code whole} drawn
     * with all of its text up to the text size it maps to.
     */
    private void assertFits(String page, Consumer<MainViewController> show,
                            Map<String, Double> whole) {
        List<String> wrong = new ArrayList<>();
        for (int[] window : WINDOWS) {
            for (double size : TEXT_SIZES) {
                for (Locale language : LANGUAGES) {
                    interact(() -> I18n.setLocale(language));
                    String where = "%s %dx%d at %.0f%% %s".formatted(page, window[0], window[1],
                            size * 100, language);
                    Scene scene;
                    try {
                        scene = mainView(window, size, show);
                    } catch (AssertionError unsettled) {
                        // A layout that never settles is a defect of its own,
                        // and its geometry is not worth measuring.
                        wrong.add(where + ": " + unsettled.getMessage());
                        continue;
                    }
                    List<String> wholeHere = whole.entrySet().stream()
                            .filter(upTo -> size <= upTo.getValue())
                            .map(Map.Entry::getKey).sorted().toList();
                    interact(() -> measure(where, scene, wholeHere, wrong));
                }
            }
        }

        assertThat(wrong)
                .withFailMessage("ContentScrollPane has no horizontal scroll bar, so these are "
                        + "cut off or unreadable:%n  %s", String.join("\n  ", wrong))
                .isEmpty();
    }

    private static void measure(String where, Scene scene, List<String> whole,
                                List<String> wrong) {
        ScrollPane pane = shownPage(scene);
        if (pane == null) {
            wrong.add(where + ": no page on screen");
            return;
        }
        Node viewport = pane.lookup(".viewport");
        double edge = inScene(viewport).getMaxX();
        for (Node node : pane.getContent().lookupAll("*")) {
            if (!(node instanceof Control control) || !actionable(control) || !shown(control)
                    || insideAnotherControl(control, pane)) {
                continue;
            }
            Bounds bounds = inScene(control);
            if (bounds.getWidth() > 0 && bounds.getMaxX() > edge + 0.5) {
                wrong.add("%s: %s reaches x=%.1f, past the viewport's edge at %.1f".formatted(
                        where, describe(control), bounds.getMaxX(), edge));
            }
        }
        for (String id : whole) {
            Node node = scene.lookup(id);
            String full = node instanceof Labeled labeled ? labeled.getText() : null;
            String drawn = drawnText(node);
            if (full == null || !full.equals(drawn)) {
                wrong.add("%s: %s \"%s\" is drawn \"%s\"".formatted(where, id, full, drawn));
            }
        }
    }

    /** Whether {@code second} sits on the line {@code first} starts. */
    private void oneLine(String where, Scene scene, String first, String second,
                         List<String> wrapped) {
        interact(() -> {
            Bounds a = inScene(scene.lookup(first));
            Bounds b = inScene(scene.lookup(second));
            if (Math.abs(a.getCenterY() - b.getCenterY()) > 1) {
                wrapped.add("%s: %s at y=%.1f, %s at y=%.1f".formatted(
                        where, first, a.getCenterY(), second, b.getCenterY()));
            }
        });
    }

    /**
     * MainView at {@code window}, dressed as ThemeManager dresses it at
     * {@code size}, with a page mounted the way its nav button mounts it.
     * Never shown, as in SettingsScrollBarTest: a stage would clamp the size
     * to the runner's screen. Laid out until it settles.
     */
    private Scene mainView(int[] window, double size, Consumer<MainViewController> show) {
        Scene[] holder = new Scene[1];
        interact(() -> {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainView.fxml"));
            Parent root;
            try {
                root = loader.load();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            Scene scene = new Scene(new Group(), window[0], window[1]);
            List<String> sheets = new ArrayList<>(ThemeCss.light());
            if (size > 1) {
                // Last, where ThemeManager puts it.
                sheets.add(TextScale.dataUrl(TextScale.scaledRules(baseCss, size)));
            }
            scene.getStylesheets().setAll(sheets);
            scene.setRoot(root);
            show.accept(loader.getController());
            root.resize(window[0], window[1]);
            root.applyCss();
            root.layout();
            holder[0] = scene;
        });
        SettledLayout.settle(holder[0]);
        return holder[0];
    }

    private static ScrollPane shownPage(Scene scene) {
        for (Node node : scene.getRoot().lookupAll(".content-scroll")) {
            if (node instanceof ScrollPane pane && shown(pane) && pane.getContent() != null
                    && pane.getWidth() > 0) {
                return pane;
            }
        }
        return null;
    }

    /** What the user has to reach: the controls, not the containers around them. */
    private static boolean actionable(Control control) {
        return control instanceof ButtonBase || control instanceof ComboBoxBase<?>
                || control instanceof TextInputControl || control instanceof ListView<?>;
    }

    /** The text a labelled control draws, a menu button's included. */
    private static String drawnText(Node node) {
        Node label = node instanceof MenuButton menu ? menu.lookup(".label") : node;
        if (label == null) {
            return null;
        }
        for (Node child : label.lookupAll(".text")) {
            if (child instanceof Text text && child.getParent() == label) {
                return text.getText();
            }
        }
        return null;
    }

    private static Bounds inScene(Node node) {
        return node.getParent().localToScene(node.getBoundsInParent());
    }

    private static boolean shown(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private static boolean insideAnotherControl(Node node, Node stop) {
        for (Node parent = node.getParent(); parent != null && parent != stop;
                parent = parent.getParent()) {
            if (parent instanceof Control) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Node node) {
        return node.getId() != null ? "#" + node.getId()
                : node.getClass().getSimpleName() + "." + String.join(".", node.getStyleClass());
    }
}
