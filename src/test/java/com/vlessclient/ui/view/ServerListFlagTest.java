package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.CountryResolver;
import com.vlessclient.service.TestConfigStores;
import com.vlessclient.testing.TestServers;
import com.vlessclient.testing.UiTest;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A row whose country is known draws its flag once per fill.
 *
 * <p>It drew the known flag, then asked for the country again; the answer,
 * known already, came back at once, and a task queued with it drew a second
 * flag over the first on the next pulse. Every refresh of the list, which
 * each batch of measurements makes, built two flags a row and laid each row
 * out twice.</p>
 */
@UiTest
public class ServerListFlagTest extends ApplicationTest {

    @TempDir
    static Path tempDir;

    /** Knows every server's country, as the real resolver does once it has looked. */
    private static final class KnownCountries extends CountryResolver {

        KnownCountries() {
            super(null);
        }

        @Override
        public Optional<String> countryOf(ServerConfig server) {
            return Optional.of("NL");
        }

        @Override
        public void resolveAsync(ServerConfig server, Consumer<String> onResolved) {
            // A cached answer is handed over at once, on the caller's thread.
            onResolved.accept("NL");
        }
    }

    @Override
    public void start(Stage stage) throws Exception {
        ConfigStore store = TestConfigStores.at(tempDir.resolve("data"));
        ServiceLocator.register(ConfigStore.class, store);
        ServiceLocator.register(CountryResolver.class, new KnownCountries());
        store.getServers().clear();
        store.addServer(TestServers.server().name("Amsterdam 01").protocol(Protocol.VLESS)
                .address("185.107.56.12").port(443)
                .uuid("11111111-2222-3333-4444-555555555555").build());

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/ServersView.fxml"));
        stage.setScene(new Scene(loader.load(), 980, 560));
        stage.show();
    }

    /**
     * The cell is filled again the way the list fills a reused one, on a
     * scroll or a refresh, so the same slot can be watched across the fill.
     */
    @Test
    void aKnownFlagIsDrawnOncePerFill() {
        ListCell<?> row = serverRow();
        Pane slot = (Pane) ((HBox) row.getGraphic()).getChildren().getFirst();
        assertThat(slot.getChildren()).as("the flag").hasSize(1);
        AtomicInteger drawn = new AtomicInteger();
        interact(() -> slot.getChildren().addListener((ListChangeListener<Node>) change -> {
            while (change.next()) {
                if (change.wasAdded()) {
                    drawn.incrementAndGet();
                }
            }
        }));

        interact(() -> {
            int index = row.getIndex();
            row.updateIndex(-1);
            row.updateIndex(index);
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(drawn.get()).as("flags drawn into the row by one fill").isEqualTo(1);
    }

    private ListCell<?> serverRow() {
        return lookup(".list-cell").queryAll().stream()
                .map(node -> (ListCell<?>) node)
                .filter(cell -> cell.getItem() instanceof ServerConfig)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no server row"));
    }
}
