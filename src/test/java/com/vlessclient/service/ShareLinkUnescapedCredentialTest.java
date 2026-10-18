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
                "trojan://s3cr3t@Ta.il#More@tr.example.com:443#Remark",
                "trojan://s3cr3t@Tail:9#More@tr.example.com:443#Remark",
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

    @Test
    void aNameHoldingAnAddressAfterARealServerIsStillAName() {
        ServerConfig vless = parser.parseForImport("vless://" + UUID
                + "@real.example.com:443?security=tls#Me@backup.example:8443");
        ServerConfig trojan = parser.parseForImport("trojan://pw@1.2.3.4:443#user@5.6.7.8:22");
        ServerConfig handle = parser.parseForImport("trojan://pw@tr.example.com:443#%40channel");
        ServerConfig hopping = parser.parseForImport(
                "hy2://pw@hy.example.com:443,20000-30000#a@b.example:1");

        assertThat(vless.getAddress()).isEqualTo("real.example.com");
        assertThat(vless.getName()).isEqualTo("Me@backup.example:8443");
        assertThat(trojan.getAddress()).isEqualTo("1.2.3.4");
        assertThat(handle.getName()).isEqualTo("@channel");
        assertThat(hopping.getAddress()).isEqualTo("hy.example.com");
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
