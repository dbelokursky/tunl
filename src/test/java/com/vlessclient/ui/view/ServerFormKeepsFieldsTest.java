package com.vlessclient.ui.view;

import com.vlessclient.app.ThemeCss;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TransportType;
import com.vlessclient.testing.TestServers;
import com.vlessclient.testing.UiTest;
import java.util.concurrent.atomic.AtomicReference;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An edit keeps what the server form does not show.
 *
 * <p>Save wrote a new transport block and a new TLS block from the fields on
 * screen. So renaming a server dropped:</p>
 * <ul>
 *   <li>the transport's request headers;</li>
 *   <li>WireGuard's reserved bytes, which live in the TLS server name that
 *       WireGuard's form hides;</li>
 *   <li>fields a newer build wrote;</li>
 *   <li>a gRPC service name that a VMess link keeps in the path, which the
 *       form showed as empty.</li>
 * </ul>
 */
@UiTest
public class ServerFormKeepsFieldsTest extends ApplicationTest {

    private static final String REALITY_KEY = "WZaG00XCAiVCF2SP5fmSbKiuTbBB-lMDg_81rC8hR80";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AtomicReference<ServerConfig> saved = new AtomicReference<>();
    private ServerFormController controller;

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/ServerFormView.fxml"));
        Parent root = loader.load();
        controller = loader.getController();
        controller.setOnSave(saved::set);
        Scene scene = new Scene(root, 520, 650);
        scene.getStylesheets().setAll(ThemeCss.light());
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void renamingKeepsTheTransportHeaders() {
        ServerConfig server = TestServers.vless("Tokyo")
                .transport(TransportType.WEBSOCKET, "/ws", "cdn.example")
                .header("User-Agent", "Mozilla/5.0")
                .tls("cdn.example")
                .build();

        edit(server, () -> text("#nameField", "Tokyo 2"));

        assertThat(saved.get().getName()).isEqualTo("Tokyo 2");
        assertThat(saved.get().getTransport().getHeaders())
                .containsEntry("User-Agent", "Mozilla/5.0");
    }

    @Test
    void renamingKeepsWireGuardsReservedBytes() {
        ServerConfig server = TestServers.server().name("WARP").protocol(Protocol.WIREGUARD)
                .address("engage.example").port(2408)
                .uuid("aGVsbG8gd29ybGQgaGVsbG8gd29ybGQgaGVsbG8gd28=")
                .encryption("cGVlciBwdWJsaWMga2V5IHBlZXIgcHVibGljIGtleSA=")
                .flow("172.16.0.2")
                .build();
        server.getTls().setServerName("1,2,3");

        edit(server, () -> text("#nameField", "WARP 2"));

        assertThat(saved.get().getTls().getServerName())
                .as("the reserved bytes, kept in the TLS server name")
                .isEqualTo("1,2,3");
    }

    @Test
    void renamingKeepsFieldsANewerBuildWrote() throws Exception {
        ServerConfig server = JSON.readValue("""
                {"id": "srv-1", "name": "Tokyo", "protocol": "vless",
                 "address": "tokyo.example", "port": 443,
                 "uuid": "11111111-2222-3333-4444-555555555555",
                 "tls": {"enabled": true, "server_name": "tokyo.example",
                         "ech": {"enabled": true}},
                 "transport": {"type": "ws", "path": "/ws", "early_data": 2048}}
                """, ServerConfig.class);

        edit(server, () -> text("#nameField", "Tokyo 2"));

        JsonNode written = JSON.valueToTree(saved.get());
        assertThat(written.path("tls").path("ech").path("enabled").asBoolean()).isTrue();
        assertThat(written.path("transport").path("early_data").asInt()).isEqualTo(2048);
    }

    @Test
    void aGrpcServiceNameKeptInThePathIsShownAndSaved() {
        ServerConfig server = TestServers.vless("Tokyo").protocol(Protocol.VMESS)
                .transport(TransportType.GRPC, "tunnel", null)
                .build();

        interact(() -> controller.setServerConfig(server));

        assertThat(((TextField) lookup("#grpcServiceNameField").query()).getText())
                .isEqualTo("tunnel");
        interact(this::save);
        assertThat(saved.get().getTransport().getServiceName()).isEqualTo("tunnel");
    }

