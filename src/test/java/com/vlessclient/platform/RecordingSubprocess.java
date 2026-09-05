package com.vlessclient.platform;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stands in for the OS command a sealer shells out to: records every argv
 * and stdin it is handed and answers from a scripted queue, so a test can
 * pin the exact command without a keychain, DPAPI or D-Bus session anywhere
 * near it. An unscripted call fails the test — an extra command is a
 * finding, not something to answer quietly.
 */
final class RecordingSubprocess implements SecretSealers.Subprocess {

    /** One invocation: the argv exactly as handed over, and the stdin payload (null when none). */
    record Call(List<String> command, String stdin) {
    }

    final List<Call> calls = new ArrayList<>();
    private final Deque<Optional<String>> replies = new ArrayDeque<>();

    /** Scripts the next command to exit 0 with this stdout. */
    RecordingSubprocess reply(String stdout) {
        replies.addLast(Optional.of(stdout));
        return this;
    }

    /** Scripts the next command to fail (non-zero exit, timeout, or not launchable). */
    RecordingSubprocess fail() {
        replies.addLast(Optional.empty());
        return this;
    }

    @Override
    public Optional<String> run(String[] command, String stdin) {
        calls.add(new Call(List.of(command), stdin));
        if (replies.isEmpty()) {
            throw new AssertionError("unscripted command: " + Arrays.toString(command));
        }
        return replies.removeFirst();
    }

    /** The single call made so far; fails when there were none or several. */
    Call only() {
        assertThat(calls).as("exactly one command should have run").hasSize(1);
        return calls.getFirst();
    }
}
