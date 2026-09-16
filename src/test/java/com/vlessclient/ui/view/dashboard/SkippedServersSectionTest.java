package com.vlessclient.ui.view.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import com.vlessclient.service.ConnectionService.SkippedServer;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The wording of the notice for servers the core refused, without a scene. */
class SkippedServersSectionTest {

    @Test
    void thereIsNothingToSayWhenNoServerWasLeftOut() {
        assertThat(SkippedServersSection.textFor(List.of())).isNull();
        assertThat(SkippedServersSection.textFor(null)).isNull();
    }

    @Test
    void aServerIsNamedWithTheCoresReason() {
        String text = SkippedServersSection.textFor(List.of(
                new SkippedServer("srv-2", "Frankfurt", "unsupported flow: xtls-rprx-direct")));

        assertThat(text).contains("Frankfurt", "unsupported flow: xtls-rprx-direct");
    }

    @Test
    void aLongListNamesTheFirstFewAndCountsTheRest() {
        List<SkippedServer> servers = List.of(
                new SkippedServer("1", "One", "reason one"),
                new SkippedServer("2", "Two", "reason two"),
                new SkippedServer("3", "Three", "reason three"),
                new SkippedServer("4", "Four", "reason four"),
                new SkippedServer("5", "Five", "reason five"));

        String text = SkippedServersSection.textFor(servers);

        assertThat(text)
                .contains("One", "Two", "Three", "(5)")
                .contains(I18n.get("dashboard.skipped.more", "2"))
                .doesNotContain("Four", "Five");
    }
}
