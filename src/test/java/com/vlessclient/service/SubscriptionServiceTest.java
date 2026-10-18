package com.vlessclient.service;

import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.Subscription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionServiceTest {

    /** A self-signed Hysteria2 server as one-click installs hand it out, before its pin. */
    private static final String HY2_SELF_SIGNED =
            "hy2://password@203.0.113.7:443/?sni=bing.com&insecure=1";

    private static final String HY2_PIN =
            "dc19d6633637ad39aa5aee234d7eb11c49e217e9eaef988cacb6d83442f9cab7";

    @TempDir
    Path tempDir;

    private ConfigStore configStore;
    private ShareLinkParser shareLinkParser;
    private TestableSubscriptionService service;

    @BeforeEach
    void setUp() {
        configStore = new ConfigStore(tempDir);
        shareLinkParser = new ShareLinkParser();
        service = new TestableSubscriptionService(configStore, shareLinkParser, tempDir);
    }

    @Test
    void parseContent_base64EncodedLinks() {
        String links = "vless://uuid1@server1.com:443?security=tls&type=tcp#Server1\n"
                + "vless://uuid2@server2.com:443?security=tls&type=tcp#Server2\n";
        String base64 = Base64.getEncoder().encodeToString(links.getBytes(StandardCharsets.UTF_8));

        List<ServerConfig> servers = service.parseContent(base64).servers();

        assertThat(servers).hasSize(2);
        assertThat(servers.get(0).getAddress()).isEqualTo("server1.com");
        assertThat(servers.get(0).getPort()).isEqualTo(443);
        assertThat(servers.get(1).getAddress()).isEqualTo("server2.com");
    }

    /**
     * A list served from a file saved with a byte order mark starts with it,
     * and trimming the body kept it: the base64 check failed on it, and the
     * blob was read as one unreadable line. The first refresh imported
     * nothing, and every later one said the subscription had no links.
     */
    @Test
    void parseContent_aBase64BodySavedWithAByteOrderMark() {
        String links = "vless://uuid1@server1.com:443?security=tls&type=tcp#Server1\n"
                + "vless://uuid2@server2.com:443?security=tls&type=tcp#Server2\n";
        String body = "\uFEFF"
                + Base64.getEncoder().encodeToString(links.getBytes(StandardCharsets.UTF_8));

        SubscriptionService.ParsedContent parsed = service.parseContent(body);

        assertThat(parsed.servers()).extracting(ServerConfig::getAddress)
                .containsExactly("server1.com", "server2.com");
        assertThat(parsed.skipped()).isZero();
    }

    /**
     * Lines were split at \n and \r\n only. A list whose links end with a
     * bare CR, as old Mac files do, or with NEL or a Unicode line separator
     * read as one line: the first link's name swallowed the rest, the next
     * links' passwords with it, a name shown and logged on every connect, and
     * the other servers were missing.
     */
    @Test
    void parseContent_readsEveryKindOfLineBreak() {
        for (String separator : new String[] {"\r", "\u0085", " ", " ", "\r\n"}) {
            String links = "trojan://firstPw@a.example.com:443#A" + separator
                    + "trojan://secondPw@b.example.com:443#B";
            String base64 = Base64.getEncoder().encodeToString(
                    links.getBytes(StandardCharsets.UTF_8));
            for (String body : new String[] {links, base64}) {
                SubscriptionService.ParsedContent parsed = service.parseContent(body);

                assertThat(parsed.servers()).as("%04x", (int) separator.charAt(0))
                        .extracting(ServerConfig::getName).containsExactly("A", "B");
            }
        }
    }

    /**
     * A captive portal answers the hourly refresh with its login page, whose
     * lines quote addresses: {@code <html xmlns="http://…">},
     * {@code <link href="https://…">}. Each was read as a link in a protocol
     * this app lacks, so the row said the provider's links ask for what the
     * app does not support, instead of pointing at the portal.
     */
    @Test
    void refreshSubscription_aCaptivePortalPageIsNoLinksNotUnsupportedProtocols() {
        service.setFetchedContent("vless://uuid1@server1.com:443?security=tls&type=tcp#Server1\n");
        service.addSubscription("Sub", "https://example.com/sub");
        Subscription sub = service.getSubscriptions().get(0);

        service.setFetchedContent("""
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head><title>Wi-Fi login</title>
                <link rel="stylesheet" href="https://portal.example/style.css"></head>
                <body><a href="https://portal.example/login">Sign in to continue</a></body>
                </html>
                """);
        service.refreshSubscription(sub.getId());

        assertThat(sub.getLastErrorKey()).isEqualTo("subscriptions.error.no.links");
        assertThat(sub.getLeftOutLinks()).isZero();
        assertThat(configStore.getServers()).as("nothing is removed").hasSize(1);
    }

    /**
     * A line quoting the provider's site, as some lists end with, stands for
     * no server. Read as a link in a protocol named "support: https", it
     * showed on the row as a link left out.
     */
    @Test
    void parseContent_aLineQuotingAnAddressIsNeitherLeftOutNorUnreadable() {
        SubscriptionService.ParsedContent parsed = service.parseContent(
                "vless://uuid1@server1.com:443?security=tls&type=tcp#Good\n"
                        + "Support: https://t.me/provider\n");

        assertThat(parsed.servers()).hasSize(1);
        assertThat(parsed.skipped()).as("not an unreadable server either").isZero();
        assertThat(parsed.unsupportedSchemes()).isEmpty();
    }

    /**
     * The mark can stand inside the base64 too, when a provider encodes a
     * file saved with one. Only the body lost it, so the decoded list started
     * with it, and its first line, a comment, was no longer one: it counted
     * as an unreadable line, and every refresh was partial, recorded as an
     * error and removed no withdrawn server.
     */
    @Test
    void parseContent_aByteOrderMarkInsideTheBase64() {
        String list = "﻿#profile-title: Provider\n"
                + "vless://uuid1@server1.com:443?security=tls&type=tcp#Server1\n";
        String body = Base64.getEncoder().encodeToString(list.getBytes(StandardCharsets.UTF_8));

        SubscriptionService.ParsedContent parsed = service.parseContent(body);

        assertThat(parsed.servers()).extracting(ServerConfig::getAddress)
                .containsExactly("server1.com");
        assertThat(parsed.skipped()).as("the comment is no unreadable server").isZero();
    }

    /**
     * A web address on a line of its own, as a portal's page puts its sign-in
     * link or a list ends with its support page, has a scheme like any link,
     * so it was left out as an unsupported protocol named "https": the
     * portal's answer read as the provider's links asking for what the app
     * lacks, and a working list showed a link left out on its row.
     */
    @Test
    void refreshSubscription_aPortalPageWithABareAddressIsNoLinks() {
        service.setFetchedContent("vless://uuid1@server1.com:443?security=tls&type=tcp#Server1\n");
        service.addSubscription("Sub", "https://example.com/sub");
        Subscription sub = service.getSubscriptions().get(0);

        service.setFetchedContent(
                "Please sign in to the Wi-Fi network\nhttps://portal.example/login\n");
        service.refreshSubscription(sub.getId());

        assertThat(sub.getLastErrorKey()).isEqualTo("subscriptions.error.no.links");
        assertThat(sub.getLeftOutLinks()).isZero();
        assertThat(configStore.getServers()).as("nothing is removed").hasSize(1);
    }

    @Test
    void parseContent_aBareWebAddressIsNeitherLeftOutNorUnreadable() {
        SubscriptionService.ParsedContent parsed = service.parseContent(
                "vless://uuid1@server1.com:443?security=tls&type=tcp#Good\n"
                        + "https://t.me/provider\n"
                        + "HTTP://provider.example/help\n");

        assertThat(parsed.servers()).hasSize(1);
        assertThat(parsed.skipped()).isZero();
        assertThat(parsed.unsupportedSchemes()).isEmpty();
    }

    /**
     * A Hysteria2 server stored from an {@code insecure=1&pinSHA256=} link
     * before such links were refused: the pin was dropped, so what was stored
     * is what the same link gives without its pin, verification off. The
     * refusal is a link left out, not an unreadable line, so the next refresh
     * of a list that also holds servers this client runs removes it like a
     * withdrawn server, and the row names what it left out. This changes what
     * users of such servers see, and the release notes say so.
     */
    @Test
    void refreshSubscription_aServerStoredFromAPinnedLinkIsRemovedAndNamed() {
        String working = "vless://uuid1@server1.com:443?security=tls&type=tcp#Server1\n";
        service.setFetchedContent(working + HY2_SELF_SIGNED + "#Self-signed\n");
        service.addSubscription("Sub", "https://example.com/sub");
        Subscription sub = service.getSubscriptions().get(0);
        assertThat(configStore.getServers()).as("as the pin-dropping parser stored it")
                .extracting(server -> server.getTls().isAllowInsecure())
                .containsExactlyInAnyOrder(false, true);

        service.setFetchedContent(working + HY2_SELF_SIGNED + "&pinSHA256=" + HY2_PIN
                + "#Self-signed\n");
        service.refreshSubscription(sub.getId());

        assertThat(sub.hasLastError()).as("a refusal is no partial refresh").isFalse();
        assertThat(sub.getLeftOutLinks()).isEqualTo(1);
        assertThat(sub.getLeftOutSummary()).isEqualTo("certificate pin");
        assertThat(configStore.getServers()).extracting(ServerConfig::getAddress)
                .containsExactly("server1.com");
    }

    /**
     * The same server in a list that holds nothing else: like any list of
     * only links this client cannot run, it is kept, and the row says why the
     * refresh brought nothing.
     */
    @Test
    void refreshSubscription_aListOfOnlyPinnedLinksKeepsWhatItStored() {
        service.setFetchedContent(HY2_SELF_SIGNED + "#Self-signed\n");
        service.addSubscription("Sub", "https://example.com/sub");
        Subscription sub = service.getSubscriptions().get(0);

        service.setFetchedContent(HY2_SELF_SIGNED + "&pinSHA256=" + HY2_PIN + "#Self-signed\n");
        service.refreshSubscription(sub.getId());

        assertThat(sub.getLastErrorKey()).isEqualTo("subscriptions.error.unsupported");
        assertThat(sub.getLastErrorArgs()).containsExactly("certificate pin");
        assertThat(configStore.getServers()).extracting(ServerConfig::getProtocol)
                .containsExactly(Protocol.HYSTERIA2);
    }

    @Test
    void parseContent_plainTextLinks() {
        String content = "vless://uuid1@server1.com:443?security=tls&type=tcp#Server1\n"
                + "vless://uuid2@server2.com:8443?security=tls&type=tcp#Server2\n";

        List<ServerConfig> servers = service.parseContent(content).servers();

        assertThat(servers).hasSize(2);
        assertThat(servers.get(0).getName()).isEqualTo("Server1");
        assertThat(servers.get(0).getProtocol()).isEqualTo(Protocol.VLESS);
        assertThat(servers.get(1).getAddress()).isEqualTo("server2.com");
        assertThat(servers.get(1).getPort()).isEqualTo(8443);
    }

    @Test
    void parseContent_skipsInvalidLines() {
        String content = "vless://uuid1@server1.com:443?security=tls&type=tcp#Good\n"
                + "this is not a valid link\n"
                + "vless://uuid2@server2.com:443?security=tls&type=tcp#AlsoGood\n";

        SubscriptionService.ParsedContent parsed = service.parseContent(content);

        assertThat(parsed.servers()).hasSize(2);
        assertThat(parsed.servers().get(0).getName()).isEqualTo("Good");
        assertThat(parsed.servers().get(1).getName()).isEqualTo("AlsoGood");
        assertThat(parsed.skipped())
                .as("the count is what stops a refresh from deleting the servers "
                        + "those unread lines stood for")
                .isEqualTo(1);
    }

    @Test
    void parseContent_emptyContent() {
        assertThat(service.parseContent("").servers()).isEmpty();
        assertThat(service.parseContent(null).servers()).isEmpty();
        assertThat(service.parseContent("   ").servers()).isEmpty();
    }

    @Test
    void refreshSubscription_addsNewServersAndRemovesOld() {
        // Initial content with one server
        String initialContent = "vless://uuid1@server1.com:443?security=tls&type=tcp#Server1\n";
        service.setFetchedContent(Base64.getEncoder()
                .encodeToString(initialContent.getBytes(StandardCharsets.UTF_8)));

        service.addSubscription("TestSub", "https://example.com/sub");
        Subscription sub = service.getSubscriptions().get(0);
        assertThat(sub.getServerIds()).hasSize(1);
        assertThat(configStore.getServers()).hasSize(1);
        assertThat(configStore.getServers().get(0).getAddress()).isEqualTo("server1.com");

        // Refresh with different servers
        String updatedContent = "vless://uuid2@server2.com:443?security=tls&type=tcp#Server2\n"
                + "vless://uuid3@server3.com:443?security=tls&type=tcp#Server3\n";
        service.setFetchedContent(Base64.getEncoder()
                .encodeToString(updatedContent.getBytes(StandardCharsets.UTF_8)));

        service.refreshSubscription(sub.getId());

        // server1 removed, server2 and server3 added
        assertThat(sub.getServerIds()).hasSize(2);
        assertThat(configStore.getServers()).hasSize(2);
        assertThat(configStore.getServers())
                .extracting(ServerConfig::getAddress)
                .containsExactlyInAnyOrder("server2.com", "server3.com");
    }

    /**
     * A failed refresh used to vanish into the log: the row kept its old
     * timestamp and looked identical to a healthy subscription, so a dead URL
     * or an expired token went unnoticed indefinitely.
     */
    @Test
    void refreshSubscription_recordsWhyItFailedAndClearsItOnSuccess() {
        String content = "vless://uuid1@server1.com:443?security=tls&type=tcp#Server1\n";
        service.setFetchedContent(Base64.getEncoder()
                .encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        service.addSubscription("TestSub", "https://example.com/sub");
        Subscription sub = service.getSubscriptions().get(0);
        assertThat(sub.getLastError()).isNull();

        service.failFetchWith("HTTP 403");
        service.refreshSubscription(sub.getId());

        assertThat(sub.getLastError()).contains("403");
        // Persisted, so the failure is still visible after a restart.
        assertThat(new TestableSubscriptionService(configStore, shareLinkParser, tempDir)
                .getSubscriptions().get(0).getLastError()).contains("403");

        // A later success clears it — the row must not keep warning forever.
        service.setFetchedContent(Base64.getEncoder()
                .encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        service.refreshSubscription(sub.getId());
        assertThat(sub.getLastError()).isNull();
    }

    /**
     * The three failures the app words itself were stored as English
     * sentences -- in the file. They stayed English after the reader switched
     * the app to Russian, and they were written to disk in a language the
     * reader may not have. A technical reason (an HTTP status) still goes in
     * verbatim; only the app's own wording becomes a key.
     */
    @Test
    void aFailureTheAppWordsItselfIsStoredAsAKeyNotASentence() {
        service.setFetchedContent(Base64.getEncoder().encodeToString(
                "not a link at all\n".getBytes(StandardCharsets.UTF_8)));
        service.addSubscription("TestSub", "https://example.com/sub");
        Subscription sub = service.getSubscriptions().get(0);

        service.refreshSubscription(sub.getId());

        assertThat(sub.getLastErrorKey()).isEqualTo("subscriptions.error.no.links");
        assertThat(sub.getLastError())
                .as("no English sentence is written beside the key").isNull();
        assertThat(sub.hasLastError()).isTrue();
    }

    @Test
    void parseContent_unsupportedProtocolsAreNotUnreadableLines() {
        String content = "vless://uuid1@server1.com:443?security=tls&type=tcp#Good\n"
                + "tuic://uuid:pass@tuic.example:443?congestion_control=bbr#Tuic\n"
                + "# a comment the provider left in the list\n"
                + "anytls://pass@anytls.example:443#AnyTLS\n";

        SubscriptionService.ParsedContent parsed = service.parseContent(content);

        assertThat(parsed.servers()).hasSize(1);
        assertThat(parsed.skipped())
                .as("a protocol this client lacks is not a corrupted line")
                .isZero();
        assertThat(parsed.unsupportedSchemes()).containsExactly("tuic", "anytls");
    }

    /**
     * A provider that also hands out TUIC used to leave the subscription
     * permanently "failed": every unsupported link counted as an unreadable
     * one, which set the error and disabled removals on each refresh, so dead
     * servers were never pruned either.
     */
    @Test
    void refreshSubscription_unsupportedLinksNeitherFailItNorPinWithdrawnServers() {
        String initial = "vless://uuid1@server1.com:443?security=tls&type=tcp#Server1\n"
                + "vless://uuid2@server2.com:443?security=tls&type=tcp#Server2\n";
        service.setFetchedContent(initial);
        service.addSubscription("Mixed", "https://example.com/sub");
        Subscription sub = service.getSubscriptions().get(0);
        assertThat(configStore.getServers()).hasSize(2);

        service.setFetchedContent(
                "vless://uuid2@server2.com:443?security=tls&type=tcp#Server2\n"
                + "tuic://uuid:pass@tuic.example:443#Tuic\n");
        service.refreshSubscription(sub.getId());

        assertThat(sub.getLastError()).isNull();
        assertThat(configStore.getServers())
                .extracting(ServerConfig::getAddress)
                .as("server1 was withdrawn by the provider and must go")
                .containsExactly("server2.com");
    }

    /**
     * A list that mixes servers this client runs with links it cannot — XHTTP,
     * TUIC — kept the servers and dropped the rest with an INFO line in the
     * log, so the row counted fewer servers than the provider lists and said
     * nothing about why. The refresh now records what it left out.
     */
    @Test
    void refreshSubscription_aMixedListSaysWhatItLeftOut() {
        service.setFetchedContent("vless://uuid1@server1.com:443?security=tls&type=tcp#Server1\n");
        service.addSubscription("Sub", "https://example.com/sub");
        Subscription sub = service.getSubscriptions().get(0);
        assertThat(sub.getLeftOutLinks()).isZero();

        service.setFetchedContent("vless://uuid1@server1.com:443?security=tls&type=tcp#Server1\n"
                + "tuic://uuid:pass@tuic.example:443#Tuic\n"
                + "vless://11111111-2222-3333-4444-555555555555@x.example:443"
                + "?security=tls&type=xhttp&path=%2F#Xhttp\n");
        service.refreshSubscription(sub.getId());

        assertThat(sub.getLeftOutLinks()).isEqualTo(2);
        assertThat(sub.getLeftOutSummary()).contains("tuic");
        assertThat(sub.getLastError()).isNull();

        service.setFetchedContent("vless://uuid1@server1.com:443?security=tls&type=tcp#Server1\n");
        service.refreshSubscription(sub.getId());

        assertThat(sub.getLeftOutLinks()).isZero();
        assertThat(sub.getLeftOutSummary()).isEmpty();
    }

    @Test
    void refreshSubscription_onlyUnsupportedLinksSaysSoInsteadOfHintingAtExpiry() {
        service.setFetchedContent("vless://uuid1@server1.com:443?security=tls&type=tcp#Server1\n");
        service.addSubscription("Sub", "https://example.com/sub");
        Subscription sub = service.getSubscriptions().get(0);

        service.setFetchedContent("tuic://uuid:pass@tuic.example:443#Tuic\n");
        service.refreshSubscription(sub.getId());

        // Keyed now, so the row can render it in the reader's language; the
        // argument still names what was unsupported.
        assertThat(sub.getLastErrorKey()).isEqualTo("subscriptions.error.unsupported");
        assertThat(sub.getLastErrorArgs()).singleElement().asString().contains("tuic");
        assertThat(configStore.getServers())
                .as("nothing usable came back, so nothing is removed")
                .hasSize(1);
    }

    /**
     * The same list in the form panels serve by default, base64. When it held
     * nothing this client runs — an XHTTP-only VLESS list, say — the decoded
     * result was dropped for having no servers, the blob was read again as
     * one unreadable line, and the row suggested the subscription had
     * expired or a captive portal had answered.
     */
    @Test
    void refreshSubscription_aBase64ListOfOnlyUnsupportedLinksSaysSoToo() {
        service.setFetchedContent("vless://uuid1@server1.com:443?security=tls&type=tcp#Server1\n");
        service.addSubscription("Sub", "https://example.com/sub");
        Subscription sub = service.getSubscriptions().get(0);

        service.setFetchedContent(java.util.Base64.getEncoder().encodeToString((
                "tuic://uuid:pass@tuic.example:443#Tuic\n"
                        + "vless://11111111-2222-3333-4444-555555555555@x.example:443"
                        + "?security=tls&type=xhttp&path=%2F#Xhttp\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        service.refreshSubscription(sub.getId());

        assertThat(sub.getLastErrorKey()).isEqualTo("subscriptions.error.unsupported");
        assertThat(sub.getLastErrorArgs()).singleElement().asString().contains("tuic");
        assertThat(configStore.getServers())
                .as("nothing usable came back, so nothing is removed")
                .hasSize(1);
    }

    /**
     * A link the core would refuse is not a server this client can use, and it
     * is not a line the parser failed to read either. Counted as unreadable,
     * one xhttp link among twenty working ones kept every later refresh from
     * removing a withdrawn server; stored, a REALITY server with a broken key
     * was left out of every connect.
     */
    @Test
    void parseContent_linksTheCoreWouldRefuseAreUnsupportedNotUnreadable() {
        String content = "vless://uuid1@server1.com:443?security=tls&type=tcp#Good\n"
                + "vless://uuid2@server2.com:443?security=tls&type=xhttp&path=%2Fx#Xhttp\n"
                + "vless://uuid3@server3.com:443?security=reality&sni=example.com"
                + "&fp=chrome&pbk=pubkey123&sid=0123abcd#BrokenKey\n";

        SubscriptionService.ParsedContent parsed = service.parseContent(content);

        assertThat(parsed.servers()).extracting(ServerConfig::getName).containsExactly("Good");
        assertThat(parsed.skipped()).isZero();
        assertThat(parsed.unsupportedSchemes())
                .containsExactly("transport xhttp", "REALITY public key");
    }

    @Test
    void refreshSubscription_linksTheCoreWouldRefuseDoNotPinWithdrawnServers() {
        service.setFetchedContent("vless://uuid1@server1.com:443?security=tls&type=tcp#Server1\n"
                + "vless://uuid2@server2.com:443?security=tls&type=tcp#Server2\n");
        service.addSubscription("Mixed", "https://example.com/sub");
        Subscription sub = service.getSubscriptions().get(0);

        service.setFetchedContent("vless://uuid2@server2.com:443?security=tls&type=tcp#Server2\n"
                + "vless://uuid3@server3.com:443?security=tls&type=xhttp#Xhttp\n");
        service.refreshSubscription(sub.getId());

        assertThat(sub.getLastError()).isNull();
        assertThat(configStore.getServers())
                .extracting(ServerConfig::getAddress)
                .as("server1 was withdrawn by the provider and must go")
                .containsExactly("server2.com");
    }

    /**
     * servers.json, settings.json and routing.json all move an unreadable
     * file aside; subscriptions.json used to log and carry on, so the next
     * save overwrote the only copy — including the sealed URLs the keychain
     * entries are filed under.
     */
    @Test
    void corruptSubscriptionsFileIsQuarantinedRatherThanOverwritten() throws Exception {
        java.nio.file.Path file = tempDir.resolve("subscriptions.json");
        java.nio.file.Files.writeString(file, "{ this is not json");

        TestableSubscriptionService fresh =
                new TestableSubscriptionService(configStore, shareLinkParser, tempDir);

        assertThat(fresh.getSubscriptions()).isEmpty();
        assertThat(java.nio.file.Files.exists(file)).isFalse();
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(tempDir)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .anyMatch(name -> name.startsWith("subscriptions.json.corrupt-"));
        }
    }

    @Test
    void applyUserInfo_readsTheProvidersQuotaHeader() {
        Subscription sub = new Subscription();
        java.net.http.HttpHeaders headers = java.net.http.HttpHeaders.of(
                java.util.Map.of("Subscription-Userinfo", List.of(
                        "upload=1024; download=2048; total=1073741824; expire=1767225600")),
                (name, value) -> true);

        SubscriptionService.applyUserInfo(sub, headers);

        assertThat(sub.getUploadBytes()).isEqualTo(1024);
        assertThat(sub.getDownloadBytes()).isEqualTo(2048);
        assertThat(sub.getTotalBytes()).isEqualTo(1073741824L);
        assertThat(sub.getExpiresAt()).isEqualTo(1767225600L);
    }

    @Test
    void applyUserInfo_ignoresNoiseAndKeepsWhatItAlreadyKnows() {
        Subscription sub = new Subscription();
        sub.setTotalBytes(500);
        java.net.http.HttpHeaders noise = java.net.http.HttpHeaders.of(
                java.util.Map.of("subscription-userinfo", List.of("total=lots; expire=; junk")),
                (name, value) -> true);
        java.net.http.HttpHeaders none = java.net.http.HttpHeaders.of(
                java.util.Map.of(), (name, value) -> true);

        SubscriptionService.applyUserInfo(sub, noise);
        SubscriptionService.applyUserInfo(sub, none);

        assertThat(sub.getTotalBytes()).isEqualTo(500);
        assertThat(sub.getExpiresAt()).isZero();
    }

    /**
     * "expire" is seconds since 1970, and the Subscriptions page renders it
     * as a date on every layout of the row. A value no date can hold threw
     * there each time, so one header from a provider — or from anyone on the
     * path of a plain-http refresh — broke the page until the next answer.
     */
    @Test
    void applyUserInfo_ignoresAnExpiryNoDateCanShow() {
        Subscription sub = new Subscription();
        sub.setExpiresAt(1767225600L);

        for (String absurd : List.of("9223372036854775807", "253402300800", "-1")) {
            SubscriptionService.applyUserInfo(sub, java.net.http.HttpHeaders.of(
                    java.util.Map.of("subscription-userinfo", List.of("expire=" + absurd)),
                    (name, value) -> true));
            assertThat(sub.getExpiresAt()).as("after expire=%s", absurd).isEqualTo(1767225600L);
        }

        SubscriptionService.applyUserInfo(sub, java.net.http.HttpHeaders.of(
                java.util.Map.of("subscription-userinfo", List.of("expire=0")),
                (name, value) -> true));
        assertThat(sub.getExpiresAt()).as("0: the plan does not expire").isZero();
    }

    /**
     * Changing a URL used to mean deleting the subscription, and its servers,
     * and adding it again.
     */
    @Test
    void updateSubscription_renamesRepointsAndRefreshes() {
        service.setFetchedContent("vless://uuid1@old.example:443?security=tls&type=tcp#Old\n");
        service.addSubscription("Before", "https://example.com/before");
        Subscription sub = service.getSubscriptions().get(0);
        assertThat(configStore.getServers().get(0).getName()).isEqualTo("[Before] Old");

        service.setFetchedContent("vless://uuid2@new.example:443?security=tls&type=tcp#New\n");
        service.updateSubscription(sub.getId(), "After", "https://example.com/after");

        assertThat(sub.getName()).isEqualTo("After");
        assertThat(sub.getUrl()).isEqualTo("https://example.com/after");
        assertThat(configStore.getServers())
                .extracting(ServerConfig::getName)
                .containsExactly("[After] New");
        assertThat(new TestableSubscriptionService(configStore, shareLinkParser, tempDir)
                .getSubscriptions().get(0).getUrl())
                .as("the new URL is persisted, sealed like the original")
                .isEqualTo("https://example.com/after");
    }

    @Test
    void removeSubscription_removesAssociatedServers() {
        String content = "vless://uuid1@server1.com:443?security=tls&type=tcp#Server1\n"
                + "vless://uuid2@server2.com:443?security=tls&type=tcp#Server2\n";
        service.setFetchedContent(Base64.getEncoder()
                .encodeToString(content.getBytes(StandardCharsets.UTF_8)));

        service.addSubscription("TestSub", "https://example.com/sub");
        assertThat(configStore.getServers()).hasSize(2);

        String subId = service.getSubscriptions().get(0).getId();
        service.removeSubscription(subId);

        assertThat(service.getSubscriptions()).isEmpty();
        assertThat(configStore.getServers()).isEmpty();
    }

    @Test
    void persistenceRoundTrip_savesAndLoadsSubscriptions() {
        String content = "vless://uuid1@server1.com:443?security=tls&type=tcp#Server1\n";
        service.setFetchedContent(Base64.getEncoder()
                .encodeToString(content.getBytes(StandardCharsets.UTF_8)));

        service.addSubscription("PersistTest", "https://example.com/sub");
        assertThat(service.getSubscriptions()).hasSize(1);

        // Create a new instance that loads from the same directory
        TestableSubscriptionService reloaded =
                new TestableSubscriptionService(configStore, shareLinkParser, tempDir);
        assertThat(reloaded.getSubscriptions()).hasSize(1);

        Subscription loaded = reloaded.getSubscriptions().get(0);
        assertThat(loaded.getName()).isEqualTo("PersistTest");
        assertThat(loaded.getUrl()).isEqualTo("https://example.com/sub");
        assertThat(loaded.getServerIds()).hasSize(1);
        assertThat(loaded.getLastRefreshedAt()).isGreaterThan(0);
    }

    @Test
    void addSubscription_prefixesServerNamesWithSubscriptionName() {
        String content = "vless://uuid1@server1.com:443?security=tls&type=tcp#OriginalName\n";
        service.setFetchedContent(Base64.getEncoder()
                .encodeToString(content.getBytes(StandardCharsets.UTF_8)));

        service.addSubscription("MySub", "https://example.com/sub");

        assertThat(configStore.getServers()).hasSize(1);
        assertThat(configStore.getServers().get(0).getName()).isEqualTo("[MySub] OriginalName");
    }

    @Test
    void parseContent_multipleProtocols() {
        String content = "vless://uuid1@vless.com:443?security=tls&type=tcp#VlessServer\n"
                + "trojan://pass@trojan.com:443?security=tls&type=tcp#TrojanServer\n";

        List<ServerConfig> servers = service.parseContent(content).servers();

        assertThat(servers).hasSize(2);
        assertThat(servers.get(0).getProtocol()).isEqualTo(Protocol.VLESS);
        assertThat(servers.get(1).getProtocol()).isEqualTo(Protocol.TROJAN);
    }

    /**
     * A testable subclass that overrides fetchContent to return
     * pre-configured content instead of making HTTP calls.
     */
    private static class TestableSubscriptionService extends SubscriptionService {

        private String fetchedContent = "";
        private RuntimeException fetchFailure;

        TestableSubscriptionService(ConfigStore configStore, ShareLinkParser shareLinkParser,
                                     Path dataDir) {
            super(configStore, shareLinkParser, dataDir,
                    java.net.http.HttpClient.newHttpClient());
        }

        void setFetchedContent(String content) {
            this.fetchedContent = content;
            this.fetchFailure = null;
        }

        /** Makes the next fetch fail, the way a dead URL or bad token would. */
        void failFetchWith(String message) {
            this.fetchFailure = new RuntimeException(message);
        }

        @Override
        String fetchContent(String url) {
            if (fetchFailure != null) {
                throw fetchFailure;
            }
            return fetchedContent;
        }
    }

    @Test
    void parseContent_urlSafeBase64() {
        // A non-ASCII server name is what puts a byte pattern into the payload
        // that encodes to '+' in the standard alphabet and '-' in the URL-safe
        // one. Providers do ship names like this, and the standard decoder
        // rejects the result outright - so without the fallback the whole
        // subscription reads as unparseable and imports nothing.
        String content = "vless://uuid1@server1.example:443?security=tls&type=tcp#\u0422\u043e\u043a\u0438\u043e\n";
        String encoded = Base64.getUrlEncoder()
                .encodeToString(content.getBytes(StandardCharsets.UTF_8));
        assertThat(encoded)
                .as("the fixture must actually exercise the URL-safe alphabet")
                .containsAnyOf("-", "_");

        List<ServerConfig> servers = service.parseContent(encoded).servers();

        assertThat(servers).hasSize(1);
        assertThat(servers.get(0).getName()).isEqualTo("\u0422\u043e\u043a\u0438\u043e");
    }
}
