package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ThemeCss;
import com.vlessclient.testing.UiTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.skin.ComboBoxListViewSkin;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Dashboard's mode and server-selection combos follow a language switch,
 * and keep their values through it.
 *
 * <p>Each drew its items in cells of its own, once, so after a switch to
 * Russian they still said "System Proxy" and "Selected server" in a Russian
 * window until the app restarted, and a list opened before the switch said
 * them too when opened again. A redraw by setting the value again would be
 * taken for the user choosing: the mode would be saved again, and the server
 * selection, changed while connected, restarts the tunnel.</p>
 */
@UiTest
@SuppressWarnings("unchecked")
public class DashboardCombosLanguageTest extends ApplicationTest {

    private Stage stage;
    private MainViewController main;
    private Parent root;

    @Override
    public void start(Stage stage) throws Exception {
        this.stage = stage;
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainView.fxml"));
        root = loader.load();
        main = loader.getController();
        Scene scene = new Scene(root, 1100, 740);
        scene.getStylesheets().setAll(ThemeCss.light());
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void english() {
        interact(() -> {
            if (!stage.isShowing()) {
                stage.show();
            }
            I18n.setLocale(Locale.ENGLISH);
        });
    }

    /** As a user switches: on the Settings page, then back to the Dashboard. */
    @Test
    void aLanguagePickedInSettingsRewordsTheCombosAndKeepsTheirValues() {
        interact(main::showDashboard);
        // Opened once in English, so the lists' cells exist before the switch.
        interact(() -> {
            for (ComboBox<?> combo : combos()) {
                combo.show();
                combo.hide();
            }
        });
        List<Object> values = values();
        AtomicInteger changes = countChanges();
        try {
            interact(main::showSettings);
            interact(() -> language().setValue("ru"));
            interact(main::showDashboard);

            assertReworded();
            assertThat(values()).as("the combos' values").isEqualTo(values);
            assertThat(changes).as("changes of either combo's value").hasValue(0);
        } finally {
            interact(main::showSettings);
            interact(() -> language().setValue("en"));
        }
    }

    /** As an agent's set_setting switches it, with the Dashboard on screen. */
    @Test
    void aLanguageSetWhileTheDashboardIsShownRewordsItAtOnce() {
        interact(main::showDashboard);
        List<Object> values = values();
        AtomicInteger changes = countChanges();

        interact(() -> I18n.setLocale(Locale.of("ru")));

        assertReworded();
        assertThat(values()).as("the combos' values").isEqualTo(values);
        assertThat(changes).as("changes of either combo's value").hasValue(0);
    }

    /** Switched while the window was closed to the tray, and shown again. */
    @Test
    void aLanguageSetWhileTheWindowIsHiddenIsShownWhenItComesBack() {
        interact(main::showDashboard);
        List<Object> values = values();

        interact(stage::hide);
        interact(() -> I18n.setLocale(Locale.of("ru")));
        interact(stage::show);

        assertReworded();
        assertThat(values()).as("the combos' values").isEqualTo(values);
    }

    /** Both combos, closed and open, in the language of the UI. */
    private void assertReworded() {
        ComboBox<?> mode = combo("#proxyModeCombo");
        ComboBox<?> selection = combo("#serverSelectionCombo");
        assertThat(shown(mode)).as("the closed mode combo")
                .isEqualTo(I18n.get("settings.proxy.system"));
        assertThat(shown(selection)).as("the closed selection combo")
                .isEqualTo(I18n.get("dashboard.selection.single"));
        assertThat(listed(mode)).as("the mode list").containsExactly(
                I18n.get("settings.proxy.system"), I18n.get("settings.proxy.tun"));
        assertThat(listed(selection)).as("the selection list").containsExactly(
                I18n.get("dashboard.selection.single"), I18n.get("dashboard.selection.auto"));
    }

    private List<ComboBox<?>> combos() {
        return List.of(combo("#proxyModeCombo"), combo("#serverSelectionCombo"));
    }

    private List<Object> values() {
        List<Object> values = new ArrayList<>();
        interact(() -> combos().forEach(combo -> values.add(combo.getValue())));
        return values;
    }

    private AtomicInteger countChanges() {
        AtomicInteger changes = new AtomicInteger();
        interact(() -> combos().forEach(combo -> combo.valueProperty()
                .addListener((obs, was, is) -> changes.incrementAndGet())));
        return changes;
    }

    /** What the closed combo shows: its button cell's text. */
    private String shown(ComboBox<?> combo) {
        String[] text = new String[1];
        interact(() -> {
            combo.applyCss();
            combo.layout();
            for (Node child : combo.getChildrenUnmodifiable()) {
                if (child instanceof ListCell<?> cell) {
                    text[0] = cell.getText();
                }
            }
        });
        return text[0];
    }

    /** What the open list shows, row by row. */
    private List<String> listed(ComboBox<?> combo) {
        List<String> rows = new ArrayList<>();
        interact(combo::show);
        interact(() -> {
            Node list = ((ComboBoxListViewSkin<?>) combo.getSkin()).getPopupContent();
            list.applyCss();
            ((Parent) list).layout();
            for (Node node : list.lookupAll(".list-cell")) {
                if (node instanceof ListCell<?> cell && !cell.isEmpty()
                        && cell.getText() != null) {
                    rows.add(cell.getText());
                }
            }
            combo.hide();
        });
        return rows;
    }

    private ComboBox<String> language() {
        return (ComboBox<String>) root.lookup("#languageCombo");
    }

    private ComboBox<?> combo(String id) {
        return (ComboBox<?>) root.lookup(id);
    }
}
