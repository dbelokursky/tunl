package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.platform.InMemorySecretSealer;
import com.vlessclient.platform.SecretSealer;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.TestServers;
import com.vlessclient.testing.UiTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * "Store credentials in the system keychain" changes servers.json when it is
 * ticked, not at some later save.
 *
 * <p>It saved only the setting. The credentials are sealed when servers.json
 * is written, and nothing wrote it, so they stayed in plaintext until a
 * server happened to be edited: with servers added by hand only, for good.
 * Unticked before a downgrade, as the release notes say to, it left the
 * sealed tags an older build reads as the credential itself.</p>
 */
@UiTest
public class SettingsSecretStoreRewriteTest extends ApplicationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(10);
    private static final String CREDENTIAL = "11111111-2222-3333-4444-555555555555";

    @TempDir
    static Path tempDir;

    private Path servers;

    @Override
    public void start(Stage stage) throws Exception {
        Path dataDir = tempDir.resolve(UUID.randomUUID().toString());
        InMemorySecretSealer sealer = new InMemorySecretSealer();
        ConfigStore store = new ConfigStore(dataDir, sealer);
        AppSettings settings = store.getSettings();
        settings.setStoreSecretsSecurely(false);
        store.saveSettings(settings);
        store.addServer(TestServers.vless("Tokyo").uuid(CREDENTIAL).build());
        servers = dataDir.resolve(ConfigStore.SERVERS_FILE);
        ServiceLocator.register(ConfigStore.class, store);
        ServiceLocator.register(SecretSealer.class, sealer);

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/SettingsView.fxml"));
        stage.setScene(new Scene(loader.load(), 700, 720));
        stage.show();
    }

    @Test
    void theBoxSealsAndUnsealsTheServersAtOnce() {
        Await.until("servers.json in plaintext", () -> servers().contains(CREDENTIAL), PATIENCE);
        CheckBox box = lookup("#storeSecretsCheck").queryAs(CheckBox.class);

        interact(() -> box.setSelected(true));
        Await.until("servers.json with the credential sealed",
                () -> !servers().contains(CREDENTIAL)
                        && servers().contains(InMemorySecretSealer.FAKE_TAG), PATIENCE);

        interact(() -> box.setSelected(false));
        Await.until("servers.json in plaintext again", () -> servers().contains(CREDENTIAL),
                PATIENCE);
    }

    private String servers() {
        try {
            return Files.isRegularFile(servers)
                    ? Files.readString(servers, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
