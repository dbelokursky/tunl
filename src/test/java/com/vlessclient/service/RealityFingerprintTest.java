package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.AppSettings;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.outbound.OutboundTags;
import com.vlessclient.testing.TestServers;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * A REALITY server greets as Chrome whatever random hello its link asks for.
 *
 * <p>The core draws {@code randomized} and {@code random} once per process:
 * every connection of a run sends the hello drawn at its start, and the next
 * run draws another. Xray 26.9.8 refuses a REALITY hello without an
 * X25519MLKEM768 key share, which a {@code randomized} draw carries half the
 * time and a {@code random} one only when it picks Chrome. A run that drew
 * one without it timed out on every connection until a restart drew again:
 * a subscription that sent {@code fp=randomized} left one start in two
 * without a tunnel.</p>
 */
class RealityFingerprintTest {

    private static final String REALITY_KEY = "Z84J2IelR9ch3k8VtlVhhs5ycBUlXA7wHBWcBrjqnAw";

    private final SingBoxConfigGenerator generator = new SingBoxConfigGenerator();
    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void realityAskingForARandomizedHelloGreetsAsChrome() throws Exception {
        JsonNode utls = utlsOf(reality("randomized"));

        assertThat(utls).isEqualTo(mapper.readTree("{\"enabled\": true, \"fingerprint\": \"chrome\"}"));
    }

    @Test
    void realityAskingForAnyRandomHelloGreetsAsChrome() throws Exception {
        for (String fingerprint : new String[] {"random", "Randomized", " random ", "", null}) {
            assertThat(utlsOf(reality(fingerprint)).path("fingerprint").asString())
                    .as("fp=%s", fingerprint)
                    .isEqualTo("chrome");
        }
    }

    /**
     * The server keeps the value its link sent. A subscription refresh,
     * hourly by default, stores the provider's server again, so a changed
     * value would not have lasted anyway; the substitution happens where the
     * configuration is written, every time.
     */
    @Test
    void theServerKeepsTheFingerprintItsLinkSent() throws Exception {
        ServerConfig server = reality("randomized");

        utlsOf(server);

        assertThat(server.getTls().getFingerprint()).isEqualTo("randomized");
    }

    /**
     * Plain TLS keeps the random hello its link asks for: an ordinary TLS
     * server takes any hello, and only REALITY servers were seen refusing one.
     */
    @Test
    void plainTlsKeepsTheRandomHelloItsLinkAsksFor() throws Exception {
        ServerConfig server = reality("randomized");
        server.getTls().setReality(false);

        assertThat(utlsOf(server).path("fingerprint").asString()).isEqualTo("randomized");
    }

    /**
     * A browser the link names is kept for REALITY too. It greets the same
     * way on every run, so a server that refuses it does so from the first
     * connect, where it shows; and the smaller hello can be the one that gets
     * through a network that drops Chrome's larger one.
     */
    @Test
    void realityKeepsABrowserItsLinkNames() throws Exception {
        assertThat(utlsOf(reality("firefox")).path("fingerprint").asString())
                .isEqualTo("firefox");
    }

    private ServerConfig reality(String fingerprint) {
        return TestServers.server()
                .id("reality")
                .name("reality")
                .protocol(Protocol.VLESS)
                .address("203.0.113.1")
                .port(443)
                .uuid("b1c2d3e4-f5a6-7890-abcd-ef1234567890")
                .flow("xtls-rprx-vision")
                .reality("www.microsoft.com", REALITY_KEY, "0123abcd")
                .fingerprint(fingerprint)
                .build();
    }

    /** The {@code utls} block of the server's own outbound in the generated configuration. */
    private JsonNode utlsOf(ServerConfig server) throws Exception {
        JsonNode root = mapper.readTree(generator.generate(server, new AppSettings()));
        for (JsonNode outbound : root.path("outbounds")) {
            if (OutboundTags.server(server).equals(outbound.path("tag").asString())) {
                return outbound.path("tls").path("utls");
            }
        }
        throw new AssertionError("no outbound for the server");
    }
}
