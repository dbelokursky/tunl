package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ThemeCss;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TlsConfig;
import com.vlessclient.testing.Await;
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
import javafx.scene.control.DialogPane;
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
public class ServerFormRefusalTest extends ApplicationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String LONG_SHORT_ID = "0123456789abcdef01";
    private static final String REALITY_KEY = "WZaG00XCAiVCF2SP5fmSbKiuTbBB-lMDg_81rC8hR80";

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

    private static ServerConfig realityServer(String shortId) {
        ServerConfig server = new ServerConfig();
        server.setId("srv-1");
        server.setName("Tokyo");
        server.setProtocol(Protocol.VLESS);
        server.setAddress("198.51.100.7");
        server.setPort(443);
        server.setUuid("11111111-2222-3333-4444-555555555555");
        TlsConfig tls = new TlsConfig();
        tls.setEnabled(true);
        tls.setServerName("example.com");
        tls.setReality(true);
        tls.setRealityPublicKey(REALITY_KEY);
        tls.setRealityShortId(shortId);
        server.setTls(tls);
        return server;
    }

    private void text(String query, String value) {
        ((TextField) lookup(query).query()).setText(value);
    }

    /**
     * Presses Save and returns what the warning it ends with says. Save is
     * fired through the queue: the warning is shown with showAndWait, which
     * would keep {@code interact} waiting for the very dialog this closes.
     */
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
