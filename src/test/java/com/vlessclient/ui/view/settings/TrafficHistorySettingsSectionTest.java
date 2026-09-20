package com.vlessclient.ui.view.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.TrafficHistoryStore;
import com.vlessclient.testing.UiTest;
import com.vlessclient.ui.view.TrafficText;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * The Traffic history card in Settings, driven over plain controls.
 *
 * <p>The dialog is replaced by an answer, and the button is fired rather than
 * clicked: Monocle's pointer does not land on every control on every CI
 * platform, and what is under test is what a press does, not where the
 * pointer went.</p>
 */
@UiTest
public class TrafficHistorySettingsSectionTest extends ApplicationTest {

    private Stage stage;
    private Label summary;
    private Button clear;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        summary = new Label();
        clear = new Button();
        stage.setScene(new Scene(new VBox(summary, clear), 400, 200));
        stage.show();
    }

    @Test
    void theCardSaysHowMuchIsRecordedAndSinceWhen(@TempDir Path dir) {
        section(storeWithTraffic(dir), owner -> false);

        assertThat(summary.getText())
                .contains(TrafficText.bytes(5_000))
                .contains(LocalDate.now().format(DateTimeFormatter
                        .ofLocalizedDate(FormatStyle.MEDIUM).withLocale(I18n.getLocale())));
        assertThat(clear.isDisabled()).isFalse();
    }

    @Test
    void decliningTheDialogKeepsTheRecord(@TempDir Path dir) {
        TrafficHistoryStore store = storeWithTraffic(dir);
        section(store, owner -> false);

        interact(clear::fire);

        assertThat(store.totalRecorded()).isEqualTo(5_000);
        assertThat(clear.isDisabled()).isFalse();
    }

    /**
     * The question is asked over the window the button is in: the dialog has
     * to belong to that window for the theme's stylesheets to reach it.
     */
    @Test
    void confirmingClearsTheRecordAndLeavesNothingToPress(@TempDir Path dir) {
        TrafficHistoryStore store = storeWithTraffic(dir);
        List<Window> askedOver = new ArrayList<>();
        section(store, owner -> {
            askedOver.add(owner);
            return true;
        });

        interact(clear::fire);

        assertThat(askedOver)
                .as("one question per press, over the window the button is in")
                .containsExactly(stage);
        assertThat(store.totalRecorded()).isZero();
        assertThat(summary.getText()).isEqualTo(I18n.get("settings.traffic.history.empty"));
        assertThat(clear.isDisabled())
                .as("an empty record has nothing to confirm deleting")
                .isTrue();
    }

    /**
     * Settings is a cached view: a connected tunnel keeps adding to the record
     * while it is hidden, and re-showing it calls refresh.
     */
    @Test
    void theCardCatchesUpWithTrafficRecordedWhileSettingsWasHidden(@TempDir Path dir) {
        TrafficHistoryStore store = new TrafficHistoryStore(dir, Clock.systemDefaultZone());
        TrafficHistorySettingsSection section = section(store, owner -> false);
        assertThat(clear.isDisabled()).isTrue();

        store.record(server(), 2_000, 2_000);
        interact(section::refresh);

        assertThat(summary.getText()).contains(TrafficText.bytes(4_000));
        assertThat(clear.isDisabled()).isFalse();
    }

    @Test
    void withoutAStoreTheCardSaysNothingIsRecorded() {
        section(null, owner -> false);

        assertThat(summary.getText()).isEqualTo(I18n.get("settings.traffic.history.empty"));
        assertThat(clear.isDisabled()).isTrue();
    }

    private TrafficHistorySettingsSection section(TrafficHistoryStore store,
                                                  Predicate<Window> confirm) {
        TrafficHistorySettingsSection[] built = new TrafficHistorySettingsSection[1];
        interact(() -> {
            built[0] = new TrafficHistorySettingsSection(store,
                    new TrafficHistorySettingsSection.Controls(summary, clear), confirm);
            built[0].init();
        });
        return built[0];
    }

    private static TrafficHistoryStore storeWithTraffic(Path dir) {
        TrafficHistoryStore store = new TrafficHistoryStore(dir, Clock.systemDefaultZone());
        store.record(server(), 1_000, 4_000);
        return store;
    }

    private static ServerConfig server() {
        ServerConfig config = new ServerConfig();
        config.setId("a");
        config.setName("Amsterdam 01");
        return config;
    }
}
