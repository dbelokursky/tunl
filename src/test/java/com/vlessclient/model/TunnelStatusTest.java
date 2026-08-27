package com.vlessclient.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The one place the two connection signals are combined, so the table below is
 * what the tray icon and the Dashboard hero card both obey.
 */
class TunnelStatusTest {

    @Test
    void aStartedCoreIsNotCalledConnectedUntilTheProbesAgree() {
        assertThat(TunnelStatus.of(ConnectionState.CONNECTED, TunnelHealth.CHECKING))
                .isEqualTo(TunnelStatus.VERIFYING);
        assertThat(TunnelStatus.of(ConnectionState.CONNECTED, TunnelHealth.HEALTHY))
                .isEqualTo(TunnelStatus.CONNECTED);
        assertThat(TunnelStatus.of(ConnectionState.CONNECTED, TunnelHealth.DEGRADED))
                .isEqualTo(TunnelStatus.DEGRADED);
        assertThat(TunnelStatus.of(ConnectionState.CONNECTED, TunnelHealth.BROKEN))
                .isEqualTo(TunnelStatus.NO_TRAFFIC);
        assertThat(TunnelStatus.of(ConnectionState.CONNECTED, TunnelHealth.UNKNOWN))
                .isEqualTo(TunnelStatus.UNVERIFIED);
    }

    @Test
    void onlyAProvenTunnelIsGreen() {
        assertThat(TunnelStatus.of(ConnectionState.CONNECTED, TunnelHealth.HEALTHY).tone())
                .isEqualTo(TunnelStatus.Tone.OK);

        // The reported bug: a core that started while nothing gets through
        // used to be indistinguishable from a working tunnel.
        assertThat(TunnelStatus.of(ConnectionState.CONNECTED, TunnelHealth.BROKEN).tone())
                .isEqualTo(TunnelStatus.Tone.BAD);
        for (TunnelHealth unproven : new TunnelHealth[] {
                TunnelHealth.CHECKING, TunnelHealth.DEGRADED, TunnelHealth.UNKNOWN}) {
            assertThat(TunnelStatus.of(ConnectionState.CONNECTED, unproven).tone())
                    .as("%s must not read as verified", unproven)
                    .isEqualTo(TunnelStatus.Tone.PENDING);
        }
    }

    @Test
    void nothingToCheckStaysPlainConnected() {
        // Health checks are optional. Switching them off, or removing every
        // target, must not leave the user permanently amber.
        assertThat(TunnelStatus.of(ConnectionState.CONNECTED, TunnelHealth.UNMONITORED))
                .isEqualTo(TunnelStatus.CONNECTED);
        assertThat(TunnelStatus.of(ConnectionState.CONNECTED, null))
                .isEqualTo(TunnelStatus.CONNECTED);
    }

    @ParameterizedTest
    @EnumSource(TunnelHealth.class)
    void healthNeverOverridesANonRunningCore(TunnelHealth health) {
        // A verdict describes a tunnel that was up. It must not survive into a
        // state where there is no tunnel to describe.
        assertThat(TunnelStatus.of(ConnectionState.DISCONNECTED, health))
                .isEqualTo(TunnelStatus.DISCONNECTED);
        assertThat(TunnelStatus.of(ConnectionState.CONNECTING, health))
                .isEqualTo(TunnelStatus.CONNECTING);
        assertThat(TunnelStatus.of(ConnectionState.ERROR, health))
                .isEqualTo(TunnelStatus.ERROR);
    }

    @Test
    void aMissingStateIsTreatedAsDisconnected() {
        assertThat(TunnelStatus.of(null, TunnelHealth.HEALTHY))
                .isEqualTo(TunnelStatus.DISCONNECTED);
    }
}
