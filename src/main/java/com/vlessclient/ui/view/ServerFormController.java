package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TlsConfig;
import com.vlessclient.model.TransportConfig;
import com.vlessclient.model.TransportType;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller for the add/edit server form. Adapts the visible fields and
 * labels to the chosen {@link Protocol}, loads an existing server for editing,
 * validates input, and hands the resulting {@link ServerConfig} back via the
 * save callback.
 */
public class ServerFormController {

    private static final Logger log = LoggerFactory.getLogger(ServerFormController.class);

    /** Appended to a required field's label. Lives here so the two places
     *  that build one cannot drift apart. */
    private static final String REQUIRED_MARKER = " *";

    // Labels whose wording never changes with the protocol. They carry an
    // fx:id purely so the bundle can reach them; the text= in the FXML is a
    // design-time placeholder that binding overwrites at load.
    @FXML private Label formTitleLabel;
    @FXML private Label protocolLabel;
    @FXML private Label nameLabel;
    @FXML private Label addressLabel;
    @FXML private Label portLabel;
    @FXML private Label transportSectionLabel;
    @FXML private Label transportTypeLabel;
    @FXML private Label wsPathLabel;
    @FXML private Label wsHostLabel;
    @FXML private Label grpcServiceNameLabel;
    @FXML private Label tlsSectionLabel;
    @FXML private Label sniLabel;
    @FXML private Label fingerprintLabel;
    @FXML private Label realityPublicKeyLabel;
    @FXML private Label realityShortIdLabel;

    @FXML private ComboBox<Protocol> protocolCombo;
    @FXML private TextField nameField;
    @FXML private TextField addressField;
    @FXML private TextField portField;
    @FXML private Label uuidLabel;
    @FXML private TextField uuidField;
    @FXML private Label encryptionLabel;
    @FXML private ComboBox<String> encryptionCombo;
    @FXML private Label flowLabel;
    @FXML private ComboBox<String> flowCombo;
    @FXML private HBox encryptionFlowBox;
    @FXML private VBox encryptionBox;
    @FXML private VBox flowBox;
    @FXML private ComboBox<String> transportTypeCombo;

    // WebSocket fields
    @FXML private VBox wsFields;
    @FXML private TextField wsPathField;
    @FXML private TextField wsHostField;

    // gRPC fields
    @FXML private VBox grpcFields;
    @FXML private TextField grpcServiceNameField;

    // Transport section
    @FXML private Separator transportSeparator;
    @FXML private VBox transportSection;

    // TLS section
    @FXML private Separator tlsSeparator;
    @FXML private VBox tlsSection;
    @FXML private CheckBox tlsEnabledCheck;
    @FXML private VBox tlsFields;
    @FXML private TextField sniField;
    @FXML private TextField alpnField;
    @FXML private TextField fingerprintField;
    @FXML private CheckBox allowInsecureCheck;

    // Reality fields
    @FXML private VBox realitySection;
    @FXML private CheckBox realityCheck;
    @FXML private VBox realityFields;
    @FXML private TextField realityPublicKeyField;
    @FXML private TextField realityShortIdField;

    @FXML private Button saveButton;
    @FXML private Button cancelButton;
    @FXML private ScrollPane formScroll;
    @FXML private HBox formActions;

    /** What the form's own content is inset by, from the FXML. */
    private static final double ACTIONS_INSET = 24;

    /** The actions bar's own breathing room, above and below. */
    private static final double ACTIONS_VERTICAL = 12;

    private ServerConfig editingServer;
    private Consumer<ServerConfig> onSave;
    private Runnable onCancel;

