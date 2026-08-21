package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.TunnelHealth;
import com.vlessclient.model.TunnelStatus;
import java.awt.Color;
import java.awt.Image;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

/**
 * The menu bar is the only status many users ever look at, so the colour it
 * shows has to answer "is my traffic going through?" rather than "did the
 * process start?".
 */
class TrayIconStatusIconTest {

    private static final Color GREEN = new Color(46, 204, 113);
    private static final Color AMBER = new Color(243, 156, 18);
    private static final Color RED = new Color(231, 76, 60);
    private static final Color GREY = new Color(149, 165, 166);

    /** The fill at the centre of the icon's dot. */
    private static Color dotColorFor(ConnectionState state, TunnelHealth health) {
        Image image = TrayIconService.createStatusIcon(TunnelStatus.of(state, health));
        assertThat(image).isInstanceOf(BufferedImage.class);
        BufferedImage rendered = (BufferedImage) image;
        int argb = rendered.getRGB(rendered.getWidth() / 2, rendered.getHeight() / 2);
        return new Color(argb, true);
    }

    @Test
    void aRunningCoreWithNoTrafficIsNotGreen() {
        // The reported bug: sing-box had started, every probe was failing, and
        // the icon was as green as a working tunnel.
        assertThat(dotColorFor(ConnectionState.CONNECTED, TunnelHealth.BROKEN))
                .isEqualTo(RED);
    }

    @Test
    void anUnprovenTunnelIsAmberUntilTheProbesAnswer() {
        assertThat(dotColorFor(ConnectionState.CONNECTED, TunnelHealth.CHECKING))
                .isEqualTo(AMBER);
        assertThat(dotColorFor(ConnectionState.CONNECTED, TunnelHealth.DEGRADED))
                .isEqualTo(AMBER);
        assertThat(dotColorFor(ConnectionState.CONNECTED, TunnelHealth.UNKNOWN))
                .isEqualTo(AMBER);
    }

    @Test
    void aVerifiedTunnelIsGreen() {
        assertThat(dotColorFor(ConnectionState.CONNECTED, TunnelHealth.HEALTHY))
                .isEqualTo(GREEN);
        // Checks switched off: nothing contradicts the connection.
        assertThat(dotColorFor(ConnectionState.CONNECTED, TunnelHealth.UNMONITORED))
                .isEqualTo(GREEN);
    }

    @Test
    void theStatesWithoutATunnelKeepTheirOldColours() {
        assertThat(dotColorFor(ConnectionState.DISCONNECTED, TunnelHealth.UNMONITORED))
                .isEqualTo(GREY);
        assertThat(dotColorFor(ConnectionState.CONNECTING, TunnelHealth.UNMONITORED))
                .isEqualTo(AMBER);
        assertThat(dotColorFor(ConnectionState.ERROR, TunnelHealth.UNMONITORED))
                .isEqualTo(RED);
    }
}
