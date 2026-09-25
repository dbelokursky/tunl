package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.platform.SecretSealer;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.UiTest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * "Store credentials in the system keychain" showed the stored choice and
 * nothing else. When the keychain did not work, on a Linux desktop without
 * {@code secret-tool} say, the credentials went into the JSON files with the
 * box still ticked. The page now says when the box is not in effect.
 */
@UiTest
public class SettingsSecretStoreHintTest extends ApplicationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    @TempDir
    static Path tempDir;

    /** A backend whose availability the test sets, and which notes who asked. */
    private static final class SwitchableSealer implements SecretSealer {
        private volatile boolean works;
        private final AtomicBoolean askedOnFxThread = new AtomicBoolean();

        @Override
        public boolean isAvailable() {
            if (Platform.isFxApplicationThread()) {
                askedOnFxThread.set(true);
            }
            return works;
        }

        @Override
        public String seal(String key, String plaintext) {
            return null;
        }

        @Override
        public Optional<String> unseal(String key, String stored) {
            return Optional.empty();
        }

        @Override
        public void delete(String key) {
        }
    }

    private final SwitchableSealer sealer = new SwitchableSealer();
    private ViewShownAware controller;

    @Override
    public void start(Stage stage) throws Exception {
        ServiceLocator.register(ConfigStore.class, new ConfigStore(
                tempDir.resolve(UUID.randomUUID().toString()), sealer));
        ServiceLocator.register(SecretSealer.class, sealer);
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/SettingsView.fxml"));
        Scene scene = new Scene(loader.load(), 700, 720);
        controller = loader.getController();
        stage.setScene(scene);
        stage.show();
    }

    private Label hint() {
        return lookup("#storeSecretsHint").queryAs(Label.class);
    }

    @Test
    void thePageSaysWhenTheKeychainIsNotInUse() {
        sealer.works = false;
        interact(controller::onViewShown);
        Await.until("the hint to show", () -> hint().isVisible(), PATIENCE);

        String expected = com.vlessclient.platform.Platform.current()
                == com.vlessclient.platform.Platform.LINUX
                ? I18n.get("settings.store.secrets.unavailable.linux")
                : I18n.get("settings.store.secrets.unavailable");
        assertThat(hint().getText()).isEqualTo(expected);
        assertThat(sealer.askedOnFxThread)
                .as("whether the backend was asked on the FX thread: it probes with processes")
                .isFalse();

        sealer.works = true;
        interact(controller::onViewShown);
        Await.until("the hint to go once the keychain works", () -> !hint().isVisible(),
                PATIENCE);
    }

    @Test
    void theHintGoesWithTheBox() {
        sealer.works = false;
        interact(controller::onViewShown);
        Await.until("the hint to show", () -> hint().isVisible(), PATIENCE);

        CheckBox box = lookup("#storeSecretsCheck").queryAs(CheckBox.class);
        interact(() -> box.setSelected(false));

        assertThat(hint().isVisible())
                .as("with the box off nothing is meant to be in the keychain")
                .isFalse();
        assertThat(hint().isManaged()).isFalse();
    }
}
