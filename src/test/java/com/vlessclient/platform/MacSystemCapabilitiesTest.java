package com.vlessclient.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The kernel's power capabilities as a full wake or not, and the in-process
 * read held against {@code pmset -g systemstate}, the process it saves.
 */
class MacSystemCapabilitiesTest {

    private static final long FULL_WAKE = MacSystemCapabilities.CPU
            | MacSystemCapabilities.GRAPHICS | MacSystemCapabilities.AUDIO
            | MacSystemCapabilities.NETWORK;
    private static final long DARK_WAKE = MacSystemCapabilities.CPU | MacSystemCapabilities.NETWORK;

    private final Deque<OptionalLong> reads = new ArrayDeque<>();
    private final BooleanSupplier fullWake = MacSystemCapabilities.fullWake(reads::removeFirst);

    /** The kernel's own sequence: the user's wake, the way down, a dark wake, the user again. */
    @Test
    void onlyAWakeWithGraphicsIsAFullWake() {
        reads.add(OptionalLong.of(FULL_WAKE));
        reads.add(OptionalLong.of(DARK_WAKE));
        reads.add(OptionalLong.of(0));
        reads.add(OptionalLong.of(DARK_WAKE));
        reads.add(OptionalLong.of(FULL_WAKE));

        assertThat(fullWake.getAsBoolean()).as("0xf").isTrue();
        assertThat(fullWake.getAsBoolean()).as("0x9, on the way down").isFalse();
        assertThat(fullWake.getAsBoolean()).as("0x0").isFalse();
        assertThat(fullWake.getAsBoolean()).as("0x9, a dark wake").isFalse();
        assertThat(fullWake.getAsBoolean()).as("0xf, the user's wake").isTrue();
    }

    /**
     * A host that never reports graphics, a virtual machine perhaps, cannot be
     * told apart from a dark wake; taken for one, it would never check the
     * tunnel again.
     */
    @Test
    void aHostThatNeverReportsGraphicsCountsAsAwake() {
        reads.add(OptionalLong.of(DARK_WAKE));
        reads.add(OptionalLong.of(DARK_WAKE));

        assertThat(fullWake.getAsBoolean()).isTrue();
        assertThat(fullWake.getAsBoolean()).isTrue();
    }

    @Test
    void capabilitiesThatCannotBeReadCountAsAwake() {
        reads.add(OptionalLong.of(FULL_WAKE));
        reads.add(OptionalLong.empty());

        assertThat(fullWake.getAsBoolean()).isTrue();
        assertThat(fullWake.getAsBoolean()).isTrue();
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void agreesWithPmsetAboutTheRunningMachine() throws Exception {
        String pmset = systemState();
        assumeTrue(pmset.contains("Current System Capabilities"),
                "pmset reports no capabilities here: " + pmset);

        OptionalLong read = MacSystemCapabilities.read();

        assertThat(read).as("the capabilities pmset reports").isPresent();
        String line = pmset.lines()
                .filter(l -> l.contains("Current System Capabilities"))
                .findFirst().orElseThrow();
        assertThat((read.getAsLong() & MacSystemCapabilities.CPU) != 0)
                .as("the CPU in: " + line).isEqualTo(line.contains("CPU"));
        assertThat((read.getAsLong() & MacSystemCapabilities.GRAPHICS) != 0)
                .as("graphics in: " + line).isEqualTo(line.contains("Graphics"));
        assertThat((read.getAsLong() & MacSystemCapabilities.NETWORK) != 0)
                .as("the network in: " + line).isEqualTo(line.contains("Network"));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.WINDOWS})
    void readsNothingOffMacOs() {
        assertThat(MacSystemCapabilities.read()).isEmpty();
    }

    private static String systemState() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("pmset", "-g", "systemstate")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        return output;
    }
}
