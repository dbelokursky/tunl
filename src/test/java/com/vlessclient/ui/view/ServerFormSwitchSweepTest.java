package com.vlessclient.ui.view;

import com.vlessclient.app.ThemeCss;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.testing.TestServers;
import com.vlessclient.testing.UiTest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A protocol switch in the server form leaves no other protocol's text in the
 * fields the protocols share.
 *
 * <p>Hysteria2's obfuscation password and WireGuard's local address are one
 * editable combo, and WireGuard's peer key is another. Every switch takes
 * both out of free-text mode and gives it back to those two protocols, and
 * JavaFX's skin then showed again what the editor held when it stopped being
 * editable, as text the app had put there, while the value was empty. Save
 * commits the combos, which made that text the value: a WireGuard form turned
 * Hysteria2 was saved with the local address as its obfuscation password, so
 * it never connected, and a Hysteria2 server moved to WireGuard had its
 * password, a secret the keychain keeps, as its address, in plain text in
 * servers.json and quoted by the core's refusal in the log.</p>
 */
@UiTest
@SuppressWarnings("unchecked")
public class ServerFormSwitchSweepTest extends ApplicationTest {

    private static final String PRIVATE_KEY = "tSvcDcFFKiY/1dfLHXsTKZPYGLBRXpMAyxr5uNITbEk=";
    private static final String PEER_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=";

