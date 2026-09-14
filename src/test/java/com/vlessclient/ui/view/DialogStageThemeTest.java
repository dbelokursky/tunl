package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.app.ThemeCss;
import com.vlessclient.service.SingBoxInstaller;
import com.vlessclient.service.ThemeManager;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.Contrast;
import com.vlessclient.testing.UiTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two windows the views open as Stages rather than Dialogs, the sing-box
 * installer and the server form, belong to the window the view is in: they
 * sit over it and wear its theme.
 *
 * <p>A Dialog gets both from its owner inside JavaFX: HeavyweightDialog binds
 * the dialog scene's stylesheets to the owner scene's, and the stage it builds
 * overrides {@code centerOnScreen} to sit over the owner. {@code Stage.initOwner}
 * does neither. The installer had no owner and no stylesheet at all, so over
 * the dark window it came up as stock light Modena in the middle of the
 * screen, and even in the light theme its hint read 4.19:1 on Modena's grey
 * and its copy link 3.09:1 on the orange box. The server form copied the
 * theme's stylesheets but, unowned, took the middle of the screen too.</p>
 *
 * <p>Each stage is opened the way the user opens it, by the view's button,
 * fired through {@code Platform.runLater}: both are shown with
 * {@code showAndWait}, which an {@code interact} would wait out. The window a
 * view is mounted in is a stage of the test's own, placed away from the middle
 * of the screen so that sitting over it and sitting in the middle of the
 * screen differ; the primary stage is shared by the classes after this one,
 * and a position given to it would carry over. Every check collects what it
 * finds before one assertion, so one problem does not hide the rest.</p>
 */
