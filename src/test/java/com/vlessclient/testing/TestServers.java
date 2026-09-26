package com.vlessclient.testing;

import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TransportType;

/**
 * Servers for tests, described by what a test cares about.
 *
 * <p>Sixty-odd test classes each carried a private method that filled a new
 * {@link ServerConfig} field by field, and they drifted apart: some set a
 * protocol and some did not, some an id, some a credential. A builder starts
 * from the model's own defaults (a random id, TCP transport, TLS off) and
 * sets only what the test names, so what a test does not mention is what the
 * app would have.</p>
 *
 * <p>One builder makes one server: {@link Builder#build} hands over the
 * object it filled.</p>
 */
public final class TestServers {

    /** A credential any VLESS, VMess or Trojan test server can carry. */
    public static final String UUID = "11111111-2222-3333-4444-555555555555";

    private TestServers() {
    }

    /**
     * A server with nothing set beyond the model's defaults.
     *
     * @return a builder over a new server
     */
    public static Builder server() {
        return new Builder();
    }

    /**
     * A VLESS server at {@code <name>.example:443} with {@link #UUID}, the
     * shape most tests need.
     *
     * @param name its name, which also names its host
     * @return a builder over it
     */
    public static Builder vless(String name) {
        return server().name(name).protocol(Protocol.VLESS)
                .address(name.toLowerCase(java.util.Locale.ROOT).replace(' ', '-') + ".example")
                .port(443).uuid(UUID);
    }

    /** Fills one {@link ServerConfig}. */
    public static final class Builder {

        private final ServerConfig server = new ServerConfig();

        private Builder() {
        }

        /** Sets the id, replacing the random one. */
        public Builder id(String id) {
            server.setId(id);
            return this;
        }

        /** Sets the display name. */
        public Builder name(String name) {
            server.setName(name);
            return this;
        }

        /** Sets the protocol. */
        public Builder protocol(Protocol protocol) {
            server.setProtocol(protocol);
            return this;
        }

        /** Sets the host. */
        public Builder address(String address) {
            server.setAddress(address);
            return this;
        }

        /** Sets the port. */
        public Builder port(int port) {
            server.setPort(port);
            return this;
        }

        /** Sets the credential field: UUID, password or private key by protocol. */
        public Builder uuid(String uuid) {
            server.setUuid(uuid);
            return this;
        }

        /** Sets the flow field (VLESS flow; WireGuard's local address). */
        public Builder flow(String flow) {
            server.setFlow(flow);
            return this;
        }

        /** Sets the encryption field (cipher; WireGuard's peer public key). */
        public Builder encryption(String encryption) {
            server.setEncryption(encryption);
            return this;
        }

        /** Marks the server as the picked one, or not. */
        public Builder active(boolean active) {
            server.setActive(active);
            return this;
        }

        /** Sets the subscription the server came from. */
        public Builder subscriptionId(String subscriptionId) {
            server.setSubscriptionId(subscriptionId);
            return this;
        }

        /** Sets Hysteria2's port list for port hopping. */
        public Builder serverPorts(String serverPorts) {
            server.setServerPorts(serverPorts);
            return this;
        }

        /** Sets a Shadowsocks SIP003 plugin and its options. */
        public Builder plugin(String plugin, String options) {
            server.setPlugin(plugin);
            server.setPluginOpts(options);
            return this;
        }

        /** Turns TLS on with {@code serverName} as its SNI. */
        public Builder tls(String serverName) {
            server.getTls().setEnabled(true);
            server.getTls().setServerName(serverName);
            return this;
        }

        /** Sets the ALPN list of the TLS settings. */
        public Builder alpn(String alpn) {
            server.getTls().setAlpn(alpn);
            return this;
        }

        /** Sets the uTLS fingerprint of the TLS settings. */
        public Builder fingerprint(String fingerprint) {
            server.getTls().setFingerprint(fingerprint);
            return this;
        }

        /** Turns certificate verification off, or on. */
        public Builder allowInsecure(boolean allowInsecure) {
            server.getTls().setAllowInsecure(allowInsecure);
            return this;
        }

        /** Turns REALITY on, with TLS, its SNI, public key and short id. */
        public Builder reality(String serverName, String publicKey, String shortId) {
            tls(serverName);
            server.getTls().setReality(true);
            server.getTls().setRealityPublicKey(publicKey);
            server.getTls().setRealityShortId(shortId);
            return this;
        }

        /** Sets the transport, with the path and host of WebSocket and its kin. */
        public Builder transport(TransportType type, String path, String host) {
            server.getTransport().setType(type);
            server.getTransport().setPath(path);
            server.getTransport().setHost(host);
            return this;
        }

        /** Sets gRPC as the transport, with its service name. */
        public Builder grpc(String serviceName) {
            server.getTransport().setType(TransportType.GRPC);
            server.getTransport().setServiceName(serviceName);
            return this;
        }

        /** Sets the gRPC service name without changing the transport. */
        public Builder serviceName(String serviceName) {
            server.getTransport().setServiceName(serviceName);
            return this;
        }

        /** Adds a header to the transport's request. */
        public Builder header(String name, String value) {
            server.getTransport().getHeaders().put(name, value);
            return this;
        }

        /**
         * The server, as filled.
         *
         * @return the server this builder filled
         */
        public ServerConfig build() {
            return server;
        }
    }
}
