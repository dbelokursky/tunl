package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PunycodeTest {

    /** RFC 3492's own samples, and labels Node's WHATWG URL spells the same way. */
    @Test
    void aLabelIsEncodedAsRfc3492Does() {
        assertThat(Punycode.label("bücher")).isEqualTo("xn--bcher-kva");
        assertThat(Punycode.label("ليهمابتكل"
                + "موشعربي؟"))
                .isEqualTo("xn--egbpdaj6bu4bxfgehfvwxn");
        assertThat(Punycode.label("他们为什么不说中文"))
                .isEqualTo("xn--ihqwcrb4cv8a8dqg056pqjye");
        assertThat(Punycode.label("straße")).isEqualTo("xn--strae-oqa");
        assertThat(Punycode.label("ελληνικός")).isEqualTo("xn--qxaegecap6byf");
    }

    @Test
    void anAsciiLabelStaysAndOneTooLongForDnsIsKeptAsGiven() {
        assertThat(Punycode.label("example")).isEqualTo("example");
        String long0 = "ß".repeat(70);
        assertThat(Punycode.label(long0)).isEqualTo(long0);
    }
}
