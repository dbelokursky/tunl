package com.vlessclient.app;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** How a tunl:// link reaches the window, however it arrives. */
class DeepLinksTest {

    @AfterEach
    void forget() {
        DeepLinks.reset();
    }

    @Test
    void aTunlLinkIsOneInTunlsSchemeOfAReasonableLength() {
        assertThat(DeepLinks.isLink("tunl://install-config?url=https%3A%2F%2Fa.example")).isTrue();
        assertThat(DeepLinks.isLink("TUNL://install-config?url=x")).isTrue();
        assertThat(DeepLinks.isLink("https://a.example")).isFalse();
        assertThat(DeepLinks.isLink("tunl://x\n--flag")).as("a control character").isFalse();
        assertThat(DeepLinks.isLink("tunl://" + "a".repeat(DeepLinks.MAX_LENGTH))).isFalse();
        assertThat(DeepLinks.isLink(null)).isFalse();
    }

    @Test
    void theLinkWindowsOrLinuxStartedTheAppWithIsTaken() {
        assertThat(DeepLinks.fromArgs(new String[] {"--flag", "tunl://install-config?url=x"}))
                .contains("tunl://install-config?url=x");
        assertThat(DeepLinks.fromArgs(new String[] {"--flag"})).isEmpty();
        assertThat(DeepLinks.fromArgs(null)).isEmpty();
    }

    /** A link that comes before the window, the one that started the app, waits for it. */
    @Test
    void aLinkBeforeTheWindowWaitsForIt() {
        List<String> opened = new ArrayList<>();

        DeepLinks.receive("tunl://install-config?url=first");
        DeepLinks.openWith(opened::add);
        DeepLinks.receive("tunl://install-config?url=second");
        DeepLinks.receive("https://not-ours.example");

        assertThat(opened).containsExactly(
                "tunl://install-config?url=first", "tunl://install-config?url=second");
    }
}
