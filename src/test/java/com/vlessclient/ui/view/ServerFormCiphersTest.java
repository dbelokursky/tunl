package com.vlessclient.ui.view;

import com.vlessclient.app.ThemeCss;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.testing.TestServers;
import com.vlessclient.testing.UiTest;
import java.util.concurrent.atomic.AtomicReference;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The form's cipher box offers each protocol its own ciphers.
 *
 * <p>VLESS has none of its own, yet the box offered it VMess's, which VLESS
 * ignores. VMess got the same short list, started at {@code none}, and the
 * core was asked for {@code auto} whatever the box said.</p>
 */
@UiTest
public class ServerFormCiphersTest extends ApplicationTest {

    private final AtomicReference<ServerConfig> saved = new AtomicReference<>();
    private ServerFormController controller;

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/ServerFormView.fxml"));
        Parent root = loader.load();
        controller = loader.getController();
        controller.setOnSave(saved::set);
        Scene scene = new Scene(root, 520, 650);
        scene.getStylesheets().setAll(ThemeCss.light());
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void vlessOffersNoCipher() {
        interact(() -> cipher().getScene().getRoot().applyCss());

        assertThat(encryptionBox().isVisible()).isFalse();
        assertThat(encryptionBox().isManaged()).isFalse();
    }

    @Test
    void aNewVmessServerOffersVmessCiphersStartingAtAuto() {
        interact(() -> protocol().setValue(Protocol.VMESS));

        assertThat(encryptionBox().isVisible()).isTrue();
        assertThat(cipher().getValue()).isEqualTo("auto");
        assertThat(cipher().getItems())
                .contains("auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero");
    }

    @Test
    void aVmessServersCipherIsShownAndSaved() {
        ServerConfig server = TestServers.vless("Tokyo").protocol(Protocol.VMESS)
                .encryption("chacha20-poly1305").build();

        interact(() -> controller.setServerConfig(server));

        assertThat(cipher().getValue()).isEqualTo("chacha20-poly1305");
        interact(() -> ((Button) lookup("#saveButton").query()).fire());
        assertThat(saved.get().getEncryption()).isEqualTo("chacha20-poly1305");
    }

    @SuppressWarnings("unchecked")
    private ComboBox<String> cipher() {
        return (ComboBox<String>) lookup("#encryptionCombo").query();
    }

    @SuppressWarnings("unchecked")
    private ComboBox<Protocol> protocol() {
        return (ComboBox<Protocol>) lookup("#protocolCombo").query();
    }

    private VBox encryptionBox() {
        return lookup("#encryptionBox").queryAs(VBox.class);
    }
}
