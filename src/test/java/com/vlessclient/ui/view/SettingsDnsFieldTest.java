package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.platform.SecretSealers;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.testing.UiTest;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * The DNS fields hold one resolver the core can use.
 *
 * <p>They stored any text. Two resolvers, typed the way every OS dialog asks
 * for a primary and a secondary, reached the core as one server named
 * "1.1.1.1, 1.0.0.1": it started, and in TUN mode no name resolved while the
 * card said Connected. A port past 65535 made it refuse the configuration.</p>
 */
@UiTest
public class SettingsDnsFieldTest extends ApplicationTest {

    @TempDir
    static Path tempDir;

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
    void aListOfResolversIsNotStoredAndTheFieldSaysWhy() {
        String stored = store.getSettings().getProxyDns();

        commit("#proxyDnsField", "1.1.1.1, 1.0.0.1");

        assertThat(store.getSettings().getProxyDns()).isEqualTo(stored);
        assertThat(field("#proxyDnsField").getText()).isEqualTo("1.1.1.1, 1.0.0.1");
        assertThat(field("#proxyDnsField").getStyleClass()).contains("field-error");
        assertThat(label("#proxyDnsProblem").isVisible()).isTrue();
        assertThat(label("#proxyDnsProblem").getText()).startsWith("Not saved");

        commit("#proxyDnsField", "tls://9.9.9.9:853");

        assertThat(store.getSettings().getProxyDns()).isEqualTo("tls://9.9.9.9:853");
        assertThat(field("#proxyDnsField").getStyleClass()).doesNotContain("field-error");
        assertThat(label("#proxyDnsProblem").isVisible()).isFalse();
        assertThat(label("#proxyDnsProblem").isManaged()).isFalse();
    }

    @Test
    void directDnsTakesOneResolverOrTheSystemsOwn() {
        commit("#directDnsField", "8.8.8.8,8.8.4.4");
        assertThat(store.getSettings().getDirectDns()).isEqualTo(AppSettings.SYSTEM_DNS);
        assertThat(label("#directDnsProblem").isVisible()).isTrue();

        commit("#directDnsField", "udp://8.8.8.8:99999");
        assertThat(store.getSettings().getDirectDns()).isEqualTo(AppSettings.SYSTEM_DNS);

        commit("#directDnsField", "77.88.8.8:53");
        assertThat(store.getSettings().getDirectDns()).isEqualTo("77.88.8.8:53");
        assertThat(label("#directDnsProblem").isVisible()).isFalse();

        commit("#directDnsField", "System");
        assertThat(store.getSettings().getDirectDns()).isEqualTo("System");
        assertThat(label("#directDnsProblem").isVisible()).isFalse();
    }

    /**
     * A list stored before the fields checked it is asked for as the default;
     * the page says so where the address is, and showing it stores nothing.
     */
    @Test
    void anAddressStoredBeforeTheCheckSaysTheDefaultIsAskedInstead() {
        interact(() -> {
            store.getSettings().setProxyDns("1.1.1.1, 8.8.8.8");
            store.getSettings().setDirectDns("8.8.8.8;8.8.4.4");
        });
        saves.set(0);

        interact(controller::onViewShown);
        interact(() -> field("#proxyDnsField").requestFocus());
        interact(() -> field("#directDnsField").requestFocus());
        interact(() -> field("#httpPortField").requestFocus());

        assertThat(field("#proxyDnsField").getText()).isEqualTo("1.1.1.1, 8.8.8.8");
        assertThat(label("#proxyDnsProblem").isVisible()).isTrue();
        assertThat(label("#proxyDnsProblem").getText()).contains("https://1.1.1.1/dns-query");
        assertThat(label("#directDnsProblem").isVisible()).isTrue();
        assertThat(label("#directDnsProblem").getText()).contains("system resolver");
        assertThat(saves).as("showing what is stored writes nothing").hasValue(0);

        interact(() -> store.getSettings().setProxyDns("https://dns.google/dns-query"));
        interact(controller::onViewShown);

        assertThat(label("#proxyDnsProblem").isVisible()).isFalse();
    }

    /** Types {@code text} into a field and leaves it, as a person does. */
    private void commit(String id, String text) {
        interact(() -> {
            field(id).requestFocus();
            field(id).setText(text);
        });
        interact(() -> field("#httpPortField").requestFocus());
    }

    private TextField field(String id) {
        return lookup(id).queryAs(TextField.class);
    }

    private Label label(String id) {
        return lookup(id).queryAs(Label.class);
    }
}
