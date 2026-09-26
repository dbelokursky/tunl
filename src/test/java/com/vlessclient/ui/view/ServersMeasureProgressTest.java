package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.app.ThemeCss;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.LatencyTester;
import com.vlessclient.service.TestConfigStores;
import com.vlessclient.service.TestLatencyTesters;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.TestServers;
import com.vlessclient.testing.UiTest;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * A measurement fills the rows in as its results arrive.
 *
 * <p>The list was redrawn once, after the last server answered: with five
 * hundred servers, about two minutes in which nothing on screen changed,
 * though most results had long been in.</p>
 */
@UiTest
public class ServersMeasureProgressTest extends ApplicationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    static Path tempDir;

    private final CountDownLatch alphaAnswers = new CountDownLatch(1);
    private final CountDownLatch betaAnswers = new CountDownLatch(1);

    @Override
    public void start(Stage stage) throws IOException {
        ConfigStore store = TestConfigStores.at(tempDir.resolve(UUID.randomUUID().toString()));
        ServerConfig alpha = server("Alpha");
        ServerConfig beta = server("Beta");
        store.addServer(alpha);
        store.addServer(beta);
        ServiceLocator.register(ConfigStore.class, store);
        ServiceLocator.register(LatencyTester.class, TestLatencyTesters.gated(
                Map.of(alpha, alphaAnswers, beta, betaAnswers), 42));
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/ServersView.fxml"));
        Scene scene = new Scene(loader.load(), 900, 640);
        scene.getStylesheets().addAll(ThemeCss.light());
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void openTheGates() {
        alphaAnswers.countDown();
        betaAnswers.countDown();
    }

    @Test
    void aRowShowsItsResultWhileTheOthersAreStillMeasured() {
        interact(() -> lookup("#measureButton").queryAs(Button.class).fire());

        alphaAnswers.countDown();

        Await.until("Alpha's result on its row", () -> chipOf("Alpha") != null, TIMEOUT);
        assertThat(chipOf("Alpha")).contains("42");
        assertThat(chipOf("Beta")).as("Beta, still being measured").isNull();

        betaAnswers.countDown();
        Await.until("Beta's result on its row", () -> chipOf("Beta") != null, TIMEOUT);
    }

    /** The latency chip on the row of the server named {@code name}, or null while none shows. */
    private String chipOf(String name) {
        return onFx(() -> {
            for (Node node : lookup(".list-cell").queryAll()) {
                if (node instanceof ListCell<?> cell && cell.getItem() instanceof ServerConfig server
                        && name.equals(server.getName())) {
                    Node chip = ((Parent) cell.getGraphic()).lookup(".latency-chip");
                    return chip instanceof Label label && label.isVisible()
                            && label.getParent() != null ? label.getText() : null;
                }
            }
            return null;
        });
    }

    private <T> T onFx(java.util.concurrent.Callable<T> read) {
        try {
            return org.testfx.util.WaitForAsyncUtils.asyncFx(read).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ServerConfig server(String name) {
        return TestServers.server()
                .id(UUID.randomUUID().toString())
                .name(name)
                .protocol(Protocol.VLESS)
                .address(name.toLowerCase() + ".example")
                .port(443)
                .uuid("11111111-2222-3333-4444-555555555555")
                .build();
    }
}
