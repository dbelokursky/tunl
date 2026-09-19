package com.vlessclient.service.outbound;

import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TlsConfig;
import com.vlessclient.model.TransportConfig;
import com.vlessclient.model.TransportType;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Base class for the per-protocol sing-box outbound builders. Holds the shared
 * Jackson mapper and the TLS/Reality and transport JSON fragments that several
 * protocols embed into their outbound objects.
 */
public abstract class OutboundBuilder {

    /** Shared mapper used to create the JSON nodes of the outbound. */
    protected final ObjectMapper mapper;

    protected OutboundBuilder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Builds the sing-box outbound object for the given server under the given
     * tag.
     *
     * <p>The tag is supplied rather than fixed: a server's outbound is now one
     * member of a proxy group, so it carries its own
     * {@link OutboundTags#server(String) server tag} while the group holds the
     * {@code proxy} tag everything downstream resolves.</p>
     *
     * @param server the server to build the outbound from
     * @param tag the sing-box tag to emit
     * @return the outbound as a JSON object node
     */
    public abstract ObjectNode build(ServerConfig server, String tag);

    /** Adds the {@code tls} block (server name, ALPN, uTLS, Reality) when TLS is enabled. */
    protected final void addTlsIfEnabled(ObjectNode outbound, TlsConfig tls) {
        if (tls == null || !tls.isEnabled()) {
            return;
        }

        ObjectNode tlsNode = mapper.createObjectNode();
        tlsNode.put("enabled", true);

        if (tls.getServerName() != null && !tls.getServerName().isEmpty()) {
            tlsNode.put("server_name", tls.getServerName());
        }

        if (tls.getAlpn() != null && !tls.getAlpn().isEmpty()) {
            ArrayNode alpnArray = mapper.createArrayNode();
            for (String a : tls.getAlpn().split(",")) {
                alpnArray.add(a.trim());
            }
            tlsNode.set("alpn", alpnArray);
        }

        String fingerprint = CoreSettings.fingerprint(tls);
        if (fingerprint != null) {
            ObjectNode utls = mapper.createObjectNode();
            utls.put("enabled", true);
            utls.put("fingerprint", fingerprint);
            tlsNode.set("utls", utls);
        }

        if (tls.isAllowInsecure()) {
            tlsNode.put("insecure", true);
        }

        if (tls.isReality()) {
            ObjectNode reality = mapper.createObjectNode();
            reality.put("enabled", true);
            if (tls.getRealityPublicKey() != null && !tls.getRealityPublicKey().isBlank()) {
                reality.put("public_key",
                        CoreSettings.realityPublicKey(tls.getRealityPublicKey()));
            }
            String shortId = CoreSettings.realityShortId(tls.getRealityShortId());
            if (!shortId.isEmpty()) {
                reality.put("short_id", shortId);
            }
            tlsNode.set("reality", reality);
        }

        outbound.set("tls", tlsNode);
    }

    /**
     * Adds the {@code transport} block unless the transport is plain TCP.
     *
     * <p>Only the fields the type defines ({@code fieldsOf}) are written.
     * sing-box refuses the whole configuration over a field a transport does
     * not know, and every stored server is a member of the proxy group, so one
     * server's stray field stopped all of them from connecting. A WebSocket
     * link with a Host header, the usual CDN setup, did exactly that.</p>
     */
    protected final void addTransportIfNeeded(ObjectNode outbound, TransportConfig transport) {
        if (transport == null || transport.getType() == null
                || transport.getType() == TransportType.TCP) {
            return;
        }
        Set<String> fields = fieldsOf(transport.getType());

        ObjectNode transportNode = mapper.createObjectNode();
        transportNode.put("type", transport.getType().getValue());

        if (fields.contains("path")) {
            putIfPresent(transportNode, "path", transport.getPath());
        }
        if (fields.contains("host")) {
            putIfPresent(transportNode, "host", transport.getHost());
        }
        if (fields.contains("service_name")) {
            // VMess links keep gRPC's service name in path, and servers
            // imported before the parser mapped it still carry it there.
            putIfPresent(transportNode, "service_name", present(transport.getServiceName())
                    ? transport.getServiceName() : transport.getPath());
        }
        if (fields.contains("headers")) {
            ObjectNode headers = headersOf(transport, !fields.contains("host"));
            if (!headers.isEmpty()) {
                transportNode.set("headers", headers);
            }
        }

        outbound.set("transport", transportNode);
    }

    /**
     * The fields each sing-box transport defines beside its type. A switch
     * expression, so a new transport type does not compile until its fields
     * are decided here.
     */
    private static Set<String> fieldsOf(TransportType type) {
        return switch (type) {
            case TCP, QUIC -> Set.of();
            case WEBSOCKET -> Set.of("path", "headers");
            case HTTP2, HTTPUPGRADE -> Set.of("host", "path", "headers");
            case GRPC -> Set.of("service_name");
        };
    }

    /**
     * The request headers, with the link's host added as {@code Host} when the
     * transport has no host field of its own. A Host header the server's
     * configuration already names, in any case, is left as it is.
     */
    private ObjectNode headersOf(TransportConfig transport, boolean hostAsHeader) {
        ObjectNode headers = mapper.createObjectNode();
        boolean hasHostHeader = false;
        if (transport.getHeaders() != null) {
            for (Map.Entry<String, String> entry : transport.getHeaders().entrySet()) {
                headers.put(entry.getKey(), entry.getValue());
                hasHostHeader |= "host".equalsIgnoreCase(entry.getKey());
            }
        }
        if (hostAsHeader && !hasHostHeader && present(transport.getHost())) {
            headers.put("Host", transport.getHost());
        }
        return headers;
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (present(value)) {
            node.put(field, value);
        }
    }

    private static boolean present(String value) {
        return value != null && !value.isEmpty();
    }
}
