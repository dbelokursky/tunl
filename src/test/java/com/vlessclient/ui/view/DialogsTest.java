package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import com.vlessclient.testing.UiTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * The stock buttons of the app's dialogs speak the language of the UI.
 *
 * <p>JavaFX words OK and Cancel from a bundle of its own and has none for
 * Russian, so every dialog of the Russian UI said "OK" and "Cancel", deleting
 * a server or a subscription included.</p>
 */
@UiTest
public class DialogsTest extends ApplicationTest {

    @Override
    public void start(Stage stage) {
        stage.show();
    }

    @AfterEach
    void backToEnglish() {
        interact(() -> I18n.setLocale(Locale.ENGLISH));
    }

    @Test
    void anAlertsButtonsAreWordedInTheLanguageOfTheUi() {
        interact(() -> I18n.setLocale(Locale.of("ru")));
        AtomicReference<DialogPane> pane = new AtomicReference<>();

        interact(() -> pane.set(Dialogs.alert(Alert.AlertType.CONFIRMATION).getDialogPane()));

        assertThat(((Button) pane.get().lookupButton(ButtonType.OK)).getText())
                .isEqualTo(I18n.get("button.ok"))
                .isNotEqualTo("OK");
        assertThat(((Button) pane.get().lookupButton(ButtonType.CANCEL)).getText())
                .isEqualTo(I18n.get("button.cancel"))
                .isNotEqualTo("Cancel");
    }

    /**
     * Every dialog the app builds goes through {@link Dialogs}: an alert made
     * with {@code new Alert(…)}, or a dialog whose stock buttons are never
     * worded, speaks English again in the Russian UI.
     */
    @Test
    void theAppBuildsItsDialogsThroughDialogs() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(Path.of("src", "main", "java"))) {
            for (Path file : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (name.equals("Dialogs.java") || name.equals("Confirmations.java")) {
                    continue;
                }
                String source = Files.readString(file);
                if (source.contains("new Alert(")) {
                    offenders.add(name + ": new Alert(");
                }
                if (source.contains("getButtonTypes().addAll(ButtonType.OK")
                        && !source.contains("Dialogs.localizeButtons(")) {
                    offenders.add(name + ": a Dialog with stock buttons left unworded");
                }
            }
        }
        assertThat(offenders).isEmpty();
    }
}
