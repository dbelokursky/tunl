package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.app.ThemeCss;
import com.vlessclient.platform.Platform;
import com.vlessclient.service.SingBoxInstaller;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.UiTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;
import javafx.fxml.FXMLLoader;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every scene the app builds wears the OS's style class, so base.css's Mac
 * faces reach them all.
 *
 * <p>base.css sets the install command in monospace, which JavaFX draws in
 * Courier New on a Mac, and names Menlo for it under {@code .os-mac}.
 * MainViewController put the class on the main window's root and nothing
 * else did: the sing-box installer, a window of its own, drew the command the
 * Dashboard shows in Menlo in Courier New. Menlo is absent here, so the face
 * the Mac rule resolves to is read from a label under a root that wears the
 * class, and compared.</p>
 */
@UiTest
public class OsStyleClassTest extends ApplicationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    private Platform before;
    private List<Window> windowsBefore;
    private Stage window;

    @Override
    public void start(Stage primary) {
        before = OsStyleClass.dressAs(Platform.current());
        windowsBefore = List.copyOf(Window.getWindows());
        window = new Stage();
        window.setScene(new Scene(new StackPane(), 900, 600));
        window.show();
    }

    @AfterEach
    void putTheOsBack() {
        OsStyleClass.dressAs(before);
        interact(() -> {
            for (Window shown : List.copyOf(Window.getWindows())) {
                if (!windowsBefore.contains(shown)) {
                    shown.hide();
                }
            }
        });
    }

    @Test
    void onAMacTheInstallerDrawsTheInstallCommandInTheMacFace() {
        OsStyleClass.dressAs(Platform.MAC);
        ServiceLocator.register(SingBoxInstaller.class, new FailingInstaller());
        Parent dashboard = mount("DashboardView");

        Stage installer = open(onFx(() -> (Button) dashboard.lookup("#retryInstallButton"))::fire);
        Label command = Await.untilValue("the Homebrew fallback to be shown", () -> onFx(() ->
                        installer.getScene().getRoot().lookupAll(".banner-command").stream()
                                .filter(OsStyleClassTest::isShown)
                                .map(Label.class::cast)
                                .findFirst().orElse(null)),
                Objects::nonNull, PATIENCE);

        String drawn = onFx(() -> command.getFont().getFamily());
        assertThat(macFace()).as("the Mac's face, which plain monospace is not")
                .isNotEqualTo(faceUnder(false));
        assertThat(onFx(() -> List.copyOf(installer.getScene().getRoot().getStyleClass())))
                .as("the installer's root").contains(OsStyleClass.MAC);
        assertThat(drawn).as("the install command's face").isEqualTo(macFace());
    }

    @Test
    void onAMacEveryKindOfSceneWearsTheClass() {
        OsStyleClass.dressAs(Platform.MAC);

        assertThat(classesOfEveryKindOfScene()).allSatisfy((kind, classes) ->
                assertThat(classes).as(kind).contains(OsStyleClass.MAC));
    }

    @Test
    void offAMacNoSceneWearsIt() {
        for (Platform other : List.of(Platform.WINDOWS, Platform.LINUX)) {
            OsStyleClass.dressAs(other);

            assertThat(classesOfEveryKindOfScene()).allSatisfy((kind, classes) ->
                    assertThat(classes).as(other + ": " + kind)
                            .doesNotContain(OsStyleClass.MAC));
        }
    }

    /**
     * The root's classes of each kind of scene: the main window, a stage of
     * the app's own, an alert and a dialog built by hand.
     */
    private java.util.Map<String, List<String>> classesOfEveryKindOfScene() {
        return onFx(() -> {
            java.util.Map<String, List<String>> classes = new java.util.LinkedHashMap<>();
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainView.fxml"));
            try {
                classes.put("the main window", List.copyOf(
                        ((Parent) loader.load()).getStyleClass()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            DialogStage stage = new DialogStage(null);
            stage.setThemedScene(new Scene(new StackPane()));
            classes.put("a stage of its own", List.copyOf(stage.getScene().getRoot()
                    .getStyleClass()));
            // A dialog's scene is rooted in a pane of JavaFX's around the
            // DialogPane; a class on it reaches everything the dialog shows.
            Alert alert = Dialogs.alert(Alert.AlertType.INFORMATION);
            classes.put("an alert", List.copyOf(
                    alert.getDialogPane().getScene().getRoot().getStyleClass()));
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK);
            Dialogs.localizeButtons(dialog.getDialogPane());
            classes.put("a dialog", List.copyOf(
                    dialog.getDialogPane().getScene().getRoot().getStyleClass()));
            return classes;
        });
    }

    /** The face base.css gives the install command under the Mac's class, here. */
    private String macFace() {
        return faceUnder(true);
    }

    /** The face base.css gives the install command under a root with or without it. */
    private String faceUnder(boolean mac) {
        return onFx(() -> {
            Label command = new Label(SingBoxInstaller.brewInstallCommand());
            command.getStyleClass().add("banner-command");
            VBox banner = new VBox(command);
            banner.getStyleClass().add("singbox-missing-banner");
            VBox root = new VBox(banner);
            root.getStyleClass().add("root-pane");
            if (mac) {
                root.getStyleClass().add(OsStyleClass.MAC);
            }
            Scene scene = new Scene(new Group());
            scene.getStylesheets().setAll(ThemeCss.light());
            scene.setRoot(root);
            root.applyCss();
            return command.getFont().getFamily();
        });
    }

    /** Puts a view in the window, dressed as the main window is. */
    private Parent mount(String view) {
        return onFx(() -> {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/" + view + ".fxml"));
            try {
                window.getScene().setRoot(loader.load());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            window.getScene().getStylesheets().setAll(ThemeCss.light());
            return window.getScene().getRoot();
        });
    }

    /**
     * Fires what opens a stage, which waits for its answer, and returns the
     * stage once it is on screen.
     */
    private Stage open(Runnable opener) {
        List<Window> shownBefore = onFx(() -> List.copyOf(Window.getWindows()));
        javafx.application.Platform.runLater(opener);
        return Await.untilValue("a stage to open", () -> onFx(() -> {
            for (Window shown : Window.getWindows()) {
                if (!shownBefore.contains(shown) && shown.isShowing()
                        && shown instanceof Stage stage) {
                    return stage;
                }
            }
            return null;
        }), Objects::nonNull, PATIENCE);
    }

    private static boolean isShown(Node node) {
        for (Node at = node; at != null; at = at.getParent()) {
            if (!at.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private <T> T onFx(Supplier<T> work) {
        List<T> result = new ArrayList<>(1);
        interact(() -> result.add(work.get()));
        return result.get(0);
    }

    /** Fails the download the way an unreachable GitHub does. */
    private static final class FailingInstaller extends SingBoxInstaller {

        @Override
        public Path install(DoubleConsumer progress) throws IOException {
            throw new IOException("Network is unreachable");
        }
    }
}
