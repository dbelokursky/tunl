package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.CoreLogLevel;
import com.vlessclient.model.ProxyMode;
import com.vlessclient.platform.SecretSealers;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.testing.UiTest;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * The Settings page shows what is stored each time it is shown.
 *
 * <p>The page is built once and shown again from the cache, while settings
 * change elsewhere: the mode on the Dashboard, and anything an agent sets
 * through MCP. It kept showing the values it was built with, so a mode picked
 * on the Dashboard read as the old one here.</p>
 */
@UiTest
public class SettingsShownAsStoredTest extends ApplicationTest {

    @TempDir
    static Path tempDir;

    /** Every write of the settings, from any control. */
    private final AtomicInteger saves = new AtomicInteger();
    private ConfigStore store;
    private ViewShownAware controller;

    @Override
    public void start(Stage stage) throws Exception {
        store = new ConfigStore(tempDir.resolve(UUID.randomUUID().toString()),
                SecretSealers.disabled()) {
            @Override
            public synchronized void saveSettings(AppSettings settings) {
                saves.incrementAndGet();
                super.saveSettings(settings);
            }
        };
        ServiceLocator.register(ConfigStore.class, store);
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/SettingsView.fxml"));
        Scene scene = new Scene(loader.load(), 700, 720);
        controller = loader.getController();
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void whatChangedElsewhereIsShownWhenThePageIsShownAgain() {
        AppSettings settings = store.getSettings();
        boolean autoConnect = !settings.isAutoConnect();
        boolean allowMutations = !settings.isMcpAllowMutations();
        saves.set(0);
        interact(() -> {
            settings.setProxyMode(ProxyMode.TUN);
            settings.setCoreLogLevel(CoreLogLevel.DEBUG);
            settings.setAutoConnect(autoConnect);
            settings.setProxyDns("tls://9.9.9.9");
            settings.setSocksPort(21080);
            settings.setHealthCheckIntervalSeconds(97);
            settings.setMcpAllowMutations(allowMutations);
        });

        interact(controller::onViewShown);

        assertThat(combo("#proxyModeCombo").getValue()).isEqualTo(ProxyMode.TUN);
        assertThat(combo("#coreLogLevelCombo").getValue()).isEqualTo(CoreLogLevel.DEBUG);
        assertThat(check("#autoConnectCheck").isSelected()).isEqualTo(autoConnect);
        assertThat(field("#proxyDnsField").getText()).isEqualTo("tls://9.9.9.9");
        assertThat(field("#socksPortField").getText()).isEqualTo("21080");
        assertThat(field("#healthCheckIntervalField").getText()).isEqualTo("97");
        assertThat(check("#mcpAllowMutationsCheck").isSelected()).isEqualTo(allowMutations);
        assertThat(saves).as("showing what is stored writes nothing").hasValue(0);
    }

    /**
     * A value the page shows from the store is not an edit: leaving its field
     * does not write it back, which for the MCP port would also restart the
     * listener. Not what was wrong, but what showing the stored values must
     * not break.
     */
    @Test
    void leavingAFieldThatShowsTheStoredValueWritesNothing() {
        saves.set(0);
        interact(() -> {
            store.getSettings().setSocksPort(21080);
            store.getSettings().setMcpPort(28090);
        });
        interact(controller::onViewShown);

        for (String id : List.of("#socksPortField", "#mcpPortField")) {
            interact(() -> field(id).requestFocus());
            interact(() -> field("#httpPortField").requestFocus());
        }

        assertThat(saves).hasValue(0);
    }

    private ComboBox<?> combo(String id) {
        return lookup(id).queryAs(ComboBox.class);
    }

    private CheckBox check(String id) {
        return lookup(id).queryAs(CheckBox.class);
    }

    private TextField field(String id) {
        return lookup(id).queryAs(TextField.class);
    }
}
