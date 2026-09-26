package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vlessclient.app.I18n;
import com.vlessclient.testing.FxToolkitExtension;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * The subscription URL inside another client's one-tap link. Pasted or typed
 * as it came, such a link was taken for a server that did not parse, or
 * saved as a subscription whose every refresh failed on the scheme.
 */
@ExtendWith(FxToolkitExtension.class)
class SubscriptionLinksTest {

    private static final String URL =
            "https://sub.example.com/api/sub/aBcD1234?client=clash&flag=1";
    private static final String ENCODED = URLEncoder.encode(URL, StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    @Test
    void aWebUrlIsItsOwnSubscriptionUrl() {
        assertThat(SubscriptionLinks.subscriptionUrl(" " + URL + " ")).contains(URL);
        assertThat(SubscriptionLinks.subscriptionUrl("http://sub.example.com/s"))
                .contains("http://sub.example.com/s");
    }

    @Test
    void happsAddLinkWrapsTheUrlPlainOrEncoded() {
        assertThat(SubscriptionLinks.subscriptionUrl("happ://add/" + URL)).contains(URL);
        assertThat(SubscriptionLinks.subscriptionUrl("HAPP://add/" + ENCODED)).contains(URL);
    }

    @Test
    void anInstallConfigLinkCarriesTheUrlInItsQuery() {
        assertThat(SubscriptionLinks.subscriptionUrl(
                "clash://install-config?url=" + ENCODED + "&name=Provider")).contains(URL);
        assertThat(SubscriptionLinks.subscriptionUrl(
                "v2rayng://install-config?name=Provider&url=" + ENCODED)).contains(URL);
    }

    @Test
    void singBoxsRemoteProfileLinkCarriesTheUrlBeforeItsName() {
        assertThat(SubscriptionLinks.subscriptionUrl(
                "sing-box://import-remote-profile?url=" + ENCODED + "#Provider")).contains(URL);
    }

    @Test
    void aServerLinkOrAWrapperAroundAnythingButTheWebIsNoSubscription() {
        assertThat(SubscriptionLinks.subscriptionUrl(
                "vless://11111111-2222-3333-4444-555555555555@198.51.100.7:443#A")).isEmpty();
        assertThat(SubscriptionLinks.subscriptionUrl("happ://add/ftp://sub.example.com/s"))
                .isEmpty();
        assertThat(SubscriptionLinks.subscriptionUrl("clash://install-config?name=x")).isEmpty();
        assertThat(SubscriptionLinks.subscriptionUrl("https://")).isEmpty();
        assertThat(SubscriptionLinks.subscriptionUrl(null)).isEmpty();
    }

    @Test
    void happsEncryptedLinksAreToldApart() {
        assertThat(SubscriptionLinks.encryptedForHapp("happ://crypt3/aBcDeF")).isTrue();
        assertThat(SubscriptionLinks.encryptedForHapp("happ://crypt/aBcDeF")).isTrue();
        assertThat(SubscriptionLinks.encryptedForHapp("happ://add/" + URL)).isFalse();
        assertThat(SubscriptionLinks.subscriptionUrl("happ://crypt3/aBcDeF")).isEmpty();
    }

    @Test
    void aSubscriptionAddedOrEditedFromAWrapperStoresTheUrlItWraps() {
        SubscriptionService subscriptions = TestSubscriptionServices.quiet(tempDir);
        subscriptions.addSubscription("Provider", "happ://add/" + URL);
        String id = subscriptions.getSubscriptions().getFirst().getId();
        assertThat(subscriptions.getSubscriptions().getFirst().getUrl()).isEqualTo(URL);

        subscriptions.updateSubscription(id, "Provider",
                "clash://install-config?url=" + URLEncoder.encode(
                        "https://other.example.net/s", StandardCharsets.UTF_8));
        assertThat(subscriptions.getSubscriptions().getFirst().getUrl())
                .isEqualTo("https://other.example.net/s");
    }

    @Test
    void aLinkEncryptedForHappIsRefusedWithTheReason() {
        SubscriptionService subscriptions = TestSubscriptionServices.quiet(tempDir);

        assertThatThrownBy(() -> subscriptions.addSubscription("Provider", "happ://crypt3/x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(I18n.get("subscriptions.link.happ.encrypted"));
        assertThat(subscriptions.getSubscriptions()).isEmpty();
    }
}
