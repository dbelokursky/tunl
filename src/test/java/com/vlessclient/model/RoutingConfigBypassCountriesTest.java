package com.vlessclient.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * A bypass country has to be one sing-geoip publishes a list for.
 *
 * <p>Any ISO code was kept, and eleven of them (Western Sahara, Svalbard,
 * Bouvet Island and other territories without address blocks of their own)
 * have no geoip-&lt;cc&gt;.srs: the download was a 404, and the core does not
 * start without its rule-sets, so every server failed to connect until the
 * country was removed.</p>
 */
class RoutingConfigBypassCountriesTest {

    private static final List<String> WITHOUT_A_GEOIP_LIST =
            List.of("bv", "cc", "cx", "eh", "gs", "hm", "pn", "sh", "sj", "tf", "um");

    @Test
    void aCountryWithoutAGeoipListIsNotKept() {
        RoutingConfig config = new RoutingConfig();
        java.util.ArrayList<String> countries = new java.util.ArrayList<>(WITHOUT_A_GEOIP_LIST);
        countries.add("ru");
        countries.add("DE");

        config.setBypassCountries(countries);

        assertThat(config.getBypassCountries()).containsExactly("ru", "de");
        for (String code : WITHOUT_A_GEOIP_LIST) {
            assertThat(RoutingConfig.isBypassCountry(code)).as(code).isFalse();
        }
    }

    @Test
    void everyOtherIsoCountryIsKept() {
        for (String iso : Locale.getISOCountries()) {
            String code = iso.toLowerCase(Locale.ROOT);
            assertThat(RoutingConfig.isBypassCountry(code)).as(code)
                    .isEqualTo(!WITHOUT_A_GEOIP_LIST.contains(code));
        }
    }
}
