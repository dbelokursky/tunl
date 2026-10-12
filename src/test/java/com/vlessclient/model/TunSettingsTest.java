package com.vlessclient.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.platform.Platform;
import org.junit.jupiter.api.Test;

/** The TUN settings a TUN start can succeed with. */
class TunSettingsTest {

    @Test
    void anIpv4NetworkNeedsAnAddressAndAPrefixFrom8To30() {
        assertThat(TunSettings.isIpv4Network("172.19.0.1/30")).isTrue();
        assertThat(TunSettings.isIpv4Network(" 10.0.0.1/8 ")).isTrue();

        assertThat(TunSettings.isIpv4Network("172.19.0.1")).as("no prefix").isFalse();
        assertThat(TunSettings.isIpv4Network("")).isFalse();
        assertThat(TunSettings.isIpv4Network(null)).isFalse();
        assertThat(TunSettings.isIpv4Network("172.19.0.1/32")).as("no room for the peer").isFalse();
        assertThat(TunSettings.isIpv4Network("172.19.0.1/7")).isFalse();
        assertThat(TunSettings.isIpv4Network("300.19.0.1/30")).isFalse();
        assertThat(TunSettings.isIpv4Network("fd00::1/64")).as("IPv6").isFalse();
        assertThat(TunSettings.isIpv4Network("tun/30")).isFalse();
    }

    /** macOS names a TUN device utunN, and the core refuses any other name there. */
    @Test
    void macOsTakesOnlyUtunNames() {
        assertThat(TunSettings.isInterfaceName("utun99", Platform.MAC)).isTrue();
        assertThat(TunSettings.isInterfaceName("utun", Platform.MAC)).isFalse();
        assertThat(TunSettings.isInterfaceName("tunl", Platform.MAC)).isFalse();
    }

    /** Linux refuses a name of 16 bytes or more, and one with a space, a slash or a colon. */
    @Test
    void linuxTakesShortNamesWithoutSeparators() {
        assertThat(TunSettings.isInterfaceName("tunl0", Platform.LINUX)).isTrue();
        assertThat(TunSettings.isInterfaceName("fifteen-chars-x", Platform.LINUX)).isTrue();

        assertThat(TunSettings.isInterfaceName("sixteen-chars-xx", Platform.LINUX)).isFalse();
        assertThat(TunSettings.isInterfaceName("tun 0", Platform.LINUX)).isFalse();
        assertThat(TunSettings.isInterfaceName("tun/0", Platform.LINUX)).isFalse();
        assertThat(TunSettings.isInterfaceName("tun:0", Platform.LINUX)).isFalse();
        assertThat(TunSettings.isInterfaceName("..", Platform.LINUX)).isFalse();
    }

    @Test
    void noSystemTakesABlankName() {
        for (Platform platform : Platform.values()) {
            assertThat(TunSettings.isInterfaceName("  ", platform)).as("%s", platform).isFalse();
            assertThat(TunSettings.isInterfaceName(null, platform)).as("%s", platform).isFalse();
        }
        assertThat(TunSettings.isInterfaceName("VlessClientTun", Platform.WINDOWS)).isTrue();
    }
}
