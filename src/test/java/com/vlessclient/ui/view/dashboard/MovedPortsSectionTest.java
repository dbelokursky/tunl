package com.vlessclient.ui.view.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import com.vlessclient.service.ConnectionService.MovedPort;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the Dashboard says when a port moved for the session. */
class MovedPortsSectionTest {

    @Test
    void eachMovedPortTheUserMightUseIsNamed() {
        String text = MovedPortsSection.textFor(List.of(
                new MovedPort("SOCKS", 1080, 1082), new MovedPort("control", 9090, 9091)));

        assertThat(text).isEqualTo(I18n.get("dashboard.port.moved", "SOCKS", "1080", "1082"));
    }

    @Test
    void nothingMovedOrOnlyTheControlPortSaysNothing() {
        assertThat(MovedPortsSection.textFor(List.of())).isNull();
        assertThat(MovedPortsSection.textFor(List.of(new MovedPort("control", 9090, 9091))))
                .as("only the app talks to the control port")
                .isNull();
    }
}
