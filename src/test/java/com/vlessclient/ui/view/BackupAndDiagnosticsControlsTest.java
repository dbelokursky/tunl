package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.testing.UiTest;
import java.util.List;
import java.util.Locale;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two controls that move a server list and a bug report out of the app.
 *
 * <p>Both are built by their controller rather than declared in the FXML —
 * the menu items so their labels can follow a language switch, the
 * diagnostics button because its glyph and tooltip come from code. That means
 * a rename on either side goes unnoticed until someone opens the view, which
 * is what these pin. The actions themselves open a native file dialog and are
 * deliberately not triggered.</p>
 */
@UiTest
public class BackupAndDiagnosticsControlsTest extends ApplicationTest {

    private Stage stage;

    @AfterEach
    void resetLocale() {
        interact(() -> I18n.setLocale(Locale.ENGLISH));
    }

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
    }

    @Test
    void theServersBackupMenuOffersExportAndImport() {
        Scene scene = load("ServersView");
        MenuButton menu = (MenuButton) scene.lookup("#backupMenuButton");

        assertThat(menu).isNotNull();
        assertThat(menu.getItems()).extracting(MenuItem::getId)
                .containsExactly("exportServersItem", "importServersItem");
        assertThat(menu.getItems()).allSatisfy(
                item -> assertThat(item.getOnAction()).isNotNull());
    }

    @Test
    void theBackupMenuIsTranslatedInEveryLanguage() {
        for (Locale locale : List.of(Locale.ENGLISH, Locale.of("ru"))) {
            interact(() -> I18n.setLocale(locale));
            Scene scene = load("ServersView");
            MenuButton menu = (MenuButton) scene.lookup("#backupMenuButton");

            assertThat(menu.getText())
                    .withFailMessage("backupMenuButton is hardcoded in %s", locale)
                    .isEqualTo(I18n.get("servers.backup"));
            assertThat(menu.getItems()).extracting(MenuItem::getText)
                    .withFailMessage("the backup menu items are hardcoded in %s", locale)
                    .containsExactly(
                            I18n.get("servers.backup.export"),
                            I18n.get("servers.backup.import"));
        }
    }

    @Test
    void theBackupMenuLabelsActuallyDifferBetweenLanguages() {
        interact(() -> I18n.setLocale(Locale.ENGLISH));
        String english = itemLabels();
        interact(() -> I18n.setLocale(Locale.of("ru")));

        // The assertion above compares each label with the bundle, which a
        // missing Russian key would satisfy by falling back to English.
        assertThat(itemLabels()).isNotEqualTo(english);
    }

    private String itemLabels() {
        MenuButton menu = (MenuButton) load("ServersView").lookup("#backupMenuButton");
        return menu.getText() + "|" + menu.getItems().stream()
                .map(MenuItem::getText).reduce("", (a, b) -> a + "|" + b);
    }

    @Test
    void theLogsToolbarOffersDiagnosticsBesideTheDownload() {
        Scene scene = load("LogsView");
        Button diagnostics = (Button) scene.lookup("#diagnosticsButton");

        assertThat(diagnostics).isNotNull();
        // Icon-only, so the tooltip is the only thing naming the action.
        assertThat(diagnostics.getGraphic()).isNotNull();
        assertThat(diagnostics.getTooltip()).isNotNull();
        assertThat(diagnostics.getTooltip().getText())
                .isEqualTo(I18n.get("logs.diagnostics.tooltip"));
    }

    /** Loads a view fresh, so each assertion sees the locale set just now. */
    private Scene load(String view) {
        final Scene[] holder = new Scene[1];
        interact(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(
                        getClass().getResource("/fxml/" + view + ".fxml"));
                Parent root = loader.load();
                Scene scene = new Scene(root, 900, 640);
                scene.getStylesheets().add(
                        getClass().getResource("/css/light.css").toExternalForm());
                stage.setScene(scene);
                stage.show();
                holder[0] = scene;
            } catch (Exception e) {
                throw new IllegalStateException("could not load " + view, e);
            }
        });
        return holder[0];
    }
}