@UiTest
public class DialogStageThemeTest extends ApplicationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    private final CountDownLatch release = new CountDownLatch(1);
    private List<Window> windowsBefore;
    private Stage window;

    @Override
    public void start(Stage primary) {
        windowsBefore = List.copyOf(Window.getWindows());
        window = new Stage();
        window.setScene(new Scene(new StackPane(), 900, 600));
        window.setX(40);
        window.setY(30);
        window.show();
    }

    /** Hiding a stage also ends the showAndWait a failed assertion left open. */
    @AfterEach
    void closeTheWindows() {
        release.countDown();
        interact(() -> {
            for (Window shown : List.copyOf(Window.getWindows())) {
                if (!windowsBefore.contains(shown)) {
                    shown.hide();
                }
            }
            I18n.setLocale(Locale.ENGLISH);
        });
    }

    // ===== The sing-box installer =====

    @ParameterizedTest
    @ValueSource(strings = {"light", "dark"})
    void theInstallerWhileItDownloads(String theme) {
        ServiceLocator.register(SingBoxInstaller.class, new StalledInstaller(release));
        Parent dashboard = mount("DashboardView", theme);

        Stage installer = open(button(dashboard, "#retryInstallButton")::fire);
        Await.until("the download to report progress", () -> onFx(() -> ((ProgressBar)
                installer.getScene().getRoot().lookup(".progress-bar")).getProgress() > 0),
                PATIENCE);

        List<String> problems = new ArrayList<>();
        problems.addAll(ownership(installer));
        problems.addAll(placement(installer));
        problems.addAll(dress(installer, theme));
        List<String> read = readInTheTheme(installer.getScene().getRoot(), theme, problems);
        assertNoProblems(theme + ": the installer while it downloads", problems);
        assertThat(read)
                .as("the heading, the status, the hint and the button")
                .contains(I18n.get("installer.heading"),
                        I18n.get("installer.downloading", SingBoxInstaller.PINNED_VERSION),
                        I18n.get("installer.hint"), I18n.get("button.cancel"));
    }

    /**
     * A failed download grows the stage down from the top edge it opened
     * with, to make room for the Homebrew fallback, so where its middle ends
     * up is not checked here.
     */
    @ParameterizedTest
    @ValueSource(strings = {"light", "dark"})
    void theInstallerAfterAFailedDownload(String theme) {
        ServiceLocator.register(SingBoxInstaller.class, new FailingInstaller());
        Parent dashboard = mount("DashboardView", theme);

        Stage installer = open(button(dashboard, "#retryInstallButton")::fire);
        Parent root = installer.getScene().getRoot();
        Hyperlink copy = awaitTheFallback(root);

        List<String> problems = new ArrayList<>();
        problems.addAll(ownership(installer));
        problems.addAll(dress(installer, theme));
        List<String> read = readInTheTheme(root, theme, problems);
        // Modena gives a visited link a colour of its own.
        interact(() -> copy.setVisited(true));
        List<String> visited = new ArrayList<>();
        readInTheTheme(copy, theme, visited);
        visited.forEach(problem -> problems.add("once visited, " + problem));
        assertNoProblems(theme + ": the installer after a failed download", problems);
        assertThat(read)
                .as("the fallback and every button")
                .contains(I18n.get("installer.error.header"), I18n.get("installer.error.body"),
                        SingBoxInstaller.brewInstallCommand(), I18n.get("installer.copy.command"),
                        I18n.get("button.retry"), I18n.get("installer.skip"),
                        I18n.get("button.cancel"));
    }

    /**
     * Russian is the widest language the app speaks. In the app's button
     * padding, the three buttons of a failed download take 438 of the row's
     * 440px at the widths Linux draws Cyrillic in, where macOS leaves them
     * room, so this is measured for real on the Linux runners. Read once the
     * stage is as wide as its content asks: before that, a label is cramped
     * only for the moment the stage takes to grow.
     */
    @Test
    void theInstallerDrawsEveryButtonWholeInRussian() {
        ServiceLocator.register(SingBoxInstaller.class, new FailingInstaller());
        interact(() -> I18n.setLocale(Locale.of("ru")));
        Parent dashboard = mount("DashboardView", "light");

        Stage installer = open(button(dashboard, "#retryInstallButton")::fire);
        Region root = (Region) installer.getScene().getRoot();
        awaitTheFallback(root);
        Await.until("the installer to be as wide as its content asks", () -> onFx(() ->
                !root.isNeedsLayout()
                        && root.getWidth() >= Math.max(root.prefWidth(-1), root.minWidth(-1)) - 0.5),
                PATIENCE);

        List<String> clipped = onFx(() -> {
            List<String> found = new ArrayList<>();
            for (Node node : root.lookupAll(".button")) {
                if (node instanceof Button button && isShown(button)
                        && button.lookup(".text") instanceof Text drawn
                        && !button.getText().equals(drawn.getText())) {
                    found.add("\"" + button.getText() + "\" is drawn \"" + drawn.getText() + "\"");
                }
            }
            return found;
        });
        assertNoProblems("ru: the installer's buttons", clipped);
    }

    /**
     * A view in no window has no window to give: the installer then opens on
     * its own, in the theme all the same, the way the app opens it before its
     * window exists.
     */
    @Test
    void theInstallerOfAViewInNoWindowOpensOnItsOwn() {
        ServiceLocator.register(SingBoxInstaller.class, new StalledInstaller(release));
        Parent dashboard = mount("DashboardView", "dark");
        Button retry = button(dashboard, "#retryInstallButton");
        interact(() -> window.getScene().setRoot(new StackPane()));

        Stage installer = open(retry::fire);

        List<String> problems = new ArrayList<>(dress(installer, "dark"));
        Window owner = onFx(installer::getOwner);
        if (owner != null) {
            problems.add("owned by " + owner + " though the view is in no window");
        }
        double[] at = onFx(() -> new double[] {installer.getX(), installer.getY()});
        if (Double.isNaN(at[0]) || Double.isNaN(at[1])) {
            problems.add(String.format(Locale.ROOT, "placed at (%s, %s)", at[0], at[1]));
        }
        assertNoProblems("dark: the installer of a view in no window", problems);
    }

    // ===== The server form =====

    @ParameterizedTest
    @ValueSource(strings = {"light", "dark"})
    void theServerForm(String theme) {
        Parent servers = mount("ServersView", theme);

        Stage form = open(button(servers, "#addServerButton")::fire);

        List<String> problems = new ArrayList<>();
        problems.addAll(ownership(form));
        problems.addAll(placement(form));
        problems.addAll(dress(form, theme));
        assertNoProblems(theme + ": the server form", problems);
        assertThat(onFx(form::getTitle)).isEqualTo(I18n.get("dialog.add.server"));
    }

    // ===== Opening and reading stages =====

    /**
     * Puts a view in the window, dressed in {@code theme} the way
     * ThemeManager dresses the main window, with ThemeManager holding the
     * same theme, and returns the view's root.
     */
    private Parent mount(String view, String theme) {
        ServiceLocator.get(ThemeManager.class).setTheme(theme);
        List<Parent> root = new ArrayList<>(1);
        interact(() -> {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/" + view + ".fxml"));
            try {
                window.getScene().setRoot(loader.load());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            window.getScene().getStylesheets().setAll(ThemeCss.of(theme));
            root.add(window.getScene().getRoot());
        });
        return root.get(0);
    }

    /**
     * Fires what opens a stage and returns the stage once it is on screen: the
     * first showing window that was not showing before.
     */
    private Stage open(Runnable opener) {
        List<Window> before = onFx(() -> List.copyOf(Window.getWindows()));
        Platform.runLater(opener);
        return Await.untilValue("a stage to open", () -> onFx(() -> {
            for (Window shown : Window.getWindows()) {
                if (!before.contains(shown) && shown.isShowing() && shown instanceof Stage stage) {
                    return stage;
                }
            }
            return null;
        }), Objects::nonNull, PATIENCE);
    }

    /** Waits for a failed download's Homebrew fallback and returns its copy link. */
    private Hyperlink awaitTheFallback(Parent root) {
        return Await.untilValue("the Homebrew fallback to be shown",
                () -> onFx(() -> root.lookupAll(".hyperlink").stream()
                        .filter(DialogStageThemeTest::isShown)
                        .map(Hyperlink.class::cast)
                        .findFirst()
                        .orElse(null)),
                Objects::nonNull, PATIENCE);
    }

    /** Owned by the window: JavaFX keeps it above the window and closes it with it. */
    private List<String> ownership(Stage stage) {
        Window owner = onFx(stage::getOwner);
        return owner == window ? List.of()
                : List.of("owned by " + owner + ", not the window it was opened from");
    }

    /**
     * Over the window the way JavaFX sets a Dialog down: the stage's middle on
     * the window's. Monocle draws no title bars, so the windows' middles are
     * their scenes'.
     */
    private List<String> placement(Stage stage) {
        double[] offset = onFx(() -> new double[] {
            stage.getX() + stage.getWidth() / 2 - (window.getX() + window.getWidth() / 2),
            stage.getY() + stage.getHeight() / 2 - (window.getY() + window.getHeight() / 2)});
        return Math.abs(offset[0]) <= 1 && Math.abs(offset[1]) <= 1 ? List.of()
                : List.of(String.format(Locale.ROOT,
                        "its middle is %.0fpx right of and %.0fpx below the window's",
                        offset[0], offset[1]));
    }

    /** The stage's root is painted in the window's theme. */
    private List<String> dress(Stage stage, String theme) {
        Color fill = onFx(() -> {
            stage.getScene().getRoot().applyCss();
            return Contrast.backdrop((Region) stage.getScene().getRoot());
        });
        return Contrast.isDark(fill) == "dark".equals(theme) ? List.of()
                : List.of("painted " + fill + ", which is not the " + theme + " theme");
    }

    /**
     * Reads a stage's content: everything painted in it on the theme's side,
     * and each piece of text at 4.5:1 or better on what is painted behind it.
     * What is off is added to {@code problems}.
     *
     * @return the texts that were read, so a test can show the ones it
     *     expects were among them
     */
    private List<String> readInTheTheme(Parent root, String theme, List<String> problems) {
        boolean dark = "dark".equals(theme);
        List<String> read = new ArrayList<>();
        interact(() -> {
            root.applyCss();
            root.layout();
            for (Region region : paintedIn(root)) {
                Color fill = Contrast.backdrop(region);
                if (Contrast.isDark(fill) != dark) {
                    problems.add(region.getStyleClass() + " is painted " + fill
                            + ", off the theme");
                }
            }
            for (Node node : root.lookupAll(".text")) {
                if (node instanceof Text text && isShown(text)
                        && text.getText() != null && !text.getText().isBlank()) {
                    Color fill = Contrast.flat(text.getFill());
                    Color behind = paintedBehind(text);
                    double ratio = Contrast.ratio(fill, behind);
                    read.add(text.getText());
                    if (ratio < Contrast.READABLE) {
                        problems.add(String.format(Locale.ROOT, "\"%s\" is %s on %s, %.2f:1",
                                text.getText(), fill, behind, ratio));
                    }
                }
            }
        });
        return read;
    }

    /**
     * Every region shown inside {@code root} that paints anything, but for the
     * accent: a primary button and a progress bar's bar are a green that is
     * dark in both themes.
     */
    private static List<Region> paintedIn(Parent root) {
        List<Region> painted = new ArrayList<>();
        for (Node child : root.getChildrenUnmodifiable()) {
            if (child instanceof Region region && isShown(region)
                    && region.getBackground() != null
                    && !region.getBackground().getFills().isEmpty()
                    && Contrast.backdrop(region).getOpacity() > 0
                    && !region.getStyleClass().contains("primary-button")
                    && !region.getStyleClass().contains("bar")) {
                painted.add(region);
            }
            if (child instanceof Parent parent) {
                painted.addAll(paintedIn(parent));
            }
        }
        return painted;
    }

    private static void assertNoProblems(String what, List<String> problems) {
        assertThat(problems)
                .withFailMessage("%s:%n  %s", what, String.join("\n  ", problems))
                .isEmpty();
    }

    /** The nearest paint at or above a node that is not see-through. */
    private static Color paintedBehind(Node node) {
        for (Node at = node; at != null; at = at.getParent()) {
            if (at instanceof Region region && region.getBackground() != null
                    && !region.getBackground().getFills().isEmpty()) {
                Color fill = Contrast.backdrop(region);
                if (fill.getOpacity() > 0) {
                    return fill;
                }
            }
        }
        throw new AssertionError("nothing is painted behind " + node);
    }

    private static boolean isShown(Node node) {
        for (Node at = node; at != null; at = at.getParent()) {
            if (!at.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private Button button(Parent root, String id) {
        return onFx(() -> (Button) root.lookup(id));
    }

    /** Runs {@code work} on the FX thread and hands back what it returned. */
    private <T> T onFx(Supplier<T> work) {
        List<T> result = new ArrayList<>(1);
        interact(() -> result.add(work.get()));
        return result.get(0);
    }

    /** Reports some progress, then holds the download until the test ends. */
    private static final class StalledInstaller extends SingBoxInstaller {

        private final CountDownLatch release;

        StalledInstaller(CountDownLatch release) {
            this.release = release;
        }

        @Override
        public Path install(DoubleConsumer progress) throws IOException, InterruptedException {
            progress.accept(0.42);
            release.await();
            throw new IOException("the test is over");
        }
    }

    /** Fails the download the way an unreachable GitHub does. */
    private static final class FailingInstaller extends SingBoxInstaller {

        @Override
        public Path install(DoubleConsumer progress) throws IOException {
            throw new IOException("Network is unreachable");
        }
    }
}
