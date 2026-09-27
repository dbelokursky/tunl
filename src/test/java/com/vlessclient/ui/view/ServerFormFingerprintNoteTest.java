package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ThemeCss;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.testing.TestServers;
import com.vlessclient.testing.UiTest;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * The server form says what the core is sent when it is not what the
 * fingerprint field holds.
 *
 * <p>A REALITY server whose link asks for {@code random} is sent
 * {@code randomized}. The field keeps the link's value, since that is what
 * the server stores and what a subscription refresh puts back, so without a
 * word here the form would show {@code random} for a server that is sent
 * {@code randomized}.</p>
 */
@UiTest
public class ServerFormFingerprintNoteTest extends ApplicationTest {

    private static final String REALITY_KEY = "WZaG00XCAiVCF2SP5fmSbKiuTbBB-lMDg_81rC8hR80";

    private ServerFormController controller;

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/ServerFormView.fxml"));
        Parent root = loader.load();
        controller = loader.getController();
        Scene scene = new Scene(root, 520, 650);
        scene.getStylesheets().setAll(ThemeCss.light());
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void aRealityServerAskingForRandomSaysRandomizedIsSent() {
        interact(() -> controller.setServerConfig(reality("random")));

        Label note = note();
        assertThat(note.isVisible()).isTrue();
        assertThat(note.isManaged()).isTrue();
        assertThat(note.getText()).isEqualTo(I18n.get("form.fingerprint.note"));
        assertThat(lookup("#fingerprintField").queryAs(TextField.class).getText())
                .as("the field keeps the link's value")
                .isEqualTo("random");
    }

    @Test
    void theNoteFollowsWhatTheFormWouldSave() {
        interact(() -> controller.setServerConfig(reality("random")));
        assertThat(note().isVisible()).isTrue();

        interact(() -> lookup("#fingerprintField").queryAs(TextField.class).setText("chrome"));
        assertThat(note().isVisible()).as("chrome typed in").isFalse();

        interact(() -> lookup("#fingerprintField").queryAs(TextField.class).setText("randomized"));
        assertThat(note().isVisible()).as("randomized, sent as it is").isFalse();

        interact(() -> lookup("#fingerprintField").queryAs(TextField.class).setText("RANDOM"));
        assertThat(note().isVisible()).as("random typed back").isTrue();

        interact(() -> lookup("#realityCheck").queryAs(CheckBox.class).setSelected(false));
        assertThat(note().isVisible()).as("REALITY unticked").isFalse();
        assertThat(note().isManaged()).isFalse();

        interact(() -> lookup("#realityCheck").queryAs(CheckBox.class).setSelected(true));
        assertThat(note().isVisible()).as("REALITY ticked again").isTrue();
        interact(() -> protocolCombo().setValue(Protocol.VMESS));
        assertThat(note().isVisible()).as("VMess, which the form offers no REALITY for")
                .isFalse();
    }

    @Test
    void plainTlsAskingForRandomSaysNothing() {
        interact(() -> controller.setServerConfig(TestServers.vless("Osaka")
                .tls("osaka.example")
                .fingerprint("random")
                .build()));

        assertThat(note().isVisible()).isFalse();
        assertThat(note().isManaged()).isFalse();
    }

    @Test
    void aNewServerSaysNothing() {
        assertThat(note().isVisible()).isFalse();
        assertThat(note().isManaged()).isFalse();
    }

    private Label note() {
        return lookup("#fingerprintNoteLabel").queryAs(Label.class);
    }

    @SuppressWarnings("unchecked")
    private ComboBox<Protocol> protocolCombo() {
        return (ComboBox<Protocol>) lookup("#protocolCombo").query();
    }

    private static ServerConfig reality(String fingerprint) {
        return TestServers.vless("Frankfurt")
                .flow("xtls-rprx-vision")
                .reality("www.microsoft.com", REALITY_KEY, "0123abcd")
                .fingerprint(fingerprint)
                .build();
    }
}
