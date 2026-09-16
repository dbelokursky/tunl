package com.vlessclient.service;

import com.vlessclient.model.ConnectionState;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Which failures of the tunnel get a system notification. */
class FailureNoticesTest {

    private final AtomicLong request = new AtomicLong(1);
    private final FailureNotices notices = new FailureNotices(request::get);

    @Test
    void aFailureStreakGetsOneNotice() {
        assertThat(notices.onState(ConnectionState.ERROR)).as("the first failure").isTrue();
        assertThat(notices.onState(ConnectionState.CONNECTING)).as("a retry starting").isFalse();
        assertThat(notices.onState(ConnectionState.ERROR)).as("the retry failing").isFalse();
        assertThat(notices.onState(ConnectionState.ERROR)).as("another failing").isFalse();
    }

    @Test
    void aTunnelThatCameUpStartsANewStreak() {
        notices.onState(ConnectionState.ERROR);
        notices.onState(ConnectionState.CONNECTED);

        assertThat(notices.onState(ConnectionState.ERROR))
                .as("a drop after the tunnel came up")
                .isTrue();
    }

    @Test
    void aNewRequestOfTheUsersStartsANewStreak() {
        notices.onState(ConnectionState.ERROR);
        request.incrementAndGet();

        assertThat(notices.onState(ConnectionState.ERROR))
                .as("a failure after the user connected again")
                .isTrue();
    }

    @Test
    void onlyAFailureIsNoticed() {
        for (ConnectionState state : ConnectionState.values()) {
            if (state != ConnectionState.ERROR) {
                assertThat(new FailureNotices(request::get).onState(state))
                        .as("a notice for %s", state)
                        .isFalse();
            }
        }
    }
}