    private final AtomicReference<ServerConfig> saved = new AtomicReference<>();
    private Stage stage;
    private Parent root;
    private ServerFormController controller;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
    }

    @AfterEach
    void closeOtherWindows() {
        interact(() -> {
            for (Window window : List.copyOf(Window.getWindows())) {
                if (window != stage) {
                    window.hide();
                }
            }
        });
    }

    /** One server of each protocol, each with its own values in the shared fields. */
    private static Map<Protocol, ServerConfig> servers() {
        return Map.of(
                Protocol.VLESS, TestServers.vless("A").flow("xtls-rprx-vision").build(),
                Protocol.VMESS, TestServers.vless("B").protocol(Protocol.VMESS)
                        .encryption("chacha20-poly1305").build(),
                Protocol.TROJAN, TestServers.vless("C").protocol(Protocol.TROJAN).build(),
                Protocol.SHADOWSOCKS, TestServers.vless("D").protocol(Protocol.SHADOWSOCKS)
                        .encryption("2022-blake3-aes-128-gcm").build(),
                Protocol.HYSTERIA2, TestServers.vless("E").protocol(Protocol.HYSTERIA2)
                        .encryption("gecko").flow("HY2-OBFS-PW").build(),
                Protocol.WIREGUARD, TestServers.vless("F").protocol(Protocol.WIREGUARD)
                        .encryption(PEER_KEY).flow("10.9.8.7/32").build());
    }

    /** Which of [encryption, flow] mean something for {@code protocol}. */
    private static boolean[] meaningful(Protocol protocol) {
        return switch (protocol) {
            case VLESS -> new boolean[] {false, true};
            case VMESS, SHADOWSOCKS -> new boolean[] {true, false};
            case TROJAN -> new boolean[] {false, false};
            case HYSTERIA2, WIREGUARD -> new boolean[] {true, true};
        };
    }

    /**
     * Every server, every walk of one or two switches: an editable combo shows
     * its value, and the fields the protocol walked to uses hold the server's
     * own values when the walk ends where it began, and none of them when it
     * ends elsewhere.
     */
    @Test
    void everyWalkLeavesTheSharedFieldsToTheProtocolItEndsOn() {
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<Protocol, ServerConfig> start : servers().entrySet()) {
            for (List<Protocol> walk : walks()) {
                check(start.getKey(), start.getValue(), walk, wrong);
            }
        }
        assertThat(wrong).as("walks that left another protocol's text").isEmpty();
    }

    private static List<List<Protocol>> walks() {
        List<List<Protocol>> walks = new ArrayList<>();
        for (Protocol first : Protocol.values()) {
            walks.add(List.of(first));
            for (Protocol second : Protocol.values()) {
                walks.add(List.of(first, second));
            }
        }
        return walks;
    }

    private void check(Protocol began, ServerConfig server, List<Protocol> walk,
                       List<String> wrong) {
        String[] shown = new String[2];
        String[] before = new String[2];
        String[] values = new String[2];
        interact(() -> {
            open(server);
            ComboBox<Protocol> protocol = combo("#protocolCombo");
            walk.forEach(protocol::setValue);
            List<ComboBox<String>> shared = List.of(combo("#encryptionCombo"), combo("#flowCombo"));
            for (int f = 0; f < 2; f++) {
                ComboBox<String> field = shared.get(f);
                shown[f] = field.isEditable() ? field.getEditor().getText() : null;
                before[f] = field.getValue();
                // What Save would store: it commits both combos first.
                field.commitValue();
                values[f] = field.getValue();
            }
        });
        Protocol ended = walk.get(walk.size() - 1);
        boolean[] uses = meaningful(ended);
        String[] own = {server.getEncryption(), server.getFlow()};
        for (int f = 0; f < 2; f++) {
            String field = f == 0 ? "encryption" : "flow";
            if (shown[f] != null && !shown[f].equals(Objects.requireNonNullElse(before[f], ""))) {
                wrong.add(began + " via " + walk + ": " + field + " shows \"" + shown[f]
                        + "\" over the value \"" + before[f] + "\"");
            }
            if (!uses[f]) {
                continue;
            }
            if (ended == began) {
                if (!Objects.equals(blankToNull(values[f]), blankToNull(own[f]))) {
                    wrong.add(began + " via " + walk + ": " + field + " holds \"" + values[f]
                            + "\", the server has \"" + own[f] + "\"");
                }
                continue;
            }
            for (String mine : own) {
                if (blankToNull(mine) != null && !"none".equals(mine) && mine.equals(values[f])) {
                    wrong.add(began + " via " + walk + ": " + ended + "'s " + field
                            + " holds the " + began + " server's \"" + mine + "\"");
                }
            }
        }
    }

    /**
     * Add server: WireGuard picked and filled in, then Hysteria2 picked in its
     * place. Nobody typed an obfuscation password, and the field said so only
     * by showing the local address in it.
     */
    @Test
    void aWireGuardFormTurnedHysteria2IsSavedWithoutAnObfuscationPassword() {
        interact(() -> open(null));
        ComboBox<String> flow = combo("#flowCombo");
        interact(() -> {
            combo("#protocolCombo").setValue(Protocol.WIREGUARD);
            text("#addressField", "198.51.100.7");
            text("#portField", "51820");
            text("#uuidField", PRIVATE_KEY);
            ComboBox<String> peerKey = combo("#encryptionCombo");
            peerKey.getEditor().setText(PEER_KEY);
            peerKey.commitValue();
            flow.getEditor().setText("10.0.0.2/32");
            flow.commitValue();
        });

        interact(() -> {
            combo("#protocolCombo").setValue(Protocol.HYSTERIA2);
            text("#portField", "443");
            text("#uuidField", "hy2-password");
        });

        assertThat(flow.getEditor().getText()).as("the obfuscation password shown").isEmpty();
        interact(this::save);
        assertThat(saved.get()).as("the form saved").isNotNull();
        assertThat(saved.get().getFlow()).as("the obfuscation password saved").isNullOrEmpty();
    }

    /**
     * Edit: a Hysteria2 server with an obfuscation password moved to WireGuard
     * and given its keys, opened as the Servers page opens the form, with the
     * server loaded before the dialog shows. The local address was never
     * typed, so Save asks for it rather than storing the password there.
     */
    @Test
    void aHysteria2ServerMovedToWireGuardKeepsItsPasswordOutOfTheAddress() {
        ServerConfig server = TestServers.server().name("Hy").protocol(Protocol.HYSTERIA2)
                .address("hy.example").port(443).uuid("secret")
                .encryption("salamander").flow("obfs-secret").tls("hy.example").build();
        interact(() -> openAsTheServersPageDoes(server));
        ComboBox<String> flow = combo("#flowCombo");
        assertThat(flow.getEditor().getText()).as("the password, shown on opening")
                .isEqualTo("obfs-secret");

        interact(() -> {
            combo("#protocolCombo").setValue(Protocol.WIREGUARD);
            text("#uuidField", PRIVATE_KEY);
            ComboBox<String> peerKey = combo("#encryptionCombo");
            peerKey.getEditor().setText(PEER_KEY);
            peerKey.commitValue();
        });

        assertThat(flow.getEditor().getText()).as("the local address shown").isEmpty();
        // Save warns in a dialog of its own, which waits for an answer.
        Platform.runLater(this::save);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(saved.get()).as("refused: the local address is required").isNull();
        assertThat(flow.getValue()).as("the local address").isNullOrEmpty();
        assertThat(Window.getWindows().stream().anyMatch(w -> w != stage && w.isShowing()))
                .as("the warning that asks for it").isTrue();
    }

    /**
     * Before Save committed the combos, the stale text was stored by a focus
     * pass through the field: a keyboard user tabbing on from the password
     * field crosses it, and leaving it commits what it shows.
     */
    @Test
    void passingThroughTheFieldAfterASwitchStoresNothingInIt() {
        interact(() -> open(null));
        ComboBox<String> flow = combo("#flowCombo");
        interact(() -> {
            combo("#protocolCombo").setValue(Protocol.WIREGUARD);
            flow.getEditor().setText("10.0.0.2/32");
            flow.commitValue();
            combo("#protocolCombo").setValue(Protocol.HYSTERIA2);
        });

        interact(flow::requestFocus);
        interact(() -> lookup("#sniField").query().requestFocus());

        assertThat(flow.getValue()).as("the obfuscation password after a focus pass")
                .isNullOrEmpty();
    }

    /** The form on the test's stage, already showing, with {@code server} loaded if any. */
    private void open(ServerConfig server) {
        load();
        stage.setScene(themed(root));
        stage.show();
        skin();
        if (server != null) {
            controller.setServerConfig(server);
        }
    }

    /**
     * As ServersViewController opens it: the server is loaded into a scene not
     * yet shown, before the combos have skins, and the dialog shows after.
     */
    private void openAsTheServersPageDoes(ServerConfig server) {
        load();
        Scene scene = themed(root);
        controller.setServerConfig(server);
        stage.setScene(scene);
        stage.show();
        skin();
    }

    /**
     * Gives the form its skins now, as the first show of a dialog does. The
     * stage is the one every UI test class shares, and a class that sized it
     * leaves it showing at a set size; a scene set on it then gets its skins
     * only at the next pulse, and until then the form's ScrollPane holds none
     * of the form, so a lookup in the same step found no protocol combo.
     */
    private void skin() {
        root.applyCss();
    }

    private void load() {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/ServerFormView.fxml"));
        try {
            root = loader.load();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        controller = loader.getController();
        controller.setOnSave(saved::set);
    }

    private static Scene themed(Parent root) {
        Scene scene = new Scene(root, 520, 650);
        scene.getStylesheets().setAll(ThemeCss.light());
        return scene;
    }

    private void save() {
        ((Button) root.lookup("#saveButton")).fire();
    }

    private void text(String query, String value) {
        ((TextField) root.lookup(query)).setText(value);
    }

    private <T> ComboBox<T> combo(String query) {
        return (ComboBox<T>) root.lookup(query);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
