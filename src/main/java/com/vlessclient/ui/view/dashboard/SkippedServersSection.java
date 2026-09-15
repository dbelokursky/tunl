package com.vlessclient.ui.view.dashboard;

import com.vlessclient.app.I18n;
import com.vlessclient.service.ConnectionService.SkippedServer;
import java.util.ArrayList;
import java.util.List;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

/**
 * The Dashboard's notice for servers the core refused to build, which the
 * connection went ahead without.
 *
 * <p>Leaving them out is what keeps one broken subscription entry from
 * blocking every other server, but doing it silently would leave the user
 * wondering why a server never gets picked. The notice lasts as long as the
 * core it describes: the service empties the list when the core stops.</p>
 */
public final class SkippedServersSection {

    /** How many refused servers the notice names before it only counts the rest. */
    static final int NAMED = 3;

    private final HBox banner;
    private final Label label;
    private ReadOnlyObjectProperty<List<SkippedServer>> skipped;

    /**
     * Creates the section over its controls; nothing is shown until {@link #bind}.
     *
     * @param banner the notice's container, hidden while there is nothing to say
     * @param label  the notice's text
     */
    public SkippedServersSection(HBox banner, Label label) {
        this.banner = banner;
        this.label = label;
    }

    /**
     * Follows the list of refused servers, and repaints on a language switch.
     *
     * @param skipped the servers the running core was started without
     */
    public void bind(ReadOnlyObjectProperty<List<SkippedServer>> skipped) {
        this.skipped = skipped;
        skipped.addListener((obs, oldList, newList) -> refresh());
        I18n.localeProperty().addListener((obs, oldLocale, newLocale) -> refresh());
        refresh();
    }

    private void refresh() {
        String text = textFor(skipped != null ? skipped.get() : List.of());
        label.setText(text != null ? text : "");
        banner.setVisible(text != null);
        banner.setManaged(text != null);
    }

    /**
     * The notice for these servers: each named with the core's reason, up to
     * {@link #NAMED} of them, then a count of the rest.
     *
     * @param servers the refused servers
     * @return the notice, or null when there is nothing to say
     */
    static String textFor(List<SkippedServer> servers) {
        if (servers == null || servers.isEmpty()) {
            return null;
        }
        List<String> entries = new ArrayList<>();
        for (SkippedServer server : servers.subList(0, Math.min(NAMED, servers.size()))) {
            entries.add(I18n.get("dashboard.skipped.entry", server.name(), server.reason()));
        }
        if (servers.size() > NAMED) {
            entries.add(I18n.get("dashboard.skipped.more",
                    String.valueOf(servers.size() - NAMED)));
        }
        return I18n.get("dashboard.skipped.servers",
                String.valueOf(servers.size()), String.join("; ", entries));
    }
}
