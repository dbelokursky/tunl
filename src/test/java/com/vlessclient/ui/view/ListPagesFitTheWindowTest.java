package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.app.ThemeCss;
import com.vlessclient.model.Subscription;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.SubscriptionService;
import com.vlessclient.service.TestConfigStores;
import com.vlessclient.testing.TestServers;
import com.vlessclient.testing.UiTest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ScrollBar;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pages built around a list fit the window the app opens at, with the
 * list's last row on screen.
 *
 * <p>ContentScrollPane pins a page's minimum height to its preferred height,
 * and a ListView prefers 400px whatever it holds. That is not content: the
 * list is virtualized and scrolls on its own. It still made Logs about 40px
 * and Servers about 50px taller than the 820x500 window leaves them, so each
 * showed a second, outer bar beside the list's own, and the newest log line,
 * where auto-scroll puts it, sat below the fold.</p>
 */
@UiTest
public class ListPagesFitTheWindowTest extends ApplicationTest {

    /** The size VlessClientApp opens the main window at, every time. */
    private static final int WIDTH = 820;
    private static final int HEIGHT = 500;

    @TempDir
    static Path tempDir;

    @Override
    public void start(Stage stage) {
        // Servers and Subscriptions show their list only when it has rows.
        ConfigStore store = TestConfigStores.at(tempDir.resolve("data"));
        store.getServers().clear();
        store.addServer(TestServers.server().name("Netherlands 01").build());
        ServiceLocator.register(ConfigStore.class, store);
        Subscription subscription = new Subscription();
        subscription.setName("Provider");
        subscription.setUrl("https://example.com/sub");
        ServiceLocator.get(SubscriptionService.class).getSubscriptions().setAll(subscription);
    }

    @Test
    void theListPagesFitTheDefaultWindow() {
        List<String> wrong = new ArrayList<>();
        check("Logs", MainViewController::showLogs, "#logListView", wrong);
        check("Servers", MainViewController::showServers, "#serverListView", wrong);
        check("Subscriptions", MainViewController::showSubscriptions,
                "#subscriptionListView", wrong);

        assertThat(wrong)
                .withFailMessage("at %dx%d these pages overflow the window:%n  %s",
                        WIDTH, HEIGHT, String.join("\n  ", wrong))
                .isEmpty();
    }

    /** One page in a scene of its own, so nothing laid out for one is measured for the next. */
    private void check(String page, Consumer<MainViewController> show, String listId,
                       List<String> wrong) {
        Scene scene = mainViewShowing(show);
        interact(() -> {
            Node pane = scene.lookup(".content-scroll");
            Node list = scene.lookup(listId);
            if (!(pane instanceof ContentScrollPane scroll) || list == null || !list.isVisible()) {
                wrong.add(page + ": the page or its list is not on screen");
                return;
            }
            for (Node child : scroll.getChildrenUnmodifiable()) {
                if (child instanceof ScrollBar bar && bar.getOrientation() == Orientation.VERTICAL
                        && bar.isVisible() && bar.getBoundsInParent().getHeight() > 0) {
                    wrong.add(page + ": ContentScrollPane shows a vertical bar");
                }
            }
            Bounds window = scroll.getParent().localToScene(scroll.getBoundsInParent());
            Bounds rows = list.getParent().localToScene(list.getBoundsInParent());
            if (rows.getMaxY() > window.getMaxY() + 0.5) {
                wrong.add("%s: the list ends at y=%.1f, below the page's bottom at %.1f"
                        .formatted(page, rows.getMaxY(), window.getMaxY()));
            }
        });
    }

    /**
     * MainView at the default window size with a page mounted the way its nav
     * button mounts it. Never shown, as in SettingsScrollBarTest: a stage
     * would clamp the size to the runner's screen.
     */
    private Scene mainViewShowing(Consumer<MainViewController> show) {
        final Scene[] holder = new Scene[1];
        interact(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainView.fxml"));
                Parent root = loader.load();
                Scene scene = new Scene(new Group(), WIDTH, HEIGHT);
                scene.getStylesheets().setAll(ThemeCss.light());
                scene.setRoot(root);
                show.accept(loader.getController());
                root.resize(WIDTH, HEIGHT);
                root.applyCss();
                root.layout();
                holder[0] = scene;
            } catch (Exception e) {
                throw new IllegalStateException("could not load MainView", e);
            }
        });
        return holder[0];
    }
}