    /**
     * Populates the protocol, encryption, flow, and transport combos with
     * their defaults and wires the listeners that reveal the transport, TLS,
     * and Reality fields as the relevant options are toggled.
     */
    @FXML
    public void initialize() {
        bindStaticLabels();
        followScrollViewport();

        protocolCombo.setItems(FXCollections.observableArrayList(Protocol.values()));
        protocolCombo.setValue(Protocol.VLESS);

        encryptionCombo.setItems(FXCollections.observableArrayList("none", "auto", "zero"));
        encryptionCombo.setValue("none");

        flowCombo.setItems(FXCollections.observableArrayList("", "xtls-rprx-vision"));
        flowCombo.setValue("");

        transportTypeCombo.setItems(
                FXCollections.observableArrayList("TCP", "WebSocket", "gRPC", "HTTP2"));
        transportTypeCombo.setValue("TCP");

        transportTypeCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            updateTransportFields(newVal);
        });

        tlsEnabledCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            tlsFields.setVisible(newVal);
            tlsFields.setManaged(newVal);
        });

        realityCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            realityFields.setVisible(newVal);
            realityFields.setManaged(newVal);
        });

        updateFieldsForProtocol(Protocol.VLESS);
    }

    /**
     * Loads an existing server into the form for editing, selecting its
     * protocol and filling every field (transport, TLS, and Reality included)
     * from the given config.
     *
     * @param server the server to edit
     */
    public void setServerConfig(ServerConfig server) {
        this.editingServer = server;

        Protocol protocol = server.getProtocol() != null ? server.getProtocol() : Protocol.VLESS;
        protocolCombo.setValue(protocol);
        updateFieldsForProtocol(protocol);

        nameField.setText(server.getName() != null ? server.getName() : "");
        addressField.setText(server.getAddress() != null ? server.getAddress() : "");
        portField.setText(String.valueOf(server.getPort()));
        uuidField.setText(server.getUuid() != null ? server.getUuid() : "");
        encryptionCombo.setValue(server.getEncryption() != null ? server.getEncryption() : "none");
        flowCombo.setValue(server.getFlow() != null ? server.getFlow() : "");

        TransportConfig transport = server.getTransport();
        if (transport != null) {
            String transportLabel = switch (transport.getType()) {
                case WEBSOCKET -> "WebSocket";
                case GRPC -> "gRPC";
                case HTTP2 -> "HTTP2";
                default -> "TCP";
            };
            transportTypeCombo.setValue(transportLabel);

            if (transport.getType() == TransportType.WEBSOCKET) {
                wsPathField.setText(transport.getPath() != null ? transport.getPath() : "");
                wsHostField.setText(transport.getHost() != null ? transport.getHost() : "");
            } else if (transport.getType() == TransportType.GRPC) {
                grpcServiceNameField.setText(
                        transport.getServiceName() != null ? transport.getServiceName() : "");
            }
        }

        TlsConfig tls = server.getTls();
        if (tls != null) {
            tlsEnabledCheck.setSelected(tls.isEnabled());
            sniField.setText(tls.getServerName() != null ? tls.getServerName() : "");
            alpnField.setText(tls.getAlpn() != null ? tls.getAlpn() : "");
            fingerprintField.setText(tls.getFingerprint() != null ? tls.getFingerprint() : "");
            allowInsecureCheck.setSelected(tls.isAllowInsecure());
            realityCheck.setSelected(tls.isReality());
            realityPublicKeyField.setText(
                    tls.getRealityPublicKey() != null ? tls.getRealityPublicKey() : "");
            realityShortIdField.setText(
                    tls.getRealityShortId() != null ? tls.getRealityShortId() : "");
        }
    }

    public void setOnSave(Consumer<ServerConfig> onSave) {
        this.onSave = onSave;
    }

    public void setOnCancel(Runnable onCancel) {
        this.onCancel = onCancel;
    }

    @FXML
    private void onProtocolChanged() {
        Protocol selected = protocolCombo.getValue();
        if (selected != null) {
            log.debug("Protocol changed to: {}", selected);
            updateFieldsForProtocol(selected);
        }
    }

    @FXML
    private void onSaveClicked() {
        if (!validate()) {
            return;
        }

        Protocol protocol = protocolCombo.getValue();
        ServerConfig server = editingServer != null ? editingServer : new ServerConfig();
        server.setName(nameField.getText().trim());
        server.setProtocol(protocol);
        server.setAddress(addressField.getText().trim());
        server.setPort(Integer.parseInt(portField.getText().trim()));
        server.setUuid(uuidField.getText().trim());
        server.setEncryption(encryptionCombo.getValue());
        server.setFlow(flowCombo.getValue());

        // Transport
        TransportConfig transport = new TransportConfig();
        if (isTransportVisible()) {
            transport.setType(mapTransportType(transportTypeCombo.getValue()));
            if (transport.getType() == TransportType.WEBSOCKET) {
                transport.setPath(wsPathField.getText().trim());
                transport.setHost(wsHostField.getText().trim());
            } else if (transport.getType() == TransportType.GRPC) {
                transport.setServiceName(grpcServiceNameField.getText().trim());
            }
        }
        server.setTransport(transport);

        // TLS
        TlsConfig tls = new TlsConfig();
        if (isTlsVisible()) {
            tls.setEnabled(tlsEnabledCheck.isSelected());
            if (tls.isEnabled()) {
                tls.setServerName(sniField.getText().trim());
                tls.setAlpn(alpnField.getText().trim());
                tls.setFingerprint(fingerprintField.getText().trim());
                tls.setAllowInsecure(allowInsecureCheck.isSelected());
                tls.setReality(realityCheck.isSelected());
                if (tls.isReality()) {
                    tls.setRealityPublicKey(realityPublicKeyField.getText().trim());
                    tls.setRealityShortId(realityShortIdField.getText().trim());
                }
            }
        } else if (protocol == Protocol.HYSTERIA2) {
            // Hysteria2 always has TLS on
            tls.setEnabled(true);
            tls.setServerName(sniField.getText().trim());
            tls.setAlpn(alpnField.getText().trim());
            tls.setFingerprint(fingerprintField.getText().trim());
            tls.setAllowInsecure(allowInsecureCheck.isSelected());
        }
        server.setTls(tls);

        if (onSave != null) {
            onSave.accept(server);
        }
    }

    @FXML
    private void onCancelClicked() {
        if (onCancel != null) {
            onCancel.run();
        }
    }

    private void updateFieldsForProtocol(Protocol protocol) {
        // Reset all labels and visibility to defaults
        setNodeVisible(encryptionFlowBox, true);
        setNodeVisible(encryptionBox, true);
        setNodeVisible(flowBox, true);
        setNodeVisible(transportSeparator, true);
        setNodeVisible(transportSection, true);
        setNodeVisible(tlsSeparator, true);
        setNodeVisible(tlsSection, true);
        setNodeVisible(realitySection, true);

        bindRequired(uuidLabel, "form.uuid");
        uuidField.setPromptText("xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx");
        bindLabel(encryptionLabel, "form.encryption");
        bindLabel(flowLabel, "form.flow");

        // WireGuard and Hysteria2 turn these two into free-text inputs below.
        // Reset that here with everything else: left set, they stay editable
        // for whatever protocol the user picks next — which both lets an
        // arbitrary string into a fixed-choice field and leaves the row 4px
        // taller than the rest of the form, since an editable ComboBox wraps a
        // text field of its own.
        encryptionCombo.setEditable(false);
        flowCombo.setEditable(false);

        // Reset encryption combo to VLESS defaults
        encryptionCombo.setItems(FXCollections.observableArrayList("none", "auto", "zero"));
        if (encryptionCombo.getValue() == null
                || !encryptionCombo.getItems().contains(encryptionCombo.getValue())) {
            encryptionCombo.setValue("none");
        }

        // Reset flow combo to VLESS defaults
        flowCombo.setItems(FXCollections.observableArrayList("", "xtls-rprx-vision"));
        if (flowCombo.getValue() == null
                || !flowCombo.getItems().contains(flowCombo.getValue())) {
            flowCombo.setValue("");
        }

        switch (protocol) {
            case VLESS -> {
                // All fields shown — defaults are fine
            }
            case VMESS -> {
                // No Flow, no Reality
                setNodeVisible(flowBox, false);
                setNodeVisible(realitySection, false);
            }
            case TROJAN -> {
                // UUID label -> "Password", no Flow, no Reality
                bindRequired(uuidLabel, "form.password");
                uuidField.setPromptText(I18n.get("form.prompt.password"));
                setNodeVisible(flowBox, false);
                setNodeVisible(realitySection, false);
                setNodeVisible(encryptionBox, false);
            }
            case SHADOWSOCKS -> {
                // UUID -> "Password", Encryption -> "Method" with SS ciphers, no Transport, no TLS
                bindRequired(uuidLabel, "form.password");
                uuidField.setPromptText(I18n.get("form.prompt.password"));
                bindLabel(encryptionLabel, "form.method");
                encryptionCombo.setItems(FXCollections.observableArrayList(
                        "aes-256-gcm",
                        "chacha20-ietf-poly1305",
                        "2022-blake3-aes-128-gcm",
                        "2022-blake3-aes-256-gcm"));
                encryptionCombo.setValue("aes-256-gcm");
                setNodeVisible(flowBox, false);
                setNodeVisible(transportSeparator, false);
                setNodeVisible(transportSection, false);
                setNodeVisible(tlsSeparator, false);
                setNodeVisible(tlsSection, false);
            }
            case HYSTERIA2 -> {
                // UUID -> "Password", TLS always on (no checkbox), no Transport
                // Flow field used for "Obfuscation Password"
                bindRequired(uuidLabel, "form.password");
                uuidField.setPromptText(I18n.get("form.prompt.password"));
                setNodeVisible(encryptionBox, false);
                bindLabel(flowLabel, "form.obfuscation.password");
                flowCombo.setItems(FXCollections.observableArrayList(""));
                flowCombo.setEditable(true);
                setNodeVisible(transportSeparator, false);
                setNodeVisible(transportSection, false);
                setNodeVisible(realitySection, false);
                // TLS visible but force enabled
                tlsEnabledCheck.setSelected(true);
                tlsEnabledCheck.setDisable(true);
            }
            case WIREGUARD -> {
                // UUID -> "Private Key", Encryption -> "Peer Public Key", Flow -> "Local Address"
                // No Transport, no TLS
                bindRequired(uuidLabel, "form.private.key");
                uuidField.setPromptText(I18n.get("form.prompt.private.key"));
                bindLabel(encryptionLabel, "form.peer.public.key");
                encryptionCombo.setItems(FXCollections.observableArrayList(""));
                encryptionCombo.setEditable(true);
                bindLabel(flowLabel, "form.local.address");
                flowCombo.setItems(FXCollections.observableArrayList(""));
                flowCombo.setEditable(true);
                setNodeVisible(transportSeparator, false);
                setNodeVisible(transportSection, false);
                setNodeVisible(tlsSeparator, false);
                setNodeVisible(tlsSection, false);
            }
            default -> throw new IllegalStateException("Unhandled protocol: " + protocol);
        }

        // Restore TLS checkbox for non-Hysteria2 protocols
        if (protocol != Protocol.HYSTERIA2) {
            tlsEnabledCheck.setDisable(false);
        }
    }

    /**
     * Keeps Save and Cancel in the column the fields above them form.
     *
     * <p>The actions bar is a sibling of the scroll pane, not a row inside it,
     * so it keeps the full width when a scrollbar appears and the fields lose
     * it — which put its buttons 14px right of every field the moment the form
     * grew tall enough to scroll, which is most of the time. Following the
     * viewport's right edge costs one listener and is exact in both states.</p>
     */
    private void followScrollViewport() {
        // What the fields lose on the right and this bar does not: the scroll
        // pane's own right border plus the scrollbar when there is one. The
        // viewport's bounds are relative to the viewport, not to the scroll
        // pane, so its left inset has to come off separately — reading maxX as
        // if it were a scroll-pane coordinate double-counts that inset and
        // leaves the buttons 2px short.
        //
        // A binding rather than a listener: width and viewport bounds settle in
        // separate passes, and reading one while the other is stale sets a
        // padding that stays wrong until something else invalidates it.
        formActions.paddingProperty().bind(Bindings.createObjectBinding(
                () -> new Insets(ACTIONS_VERTICAL,
                        ACTIONS_INSET + formScroll.getWidth()
                                - formScroll.getInsets().getLeft()
                                - formScroll.getViewportBounds().getWidth(),
                        ACTIONS_VERTICAL, ACTIONS_INSET),
                formScroll.widthProperty(), formScroll.viewportBoundsProperty(),
                formScroll.insetsProperty()));
    }

    /**
     * Validates the form, marking the offending fields and telling the user
     * what is wrong.
     *
     * <p>Failures used to go only to the log, so pressing Save on an
     * incomplete form did nothing at all: the dialog stayed open with no
     * message and no indication of which field to fix.</p>
     */
    private boolean validate() {
        List<String> errors = new ArrayList<>();

        boolean addressMissing = isBlank(addressField.getText());
        markField(addressField, addressMissing);
        if (addressMissing) {
            errors.add(I18n.get("error.field.required", I18n.get("form.address")));
        }

        errors.addAll(validatePort());

        boolean uuidMissing = isBlank(uuidField.getText());
        markField(uuidField, uuidMissing);
        if (uuidMissing) {
            // The label carries the protocol-specific name (UUID, Password, …).
            errors.add(I18n.get("error.field.required",
                    uuidLabel.getText().replace(" *", "")));
        }

        if (errors.isEmpty()) {
            return true;
        }
        log.warn("Validation failed: {}", errors);
        showValidationErrors(errors);
        return false;
    }

    private List<String> validatePort() {
        String text = portField.getText();
        if (isBlank(text)) {
            markField(portField, true);
            return List.of(I18n.get("error.field.required", I18n.get("form.port")));
        }
        try {
            int port = Integer.parseInt(text.trim());
            boolean outOfRange = port < 1 || port > 65535;
            markField(portField, outOfRange);
            return outOfRange ? List.of(I18n.get("error.port.range")) : List.of();
        } catch (NumberFormatException e) {
            markField(portField, true);
            return List.of(I18n.get("error.port.number"));
        }
    }

    private void showValidationErrors(List<String> errors) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        if (saveButton.getScene() != null) {
            alert.initOwner(saveButton.getScene().getWindow());
        }
        alert.setTitle(I18n.get("form.invalid.title"));
        alert.setHeaderText(I18n.get("form.invalid.header"));
        alert.setContentText(String.join("\n", errors));
        alert.showAndWait();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * Adds or removes the error styling. Guarded against duplicates: the class
     * was previously appended on every failed attempt, so the list grew each
     * time the user pressed Save.
     */
    private static void markField(TextField field, boolean hasError) {
        if (hasError) {
            if (!field.getStyleClass().contains("field-error")) {
                field.getStyleClass().add("field-error");
            }
        } else {
            field.getStyleClass().remove("field-error");
        }
    }

    private void updateTransportFields(String type) {
        wsFields.setVisible(false);
        wsFields.setManaged(false);
        grpcFields.setVisible(false);
        grpcFields.setManaged(false);

        if ("WebSocket".equals(type)) {
            wsFields.setVisible(true);
            wsFields.setManaged(true);
        } else if ("gRPC".equals(type)) {
            grpcFields.setVisible(true);
            grpcFields.setManaged(true);
        }
    }

    private TransportType mapTransportType(String label) {
        return switch (label) {
            case "WebSocket" -> TransportType.WEBSOCKET;
            case "gRPC" -> TransportType.GRPC;
            case "HTTP2" -> TransportType.HTTP2;
            default -> TransportType.TCP;
        };
    }

    /**
     * Points every label whose wording does not depend on the protocol at the
     * bundle. The protocol-dependent three — uuid, encryption, flow — are
     * bound the same way in {@link #updateFieldsForProtocol}, just to a key
     * that changes with the selection.
     *
     * <p>Binding rather than setting is what lets the form follow a language
     * switch while it is open. It also means nothing may call {@code setText}
     * on these again: a bound property throws.</p>
     */
    private void bindStaticLabels() {
        bindLabel(formTitleLabel, "form.title");
        bindLabel(protocolLabel, "form.protocol");
        bindLabel(nameLabel, "form.name");
        bindRequired(addressLabel, "form.address");
        bindRequired(portLabel, "form.port");
        bindLabel(transportSectionLabel, "form.transport");
        bindLabel(transportTypeLabel, "form.type");
        bindLabel(wsPathLabel, "form.path");
        bindLabel(wsHostLabel, "form.host");
        bindLabel(grpcServiceNameLabel, "form.service.name");
        bindLabel(tlsSectionLabel, "form.tls");
        bindLabel(tlsEnabledCheck, "form.tls.enable");
        bindLabel(sniLabel, "form.server.name");
        bindLabel(fingerprintLabel, "form.fingerprint");
        bindLabel(allowInsecureCheck, "form.allow.insecure");
        bindLabel(realityCheck, "form.reality");
        bindLabel(realityPublicKeyLabel, "form.public.key");
        bindLabel(realityShortIdLabel, "form.short.id");
        bindLabel(saveButton, "button.save");
        bindLabel(cancelButton, "button.cancel.action");
        nameField.promptTextProperty().bind(I18n.binding("form.prompt.name"));
    }

    /** Binds a label, button or checkbox to a bundle key. */
    private static void bindLabel(Labeled node, String key) {
        node.textProperty().bind(I18n.binding(key));
    }

    /** Same, plus the marker that flags the field as required. */
    private static void bindRequired(Labeled node, String key) {
        node.textProperty().bind(I18n.binding(key).concat(REQUIRED_MARKER));
    }

    private void setNodeVisible(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private boolean isTransportVisible() {
        return transportSection.isVisible() && transportSection.isManaged();
    }

    private boolean isTlsVisible() {
        return tlsSection.isVisible() && tlsSection.isManaged();
    }
}
