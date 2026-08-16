package com.vlessclient.service;

import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The core-version notice. It only ever adds information — "a newer sing-box
 * exists" — so the failure that matters is claiming one when there isn't, or
 * on the strength of an answer that never arrived.
 */
class SingBoxReleasesTest {

    private final SingBoxReleases releases = new SingBoxReleases();

    @Test
    void readsTheTagAndDropsItsPrefix() {
        assertThat(releases.parseTag("{\"tag_name\":\"v1.14.0\",\"name\":\"1.14.0\"}"))
                .contains("1.14.0");
    }

    @Test
    void anUnusableResponseNamesNoVersion() {
        // Empty rather than a guess: everything downstream treats a value here
        // as "a newer core was released".
        assertThat(releases.parseTag("{ not json")).isEmpty();
        assertThat(releases.parseTag("{}")).isEmpty();
        assertThat(releases.parseTag("{\"tag_name\":\"\"}")).isEmpty();
    }

    @Test
    void onlyAGenuinelyNewerVersionIsReported() {
        assertThat(SingBoxReleases.newerOf(Optional.of("1.14.0"), "1.13.14")).contains("1.14.0");
        assertThat(SingBoxReleases.newerOf(Optional.of("1.13.14"), "1.13.14")).isEmpty();
        // A pin ahead of the latest release happens while a bump PR is open.
        assertThat(SingBoxReleases.newerOf(Optional.of("1.13.2"), "1.13.14")).isEmpty();
    }

    @Test
    void aCheckThatFailedNeverProducesANotice() {
        // The whole point of the empty Optional coming out of the fetch.
        assertThat(SingBoxReleases.newerOf(Optional.empty(), "1.13.14")).isEmpty();
        // And an unknown local version cannot be compared against anything.
        assertThat(SingBoxReleases.newerOf(Optional.of("1.14.0"), "")).isEmpty();
        assertThat(SingBoxReleases.newerOf(Optional.of("1.14.0"), null)).isEmpty();
    }
}
