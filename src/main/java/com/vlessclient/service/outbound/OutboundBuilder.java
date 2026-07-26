package com.vlessclient.service.outbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TlsConfig;
import com.vlessclient.model.TransportConfig;
import com.vlessclient.model.TransportType;
import java.util.Map;

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

        if (tls.getFingerprint() != null && !tls.getFingerprint().isEmpty()) {
            ObjectNode utls = mapper.createObjectNode();
            utls.put("enabled", true);
            utls.put("fingerprint", tls.getFingerprint());
            tlsNode.set("utls", utls);
        }

        if (tls.isAllowInsecure()) {
            tlsNode.put("insecure", true);
        }

        if (tls.isReality()) {
            ObjectNode reality = mapper.createObjectNode();
            reality.put("enabled", true);
            if (tls.getRealityPublicKey() != null && !tls.getRealityPublicKey().isEmpty()) {
                reality.put("public_key", tls.getRealityPublicKey());
            }
            if (tls.getRealityShortId() != null && !tls.getRealityShortId().isEmpty()) {
                reality.put("short_id", tls.getRealityShortId());
            }
            tlsNode.set("reality", reality);
        }

        outbound.set("tls", tlsNode);
    }

    /** Adds the {@code transport} block unless the transport is plain TCP. */
    protected final void addTransportIfNeeded(ObjectNode outbound, TransportConfig transport) {
        if (transport == null || transport.getType() == TransportType.TCP) {
            return;
        }

        ObjectNode transportNode = mapper.createObjectNode();
        transportNode.put("type", transport.getType().getValue());

        if (transport.getPath() != null && !transport.getPath().isEmpty()) {
            transportNode.put("path", transport.getPath());
        }

        if (transport.getHost() != null && !transport.getHost().isEmpty()) {
            transportNode.put("host", transport.getHost());
        }

        if (transport.getServiceName() != null && !transport.getServiceName().isEmpty()) {
            transportNode.put("service_name", transport.getServiceName());
        }

        if (transport.getHeaders() != null && !transport.getHeaders().isEmpty()) {
            ObjectNode headersNode = mapper.createObjectNode();
            for (Map.Entry<String, String> entry : transport.getHeaders().entrySet()) {
                headersNode.put(entry.getKey(), entry.getValue());
            }
            transportNode.set("headers", headersNode);
        }

        outbound.set("transport", transportNode);
    }
}
