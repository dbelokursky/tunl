package com.vlessclient.ui.view.dashboard;

import com.vlessclient.app.I18n;
import com.vlessclient.service.ConnectionService.MovedPort;
import java.util.List;
import java.util.stream.Collectors;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

/**
 * The Dashboard's notice for local ports this session moved off, because
 * another program held the ones the user chose.
 *
 * <p>The move keeps the tunnel up, but a browser or a terminal set to the
 * chosen port reaches nothing until the user points it at the new one. The
 * notice lasts as long as the core it describes. The control port is left
 * out: only the app itself talks to it.</p>
 */
public final class MovedPortsSection {

    private final HBox banner;
    private final Label label;
    private ReadOnlyObjectProperty<List<MovedPort>> moved;

    /**
     * Creates the section over its controls; nothing is shown until {@link #bind}.
     *
     * @param banner the notice's container, hidden while there is nothing to say
     * @param label  the notice's text
     */
    public MovedPortsSection(HBox banner, Label label) {
        this.banner = banner;
        this.label = label;
    }

    /**
     * Follows the moved ports, and repaints on a language switch.
     *
     * @param moved the ports the running core moved off
     */
    public void bind(ReadOnlyObjectProperty<List<MovedPort>> moved) {
        this.moved = moved;
        moved.addListener((obs, oldList, newList) -> refresh());
        I18n.localeProperty().addListener((obs, oldLocale, newLocale) -> refresh());
        refresh();
    }

    private void refresh() {
        String text = textFor(moved != null ? moved.get() : List.of());
        label.setText(text != null ? text : "");
        banner.setVisible(text != null);
        banner.setManaged(text != null);
    }

    /**
     * The notice for these ports, one sentence each.
     *
     * @param ports the moved ports
     * @return the notice, or null when there is nothing to tell the user
     */
    static String textFor(List<MovedPort> ports) {
        if (ports == null) {
            return null;
        }
        String text = ports.stream()
                .filter(port -> !"control".equals(port.inbound()))
                .map(port -> I18n.get("dashboard.port.moved", port.inbound(),
                        String.valueOf(port.chosen()), String.valueOf(port.used())))
                .collect(Collectors.joining(" "));
        return text.isEmpty() ? null : text;
    }
}
