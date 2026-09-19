package com.vlessclient.service.outbound;

import com.vlessclient.model.ServerConfig;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the sing-box Shadowsocks proxy outbound: cipher method plus password
 * (stored in the ServerConfig uuid field), and the SIP003 plugin when the
 * server carries one. Shadowsocks has no TLS or transport blocks.
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
        outbound.put("method", CoreSettings.shadowsocksMethod(server.getEncryption()));
        outbound.put("password",
                CoreSettings.shadowsocksPassword(server.getEncryption(), server.getUuid()));
        if (server.getPlugin() != null && !server.getPlugin().isBlank()) {
            outbound.put("plugin", CoreSettings.shadowsocksPlugin(server.getPlugin()));
            if (server.getPluginOpts() != null && !server.getPluginOpts().isBlank()) {
                outbound.put("plugin_opts", server.getPluginOpts());
            }
        }

        return outbound;
    }
}
