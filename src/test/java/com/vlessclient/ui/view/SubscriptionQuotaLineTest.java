package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.model.Subscription;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The provider's quota line under a subscription.
 *
 * <p>An expiry the provider sent is rendered as a date on every layout of the
 * row. An absurd one, already stored by a build that took any number, threw
 * there each time ({@code Instant.ofEpochSecond} holds nothing past about
 * 3.2e16), and the row never drew again.</p>
 */
class SubscriptionQuotaLineTest {

    @Test
    void anExpiryNoDateCanShowIsLeftOutOfTheLine() {
        Subscription sub = new Subscription();
        sub.setTotalBytes(1L << 30);
        sub.setExpiresAt(Long.MAX_VALUE);

        String line = SubscriptionsViewController.quotaLine(sub);

        assertThat(line)
                .as("the traffic part stays; no expiry is named")
                .isNotNull()
                .doesNotContain(I18n.get("subscriptions.expires", "").strip());
    }

    @Test
    void anOrdinaryExpiryIsStillShown() {
        Subscription sub = new Subscription();
        long expiry = 4_102_444_800L; // 2100-01-01
        sub.setExpiresAt(expiry);

        String date = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochSecond(expiry));
        assertThat(SubscriptionsViewController.quotaLine(sub)).contains(date);
    }
}
