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
 * A REALITY server whose link asks for {@code random} is sent
 * {@code randomized}.
 *
 * <p>The core answers {@code random} with one browser's hello for the whole
 * run, picked among Chrome, Firefox, Edge, Safari and iOS, and only Chrome's
 * carries the X25519MLKEM768 key share that Xray 26.9.8 requires of a REALITY
 * hello. Against such a server four core starts in five fail every
 * connection, until a restart picks again. The bundled core puts that share
 * into every randomized hello, so the hello stays random, as the link asks,
 * and Chrome's, which TSPU is reported to single out, is not sent in its
 * place.</p>
 */
class RealityFingerprintTest {

    private static final String REALITY_KEY = "Z84J2IelR9ch3k8VtlVhhs5ycBUlXA7wHBWcBrjqnAw";

    private final SingBoxConfigGenerator generator = new SingBoxConfigGenerator();
    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void realityAskingForRandomIsSentRandomized() throws Exception {
        JsonNode utls = utlsOf(reality("random"));

        assertThat(utls).isEqualTo(
                mapper.readTree("{\"enabled\": true, \"fingerprint\": \"randomized\"}"));
    }

    @Test
    void anySpellingOfRandomIsSentRandomized() throws Exception {
        for (String fingerprint : new String[] {"Random", " random ", "RANDOM"}) {
            assertThat(utlsOf(reality(fingerprint)).path("fingerprint").asString())
                    .as("fp=%s", fingerprint)
                    .isEqualTo("randomized");
        }
    }

    /**
     * What else a link names goes as it is: {@code randomized}, which the
     * bundled core now makes compliant, a browser, and Chrome's by default
     * when the link names none.
     */
    @Test
    void realityKeepsEveryOtherFingerprint() throws Exception {
        assertThat(sent("randomized")).isEqualTo("randomized");
        assertThat(sent("chrome")).isEqualTo("chrome");
        assertThat(sent("firefox")).isEqualTo("firefox");
        assertThat(sent("")).isEqualTo("chrome");
        assertThat(sent(null)).isEqualTo("chrome");
    }

    /**
     * Plain TLS keeps {@code random}: an ordinary TLS server takes any
     * hello, and only REALITY servers were seen refusing one.
     */
    @Test
    void plainTlsKeepsRandom() throws Exception {
        ServerConfig server = reality("random");
        server.getTls().setReality(false);

        assertThat(utlsOf(server).path("fingerprint").asString()).isEqualTo("random");
    }

    /**
     * The server keeps the value its link sent. A subscription refresh,
     * hourly by default, stores the provider's server again, so a changed
     * value would not have lasted anyway; the substitution happens where the
     * configuration is written, every time.
     */
    @Test
    void theServerKeepsTheFingerprintItsLinkSent() throws Exception {
        ServerConfig server = reality("random");

        utlsOf(server);

        assertThat(server.getTls().getFingerprint()).isEqualTo("random");
    }

    private String sent(String fingerprint) throws Exception {
        return utlsOf(reality(fingerprint)).path("fingerprint").asString();
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
