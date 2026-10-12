package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The banner about entries this build could not read says what a save does
 * with them: removes them from some files, and keeps them in others.
 */
class UnreadableNoticeTest {

    @Test
    void entriesASaveRemovesAreAnnouncedAsBefore() {
        assertThat(MainViewController.unreadableNotice("routing.json", ""))
                .isEqualTo(I18n.get("persistence.unreadable", "routing.json"));
    }

    @Test
    void entriesASaveKeepsAreNotSaidToBeRemoved() {
        assertThat(MainViewController.unreadableNotice("", "servers.json"))
                .isEqualTo(I18n.get("persistence.unreadable.kept", "servers.json"))
                .doesNotContain(I18n.get("persistence.unreadable", "servers.json"));
    }

    @Test
    void bothKindsAreNamedWhenBothAreThere() {
        assertThat(MainViewController.unreadableNotice("routing.json", "servers.json"))
                .isEqualTo(I18n.get("persistence.unreadable", "routing.json") + " "
                        + I18n.get("persistence.unreadable.kept", "servers.json"));
    }
}
