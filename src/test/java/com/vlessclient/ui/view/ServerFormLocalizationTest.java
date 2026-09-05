package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ThemeCss;
import com.vlessclient.model.Protocol;
import com.vlessclient.testing.UiTest;
import java.util.Locale;
import java.util.Map;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Labeled;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The add/edit server form is the app's main data-entry screen, and its
 * wording used to sit in the FXML in English. These pin the two things that
 * can quietly undo that: a label drifting back out of the bundle, and a
 * {@code setText} landing on a property that is now bound.
 */
@UiTest
public class ServerFormLocalizationTest extends ApplicationTest {

    /** Label fx:id -> the bundle key it must render. */
    private static final Map<String, String> BOUND_LABELS = Map.ofEntries(
            Map.entry("formTitleLabel", "form.title"),
            Map.entry("protocolLabel", "form.protocol"),
            Map.entry("nameLabel", "form.name"),
            Map.entry("transportSectionLabel", "form.transport"),
            Map.entry("transportTypeLabel", "form.type"),
            Map.entry("wsPathLabel", "form.path"),
            Map.entry("wsHostLabel", "form.host"),
            Map.entry("grpcServiceNameLabel", "form.service.name"),
            Map.entry("tlsSectionLabel", "form.tls"),
            Map.entry("tlsEnabledCheck", "form.tls.enable"),
            Map.entry("sniLabel", "form.server.name"),
            Map.entry("fingerprintLabel", "form.fingerprint"),
            Map.entry("allowInsecureCheck", "form.allow.insecure"),
            Map.entry("realityCheck", "form.reality"),
            Map.entry("realityPublicKeyLabel", "form.public.key"),
            Map.entry("realityShortIdLabel", "form.short.id"),
            Map.entry("saveButton", "button.save"),
            Map.entry("cancelButton", "button.cancel.action"));

    private Scene scene;

    @AfterEach
    void resetLocale() {
        interact(() -> I18n.setLocale(Locale.ENGLISH));
    }

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/ServerFormView.fxml"));
        Parent root = loader.load();
        scene = new Scene(root, 520, 700);
        scene.getStylesheets().addAll(ThemeCss.light());
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void everyFixedLabelComesFromTheBundle() {
        BOUND_LABELS.forEach((id, key) -> {
            Labeled node = (Labeled) scene.lookup("#" + id);
            assertThat(node)
                    .withFailMessage("#%s is gone from the FXML; the binding in "
                            + "ServerFormController points at nothing", id)
                    .isNotNull();
            assertThat(node.getText())
                    .withFailMessage("#%s shows \"%s\" but the bundle says \"%s\" — "
                            + "the label is back to a hardcoded string",
                            id, node.getText(), I18n.get(key))
                    .isEqualTo(I18n.get(key));
        });
    }

    /** A hardcoded label looks fine until the language changes. */
    @Test
    void labelsFollowALanguageSwitch() {
        String englishTitle = ((Labeled) scene.lookup("#formTitleLabel")).getText();

        interact(() -> I18n.setLocale(Locale.of("ru")));

        BOUND_LABELS.forEach((id, key) -> {
            Labeled node = (Labeled) scene.lookup("#" + id);
            assertThat(node.getText())
                    .withFailMessage("#%s kept \"%s\" after switching to Russian",
                            id, node.getText())
                    .isEqualTo(I18n.get(key));
        });

        assertThat(((Labeled) scene.lookup("#formTitleLabel")).getText())
                .withFailMessage("the Russian bundle returned the English string, so this "
                        + "test would pass even with the binding removed")
                .isNotEqualTo(englishTitle);
    }

    /**
     * Every protocol rewrites the uuid/encryption/flow labels. They are bound
     * now, and a bound property throws on setText — so walking the protocols
     * is what catches a stray assignment.
     */
    @Test
    void everyProtocolRelabelsWithoutTouchingABoundProperty() {
        @SuppressWarnings("unchecked")
        ComboBox<Protocol> combo = (ComboBox<Protocol>) scene.lookup("#protocolCombo");

        for (Protocol protocol : Protocol.values()) {
            interact(() -> combo.setValue(protocol));
            String uuid = ((Labeled) scene.lookup("#uuidLabel")).getText();
            assertThat(uuid)
                    .withFailMessage("uuid label empty for %s", protocol)
                    .isNotBlank();
            assertThat(uuid)
                    .withFailMessage("uuid label for %s shows the raw key \"%s\"", protocol, uuid)
                    .doesNotContain("form.");
        }

        // Trojan renames it to the password label; if the protocol switch had
        // stopped taking effect, this would still read "UUID *".
        interact(() -> combo.setValue(Protocol.TROJAN));
        assertThat(((Labeled) scene.lookup("#uuidLabel")).getText())
                .isEqualTo(I18n.get("form.password") + " *");
    }
}
