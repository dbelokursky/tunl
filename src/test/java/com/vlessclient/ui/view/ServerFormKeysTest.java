package com.vlessclient.ui.view;

import com.vlessclient.app.ThemeCss;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.testing.UiTest;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javafx.event.Event;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The keyboard in the server form. It was the only modal window without a
 * default or a cancel button, so Escape did not close it and Enter did not
 * save, as they do in every other dialog of the app.
 */
@UiTest
@SuppressWarnings("unchecked")
public class ServerFormKeysTest extends ApplicationTest {

    /** Keys as wg genkey writes them: 32 bytes in padded standard base64. */
    private static final String PRIVATE_KEY = "tSvcDcFFKiY/1dfLHXsTKZPYGLBRXpMAyxr5uNITbEk=";
    private static final String PEER_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=";

    private final AtomicReference<ServerConfig> saved = new AtomicReference<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/ServerFormView.fxml"));
        Parent root = loader.load();
        ServerFormController controller = loader.getController();
        controller.setOnSave(saved::set);
        controller.setOnCancel(() -> cancelled.set(true));
        Scene scene = new Scene(root, 520, 650);
        // The app's stylesheet: the default button's look below is only
        // meaningful with the styles the form really wears.
        scene.getStylesheets().setAll(ThemeCss.light());
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void escapeInAFieldCancelsTheForm() {
        TextField name = lookup("#nameField").query();

        interact(() -> Event.fireEvent(name, pressed(KeyCode.ESCAPE)));

        assertThat(cancelled).as("Escape cancels").isTrue();
        assertThat(saved.get()).isNull();
    }

    @Test
    void enterInAFieldSavesAFilledForm() {
        TextField address = lookup("#addressField").query();
        interact(() -> {
            address.setText("198.51.100.7");
            ((TextField) lookup("#portField").query()).setText("443");
            ((TextField) lookup("#uuidField").query()).setText("11111111-2222-3333-4444-555555555555");
        });

        interact(() -> Event.fireEvent(address, pressed(KeyCode.ENTER)));

        assertThat(saved.get()).as("Enter saves").isNotNull();
        assertThat(saved.get().getAddress()).isEqualTo("198.51.100.7");
        assertThat(cancelled).isFalse();
    }

    /**
     * WireGuard's peer key and local address are typed into editable combos,
     * which take what was typed as their value only on a commit. Enter fired
     * Save, the default button, on the key's press, and the combo committed on
     * its release, after the form had been read: the server was saved without
     * the peer key and the address the user had just typed.
     */
    @Test
    void enterInWireGuardsTypedFieldsSavesWhatWasTyped() {
        ComboBox<String> peerKey = lookup("#encryptionCombo").query();
        ComboBox<String> localAddress = lookup("#flowCombo").query();
        interact(() -> {
            ((ComboBox<Protocol>) lookup("#protocolCombo").query()).setValue(Protocol.WIREGUARD);
            ((TextField) lookup("#addressField").query()).setText("198.51.100.7");
            ((TextField) lookup("#portField").query()).setText("51820");
            ((TextField) lookup("#uuidField").query()).setText(PRIVATE_KEY);
            peerKey.getEditor().setText(PEER_KEY);
            localAddress.requestFocus();
            localAddress.getEditor().setText("10.0.0.2/32");
        });

        interact(() -> {
            Event.fireEvent(localAddress, pressed(KeyCode.ENTER));
            Event.fireEvent(localAddress, released(KeyCode.ENTER));
        });

        assertThat(saved.get()).as("Enter saves").isNotNull();
        assertThat(saved.get().getEncryption()).as("the peer key").isEqualTo(PEER_KEY);
        assertThat(saved.get().getFlow()).as("the local address").isEqualTo("10.0.0.2/32");
    }

    /** Hysteria2's obfuscation password is typed into the same kind of combo. */
    @Test
    void enterInHysteria2sObfuscationPasswordSavesWhatWasTyped() {
        ComboBox<String> obfuscation = lookup("#flowCombo").query();
        interact(() -> {
            ((ComboBox<Protocol>) lookup("#protocolCombo").query()).setValue(Protocol.HYSTERIA2);
            ((TextField) lookup("#addressField").query()).setText("198.51.100.7");
            ((TextField) lookup("#portField").query()).setText("443");
            ((TextField) lookup("#uuidField").query()).setText("secret");
            obfuscation.requestFocus();
            obfuscation.getEditor().setText("obfs-secret");
        });

        interact(() -> {
            Event.fireEvent(obfuscation, pressed(KeyCode.ENTER));
            Event.fireEvent(obfuscation, released(KeyCode.ENTER));
        });

        assertThat(saved.get()).as("Enter saves").isNotNull();
        assertThat(saved.get().getFlow()).as("the obfuscation password").isEqualTo("obfs-secret");
    }

    /**
     * Modena restyles a default button. Save wears .primary-button, whose own
     * colours have to survive being the default.
     */
    @Test
    void saveKeepsThePrimaryLookAsTheDefaultButton() {
        Button save = lookup("#saveButton").query();
        Button primary = new Button(save.getText());
        primary.getStyleClass().setAll(save.getStyleClass());
        interact(() -> {
            ((Pane) save.getParent()).getChildren().add(primary);
            save.getScene().getRoot().applyCss();
        });

        assertThat(save.getTextFill()).isEqualTo(primary.getTextFill());
        assertThat(save.getBackground().getFills().stream().map(fill -> fill.getFill()).toList())
                .isEqualTo(primary.getBackground().getFills().stream()
                        .map(fill -> fill.getFill()).toList());
    }

    private static KeyEvent pressed(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
    }

    private static KeyEvent released(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, false, false, false, false);
    }
}
