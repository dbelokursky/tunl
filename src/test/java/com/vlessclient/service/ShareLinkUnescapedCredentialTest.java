package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vlessclient.app.I18n;
import com.vlessclient.model.ServerConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A password written into a link without percent-encoding, holding an @ and
 * then a #, ? or /: the link is cut at the first of those, so the authority
 * ended at the password's own @, a part of the password was read as the
 * host, and the rest, the real host with it, as the name, the query or the
 * path. trojan://s3cr3t@Tail#More@tr.example.com:443#Trojan was stored with
 * the address Tail, shown in the list, sent to the resolver and returned by
 * MCP, and the name More@tr.example.com:443#Trojan, logged on every connect.
 * Such a link is refused without repeating it; a name that holds an address
 * after a real authority is still read as a name.
 */
class ShareLinkUnescapedCredentialTest {

    private static final String UUID = "b831381d-6324-4d53-ad4f-8cda48b30811";

    private final ShareLinkParser parser = new ShareLinkParser();

    @Test
    void aPasswordWhoseTailLooksLikeTheServerIsRefusedWithoutBeingRepeated() {
        List<String> links = List.of(
                "trojan://s3cr3t@Tail#More@tr.example.com:443#Trojan",
                "hy2://s3cr3t@Tail#More@hy.example.com:443#Hy2",
                "vless://s3cr3t@Tail#More@v.example.com:443?security=tls#V",
                "trojan://s3cr3t@Tail#More@tr.example.com:443?security=tls&type=tcp#Remark",
                "trojan://s3cr3t@Tail?More@tr.example.com:443#Remark",
                "trojan://s3cr3t@Tail/More@tr.example.com:443#Remark",
                "vless://s3cr3t@Tail?More@1.2.3.4:443",
                "hysteria2://s3cr3t@Tail?More@[2001:db8::1]:443#Hy2");
        for (String link : links) {
            assertThatThrownBy(() -> parser.parseForImport(link)).as(link)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(I18n.get("import.credential.unescaped"))
                    .satisfies(e -> assertThat(e.getMessage())
                            .doesNotContain("s3cr3t").doesNotContain("Tail")
                            .doesNotContain("More"));
        }
    }

    /**
     * Read as a name after an authority that is a server: a name with a dot,
     * an IPv6 literal or localhost, with its port or without one, as VLESS,
     * Trojan and Hysteria2 default to 443; any name with its port; and any
     * authority after a UUID, which holds no @. Each of these was refused
     * once the check came in, though it had imported correctly, and a
     * subscription holding one became partial at every refresh.
     */
    @Test
    void aNameHoldingAnAddressAfterARealServerIsStillAName() {
        String[][] links = {
            {"vless://" + UUID + "@real.example.com:443?security=tls#Me@backup.example:8443",
                "real.example.com", "443", "Me@backup.example:8443"},
            {"trojan://pw@1.2.3.4:443#user@5.6.7.8:22", "1.2.3.4", "443", "user@5.6.7.8:22"},
            {"trojan://pw@tr.example.com:443#%40channel", "tr.example.com", "443", "@channel"},
            {"hy2://pw@hy.example.com:443,20000-30000#a@b.example:1",
                "hy.example.com", "443", "a@b.example:1"},
            {"trojan://pw@tr.example.com?security=tls&sni=tr.example.com#Me@backup.example:8443",
                "tr.example.com", "443", "Me@backup.example:8443"},
            {"hy2://pw@hy.example.com/?sni=hy.example.com#Me@backup.example:8443",
                "hy.example.com", "443", "Me@backup.example:8443"},
            {"vless://" + UUID + "@real.example.com?security=tls&sni=real.example.com"
                + "#Me@backup.example:8443", "real.example.com", "443", "Me@backup.example:8443"},
            {"trojan://pw@tr.example.com:#Me@backup.example:8443",
                "tr.example.com", "443", "Me@backup.example:8443"},
            {"trojan://pw@[2001:db8::1]?security=tls#a@b.example:1",
                "[2001:db8::1]", "443", "a@b.example:1"},
            {"trojan://pw@[fe80::1%25eth0]:443?security=tls#x@y.example:1",
                "[fe80::1%25eth0]", "443", "x@y.example:1"},
            {"hy2://pw@localhost?insecure=1#a@b.example:1", "localhost", "443", "a@b.example:1"},
            {"hy2://pw@myserver:443?insecure=1#Me@backup.example:8443",
                "myserver", "443", "Me@backup.example:8443"},
            {"hy2://pw@myserver:443,20000-30000#a@b.example:1", "myserver", "443", "a@b.example:1"},
            {"vless://" + UUID + "@myserver:443?security=tls&sni=example.com"
                + "#Me@backup.example:8443", "myserver", "443", "Me@backup.example:8443"},
            {"vless://" + UUID + "@nas#Me@backup.example:8443", "nas", "443",
                "Me@backup.example:8443"},
            {"trojan://" + UUID + "@nas?security=tls#Me@backup.example:8443", "nas", "443",
                "Me@backup.example:8443"}};
        for (String[] link : links) {
            ServerConfig server = parser.parseForImport(link[0]);

            assertThat(server.getAddress()).as(link[0]).isEqualTo(link[1]);
            assertThat(server.getPort()).as(link[0]).isEqualTo(Integer.parseInt(link[2]));
            assertThat(server.getName()).as(link[0]).isEqualTo(link[3]);
            assertThat(server.getUuid()).as(link[0]).isIn("pw", UUID);
        }
    }

    /**
     * What the check cannot tell from a server, and so reads as written, as
     * any parser of URIs does: a part of a password with a dot, which stands
     * where a server's name without a port would, and one with a port. A
     * password with an @ and then a name with no dot and no port, the shape
     * of the links that made the finding, is the one refused.
     */
    @Test
    void aPasswordPartShapedLikeAServerIsReadAsWritten() {
        ServerConfig dotted = parser.parseForImport(
                "trojan://s3cr3t@Ta.il#More@tr.example.com:443#Remark");
        ServerConfig withPort = parser.parseForImport(
                "trojan://s3cr3t@Tail:9#More@tr.example.com:443#Remark");

        assertThat(dotted.getAddress()).isEqualTo("Ta.il");
        assertThat(withPort.getAddress()).isEqualTo("Tail");
        assertThat(withPort.getPort()).isEqualTo(9);
    }

    @Test
    void thePasswordWrittenPercentEncodedIsRead() {
        ServerConfig server = parser.parseForImport(
                "trojan://s3cr3t%40Tail%23More@tr.example.com:443#Trojan");

        assertThat(server.getUuid()).isEqualTo("s3cr3t@Tail#More");
        assertThat(server.getAddress()).isEqualTo("tr.example.com");
        assertThat(server.getName()).isEqualTo("Trojan");
    }
}
