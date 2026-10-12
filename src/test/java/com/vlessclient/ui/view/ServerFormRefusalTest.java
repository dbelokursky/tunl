package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ThemeCss;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.TestServers;
import com.vlessclient.testing.UiTest;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server form holds a server to what the core accepts, as an import does.
 *
 * <p>It checked the address, the port and the credential only, so a REALITY
 * short ID of more than 16 hex digits, easy to paste in by mistake, was
 * stored. sing-box panics on it, and every connect failed, whichever server
 * was picked.</p>
 */
@UiTest
@SuppressWarnings("unchecked")
public class ServerFormRefusalTest extends ApplicationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String LONG_SHORT_ID = "0123456789abcdef01";
    private static final String REALITY_KEY = "WZaG00XCAiVCF2SP5fmSbKiuTbBB-lMDg_81rC8hR80";
    private static final String WG_PRIVATE_KEY = "tSvcDcFFKiY/1dfLHXsTKZPYGLBRXpMAyxr5uNITbEk=";
    private static final String WG_PEER_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=";

    private final AtomicReference<ServerConfig> saved = new AtomicReference<>();
    private ServerFormController controller;
    private Stage stage;

    @Override
    public void start(Stage stage) throws Exception {
        this.stage = stage;
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/ServerFormView.fxml"));
        Parent root = loader.load();
        controller = loader.getController();
        controller.setOnSave(saved::set);
        Scene scene = new Scene(root, 520, 650);
        scene.getStylesheets().setAll(ThemeCss.light());
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void closeDialogs() {
        interact(() -> {
            for (Window window : List.copyOf(Window.getWindows())) {
                if (window != stage) {
                    window.hide();
                }
            }
        });
    }

    @Test
    void aNewServerTheCoreWouldRefuseIsNotSaved() {
        interact(() -> {
            text("#addressField", "198.51.100.7");
            text("#portField", "443");
            text("#uuidField", "11111111-2222-3333-4444-555555555555");
            text("#sniField", "example.com");
            text("#realityPublicKeyField", REALITY_KEY);
            text("#realityShortIdField", LONG_SHORT_ID);
        });
        interact(() -> {
            ((CheckBox) lookup("#tlsEnabledCheck").query()).setSelected(true);
            ((CheckBox) lookup("#realityCheck").query()).setSelected(true);
        });

        String message = saveAndCloseTheWarning();

        assertThat(message).contains(I18n.get("refusal.reality.short.id"));
        assertThat(saved.get()).as("the server handed to the list").isNull();
    }

    /**
     * The form wrote each field into the server being edited before anything
     * decided the edit was valid, and that server is the one in the list.
     */
    @Test
    void aRefusedEditLeavesTheServerAsItWas() {
        ServerConfig existing = realityServer("0123abcd");
        interact(() -> controller.setServerConfig(existing));
        interact(() -> text("#realityShortIdField", LONG_SHORT_ID));

        saveAndCloseTheWarning();

        assertThat(saved.get()).isNull();
        assertThat(existing.getTls().getRealityShortId()).isEqualTo("0123abcd");
    }

    /**
     * A WireGuard server needs its peer's key and a local address as much as
     * its private key, and the form asked for the private key only. Saved
     * without the peer key, the server passed the core's check and stopped
     * the core at start, which then printed the private key into the log.
     */
    @Test
    void aWireGuardServerWithoutItsPeerKeyOrAddressIsNotSaved() {
        ComboBox<String> peerKey = lookup("#encryptionCombo").query();
        ComboBox<String> localAddress = lookup("#flowCombo").query();
        interact(() -> {
            ((ComboBox<Protocol>) lookup("#protocolCombo").query()).setValue(Protocol.WIREGUARD);
            text("#addressField", "198.51.100.7");
            text("#portField", "51820");
            text("#uuidField", WG_PRIVATE_KEY);
        });

        String message = saveAndCloseTheWarning();

        assertThat(message)
                .contains(I18n.get("error.field.required", I18n.get("form.peer.public.key")))
                .contains(I18n.get("error.field.required", I18n.get("form.local.address")));
        assertThat(saved.get()).as("the server handed to the list").isNull();
        assertThat(peerKey.getEditor().getStyleClass()).contains("field-error");
        assertThat(localAddress.getEditor().getStyleClass()).contains("field-error");
        assertThat(((Label) lookup("#encryptionLabel").query()).getText())
                .as("the peer key's label marks it required").endsWith(" *");
        assertThat(((Label) lookup("#flowLabel").query()).getText())
                .as("the local address's label marks it required").endsWith(" *");
    }

    @Test
    void aWireGuardKeyTheCoreCannotDecodeIsNotSaved() {
        interact(() -> {
            ((ComboBox<Protocol>) lookup("#protocolCombo").query()).setValue(Protocol.WIREGUARD);
            text("#addressField", "198.51.100.7");
            text("#portField", "51820");
            text("#uuidField", "not-a-key");
            ((ComboBox<String>) lookup("#encryptionCombo").query()).getEditor()
                    .setText(WG_PEER_KEY);
            ((ComboBox<String>) lookup("#flowCombo").query()).getEditor().setText("10.0.0.2/32");
        });

        String message = saveAndCloseTheWarning();

        assertThat(message).contains(I18n.get("refusal.wireguard.private.key"));
        assertThat(saved.get()).isNull();
    }

    private static ServerConfig realityServer(String shortId) {
        return TestServers.server()
                .id("srv-1")
                .name("Tokyo")
                .protocol(Protocol.VLESS)
                .address("198.51.100.7")
                .port(443)
                .uuid("11111111-2222-3333-4444-555555555555")
                .reality("example.com", REALITY_KEY, shortId)
                .build();
    }

    private void text(String query, String value) {
        ((TextField) lookup(query).query()).setText(value);
    }

    /**
     * Presses Save and returns what the warning it ends with says. Save is
     * fired through the queue: the warning is shown with showAndWait, which
     * would keep {@code interact} waiting for the very dialog this closes.
     */
    /**
     * Hysteria2 keeps its obfuscation type where VMess keeps its cipher, in a
     * field its form hides. A new server picked as VMess first and then as
     * Hysteria2 kept VMess's auto as that type, and Save was refused with "The
     * VPN core does not support the auto obfuscation", which nothing on the
     * form could change.
     */
    @Test
    void aHysteria2ServerPickedAfterVmessIsSavedWithItsOwnObfuscation() {
        ComboBox<Protocol> protocol = lookup("#protocolCombo").query();
        interact(() -> {
            protocol.setValue(Protocol.VMESS);
            protocol.setValue(Protocol.HYSTERIA2);
            text("#addressField", "198.51.100.7");
            text("#portField", "443");
            text("#uuidField", "secret");
            ((ComboBox<String>) lookup("#flowCombo").query()).getEditor().setText("obfs-secret");
        });

        Button save = lookup("#saveButton").query();
        Platform.runLater(save::fire);
        Await.until("the server to be saved", () -> saved.get() != null, TIMEOUT);

        assertThat(saved.get().getFlow()).isEqualTo("obfs-secret");
        assertThat(saved.get().getEncryption()).as("the obfuscation type").isNotEqualTo("auto");
    }

    private String saveAndCloseTheWarning() {
        Button save = lookup("#saveButton").query();
        Platform.runLater(save::fire);
        String header = I18n.get("form.invalid.header");
        DialogPane warning = Await.untilValue("the form's warning", () -> {
            AtomicReference<DialogPane> found = new AtomicReference<>();
            interact(() -> {
                for (Window window : Window.getWindows()) {
                    if (window != stage && window.isShowing() && window.getScene() != null
                            && window.getScene().getRoot().lookup(".dialog-pane")
                                    instanceof DialogPane pane
                            && header.equals(pane.getHeaderText())) {
                        found.set(pane);
                    }
                }
            });
            return found.get();
        }, Objects::nonNull, TIMEOUT);
        String content = warning.getContentText();
        interact(() -> ((Button) warning.lookupButton(ButtonType.OK)).fire());
        WaitForAsyncUtils.waitForFxEvents();
        return content;
    }
}
