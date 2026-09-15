package com.vlessclient.service.outbound;

import com.vlessclient.model.ServerConfig;
import java.util.Locale;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the sing-box Shadowsocks proxy outbound: cipher method plus password
 * (stored in the ServerConfig uuid field). Shadowsocks carries no TLS or
 * transport blocks.
 */
public final class ShadowsocksOutboundBuilder extends OutboundBuilder {

    public ShadowsocksOutboundBuilder(ObjectMapper mapper) {
        super(mapper);
    }

    @Override
    public ObjectNode build(ServerConfig server, String tag) {
        ObjectNode outbound = mapper.createObjectNode();
        outbound.put("type", "shadowsocks");
        outbound.put("tag", tag);
        outbound.put("server", server.getAddress());
        outbound.put("server_port", server.getPort());
        outbound.put("method", methodName(server.getEncryption()));
        outbound.put("password", server.getUuid());

        return outbound;
    }

    /**
     * The cipher under the name the core knows. Links written for Xray use its
     * names for the same ciphers, and some spell them in capitals; the core
     * matches the exact lower-case name and refuses anything else.
     */
    private static String methodName(String method) {
        if (method == null) {
            return null;
        }
        String name = method.strip().toLowerCase(Locale.ROOT);
        return switch (name) {
            case "chacha20-poly1305" -> "chacha20-ietf-poly1305";
            case "xchacha20-poly1305" -> "xchacha20-ietf-poly1305";
            case "plain" -> "none";
            default -> name;
        };
    }
}
