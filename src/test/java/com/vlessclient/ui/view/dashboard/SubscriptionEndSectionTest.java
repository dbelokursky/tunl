package com.vlessclient.ui.view.dashboard;

import com.vlessclient.app.I18n;
import com.vlessclient.model.Subscription;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Dashboard warns before a subscription's plan runs out. Its expiry and
 * quota were shown only on the Subscriptions page, and a tunnel that then
 * stopped carrying looked like a broken server (item 2.1 of the 2026-09-25
 * review).
 */
class SubscriptionEndSectionTest {

    private static final Instant NOW = Instant.parse("2026-09-25T12:00:00Z");

    private static Subscription named(String name) {
        Subscription sub = new Subscription();
        sub.setName(name);
        return sub;
    }

    private static Subscription expiringIn(String name, Duration left) {
        Subscription sub = named(name);
        sub.setExpiresAt(NOW.plus(left).getEpochSecond());
        return sub;
    }

    @Test
    void aPlanEndingInTheNextThreeDaysIsNamedWithTheDaysLeft() {
        assertThat(SubscriptionEndSection.textFor(
                List.of(expiringIn("Nordic", Duration.ofHours(60))), NOW))
                .isEqualTo(I18n.get("dashboard.subscription.ends", "Nordic",
                        I18n.plural("dashboard.subscription.days", 3)));
    }

    @Test
    void lessThanADayLeftSaysSo() {
        assertThat(SubscriptionEndSection.textFor(
                List.of(expiringIn("Nordic", Duration.ofHours(5))), NOW))
                .isEqualTo(I18n.get("dashboard.subscription.ends.soon", "Nordic"));
    }

    @Test
    void anEndedPlanSaysItsServersMayStop() {
        assertThat(SubscriptionEndSection.textFor(
                List.of(expiringIn("Backup", Duration.ofDays(-2))), NOW))
                .isEqualTo(I18n.get("dashboard.subscription.ended", "Backup"));
    }

    @Test
    void ninetyPercentOfTheTrafficBringsTheNotice() {
        Subscription sub = named("Nordic");
        sub.setTotalBytes(100);
        sub.setUploadBytes(5);
        sub.setDownloadBytes(90);

        assertThat(SubscriptionEndSection.textFor(List.of(sub), NOW))
                .isEqualTo(I18n.get("dashboard.subscription.traffic", "Nordic", "95"));
    }

    @Test
    void aPlanFarFromItsEndSaysNothing() {
        Subscription roomy = expiringIn("Nordic", Duration.ofDays(10));
        roomy.setTotalBytes(100);
        roomy.setDownloadBytes(40);

        assertThat(SubscriptionEndSection.textFor(List.of(roomy, named("Plain")), NOW)).isNull();
    }
}
