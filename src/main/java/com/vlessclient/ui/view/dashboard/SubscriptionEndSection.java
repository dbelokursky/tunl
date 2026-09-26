package com.vlessclient.ui.view.dashboard;

import com.vlessclient.app.I18n;
import com.vlessclient.model.Subscription;
import com.vlessclient.ui.view.SubscriptionsViewController;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

/**
 * The Dashboard's notice for subscriptions near their end: the plan expires
 * within three days or has expired, or has used 90 % of its traffic.
 *
 * <p>The provider's expiry and quota were shown only on the Subscriptions
 * page, beside the server count, and nothing warned before a plan ran out:
 * the tunnel then stopped carrying, which looked like a broken server. A
 * refresh changes a subscription in place, with no event, so the notice is
 * read again whenever the Dashboard comes on screen, and when a subscription
 * is added or removed: it matters when someone looks.</p>
 */
public final class SubscriptionEndSection {

    /** How long before the end the notice starts. */
    static final Duration AHEAD = Duration.ofDays(3);

    /** The share of the plan's traffic that brings the notice. */
    static final double TRAFFIC_SHARE = 0.9;

    private final HBox banner;
    private final Label label;
    private ObservableList<Subscription> subscriptions = FXCollections.emptyObservableList();

    /**
     * Creates the section over its controls; nothing is shown until {@link #bind}.
     *
     * @param banner the notice's container, hidden while there is nothing to say
     * @param label  the notice's text
     */
    public SubscriptionEndSection(HBox banner, Label label) {
        this.banner = banner;
        this.label = label;
    }

    /**
     * Follows the subscriptions, reads them again when the card comes on
     * screen, and repaints on a language switch.
     *
     * @param subscriptions the user's subscriptions
     * @param onScreen      whether the Dashboard is on screen
     */
    public void bind(ObservableList<Subscription> subscriptions,
                     ObservableValue<Boolean> onScreen) {
        this.subscriptions = subscriptions;
        subscriptions.addListener((ListChangeListener<Subscription>) change -> refresh());
        onScreen.addListener((obs, wasOnScreen, isOnScreen) -> {
            if (Boolean.TRUE.equals(isOnScreen)) {
                refresh();
            }
        });
        I18n.localeProperty().addListener((obs, oldLocale, newLocale) -> refresh());
        refresh();
    }

    private void refresh() {
        String text = textFor(List.copyOf(subscriptions), Instant.now());
        label.setText(text != null ? text : "");
        banner.setVisible(text != null);
        banner.setManaged(text != null);
    }

    /**
     * The notice for these subscriptions at {@code now}, one sentence each.
     *
     * @param subscriptions the subscriptions
     * @param now           the moment to measure the plans against
     * @return the notice, or null when no plan is near its end
     */
    static String textFor(List<Subscription> subscriptions, Instant now) {
        List<String> notices = new ArrayList<>();
        for (Subscription sub : subscriptions) {
            String name = SubscriptionsViewController.shownName(sub);
            Optional<Instant> expiry = sub.expiry();
            if (expiry.isPresent() && !expiry.get().isAfter(now)) {
                notices.add(I18n.get("dashboard.subscription.ended", name));
            } else if (expiry.isPresent() && expiry.get().isBefore(now.plus(AHEAD))) {
                Duration left = Duration.between(now, expiry.get());
                if (left.compareTo(Duration.ofDays(1)) < 0) {
                    notices.add(I18n.get("dashboard.subscription.ends.soon", name));
                } else {
                    // Days begun, so two and a half read as three.
                    long days = left.plusDays(1).minusNanos(1).toDays();
                    String daysLeft = I18n.plural("dashboard.subscription.days", days);
                    notices.add(I18n.get("dashboard.subscription.ends", name, daysLeft));
                }
            }
            long total = sub.getTotalBytes();
            long used = Math.max(0, sub.getUploadBytes()) + Math.max(0, sub.getDownloadBytes());
            if (total > 0 && used >= total * TRAFFIC_SHARE) {
                long percent = Math.min(100, Math.round(used * 100.0 / total));
                notices.add(I18n.get("dashboard.subscription.traffic", name,
                        String.valueOf(percent)));
            }
        }
        return notices.isEmpty() ? null : String.join(" ", notices);
    }
}