    /**
     * A WebSocket path left behind when the transport becomes gRPC would be
     * the service name the core is given, since it falls back to the path.
     */
    @Test
    void aChangedTransportDropsTheFieldsTheNewTypeHasNot() {
        ServerConfig server = TestServers.vless("Tokyo")
                .transport(TransportType.WEBSOCKET, "/ws", "cdn.example")
                .build();

        edit(server, () -> combo("#transportTypeCombo").setValue("gRPC"));

        assertThat(saved.get().getTransport().getType()).isEqualTo(TransportType.GRPC);
        assertThat(saved.get().getTransport().getPath()).isNull();
        assertThat(saved.get().getTransport().getHost()).isNull();
    }

    /**
     * The REALITY box is hidden for VMess but keeps its tick: a server moved
     * from VLESS with REALITY to VMess was saved with REALITY on.
     */
    @Test
    void realityHiddenForTheProtocolIsNotSaved() {
        ServerConfig server = TestServers.vless("Tokyo")
                .reality("tokyo.example", REALITY_KEY, "0123abcd")
                .build();

        edit(server, () -> combo("#protocolCombo").setValue(Protocol.VMESS));

        assertThat(saved.get().getTls().isReality()).isFalse();
        assertThat(saved.get().getTls().getRealityPublicKey()).isNull();
    }

    @Test
    void trojanShowsAndKeepsReality() {
        ServerConfig server = TestServers.server().name("Tokyo").protocol(Protocol.TROJAN)
                .address("tokyo.example").port(443).uuid("secret")
                .reality("tokyo.example", REALITY_KEY, "0123abcd")
                .build();

        interact(() -> controller.setServerConfig(server));

        assertThat(lookup("#realitySection").queryAs(VBox.class).isVisible()).isTrue();
        interact(this::save);
        assertThat(saved.get().getTls().isReality()).isTrue();
        assertThat(saved.get().getTls().getRealityShortId()).isEqualTo("0123abcd");
    }

    /**
     * Hysteria2's TLS box is ticked and locked. A server stored with TLS off
     * unticked it, and the save then dropped the server name.
     */
    @Test
    void hysteria2KeepsTlsOnWhateverWasStored() {
        ServerConfig server = TestServers.server().name("Hy").protocol(Protocol.HYSTERIA2)
                .address("hy.example").port(443).uuid("secret")
                .build();
        server.getTls().setServerName("hy.example");

        interact(() -> controller.setServerConfig(server));

        assertThat(((CheckBox) lookup("#tlsEnabledCheck").query()).isSelected()).isTrue();
        interact(this::save);
        assertThat(saved.get().getTls().isEnabled()).isTrue();
        assertThat(saved.get().getTls().getServerName()).isEqualTo("hy.example");
    }

    /** What the old protocol kept out of sight means nothing to the new one. */
    @Test
    void aServerMovedToAnotherProtocolStartsTheHiddenPartsAfresh() {
        ServerConfig server = TestServers.vless("Tokyo")
                .transport(TransportType.WEBSOCKET, "/ws", "cdn.example")
                .header("User-Agent", "Mozilla/5.0")
                .tls("tokyo.example")
                .build();

        edit(server, () -> {
            combo("#protocolCombo").setValue(Protocol.WIREGUARD);
            text("#uuidField", "aGVsbG8gd29ybGQgaGVsbG8gd29ybGQgaGVsbG8gd28=");
        });

        assertThat(saved.get().getTls().getServerName())
                .as("an SNI read as WireGuard's reserved bytes").isNull();
        assertThat(saved.get().getTransport().getHeaders()).isEmpty();
    }

    /** Loads {@code server}, makes {@code change}, and saves. */
    private void edit(ServerConfig server, Runnable change) {
        interact(() -> controller.setServerConfig(server));
        interact(change);
        interact(this::save);
        assertThat(saved.get()).as("the form saved").isNotNull();
    }

    private void save() {
        ((Button) lookup("#saveButton").query()).fire();
    }

    private void text(String query, String value) {
        ((TextField) lookup(query).query()).setText(value);
    }

    @SuppressWarnings("unchecked")
    private <T> ComboBox<T> combo(String query) {
        return (ComboBox<T>) lookup(query).query();
    }
}
