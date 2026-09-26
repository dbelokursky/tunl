package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.model.Subscription;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The line under a subscription's URL says what its last list held that this
 * client cannot run, beside the servers it counts: without it the row showed
 * fewer servers than the provider lists and no reason.
 */
class SubscriptionStatusLineTest {

    @Test
    void theLineSaysHowManyLinksWereLeftOutAndWhatTheyAskFor() {
        Subscription sub = new Subscription();
        sub.setServerIds(new java.util.ArrayList<>(List.of("a", "b")));
        sub.setLeftOut(3, "transport xhttp, tuic");

        String line = SubscriptionsViewController.statusLine(sub);

        assertThat(line).startsWith(I18n.plural("subscriptions.servers", 2) + " · ")
                .contains(I18n.plural("subscriptions.left.out", 3) + " (transport xhttp, tuic)");
    }

    @Test
    void nothingLeftOutAddsNothing() {
        Subscription sub = new Subscription();
        sub.setLeftOut(0, "ignored");

        assertThat(SubscriptionsViewController.statusLine(sub)).isEqualTo(
                I18n.plural("subscriptions.servers", 0) + " · "
                        + I18n.get("subscriptions.never.refreshed"));
    }
}
