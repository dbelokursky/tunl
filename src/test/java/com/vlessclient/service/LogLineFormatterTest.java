package com.vlessclient.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LogLineFormatterTest {

    @Test
    void levelOfReadsTheLevelTokenNotTheMessage() {
        assertThat(LogLineFormatter.levelOf(
                "+0900 2026-04-15 09:22:18 INFO network: no error here"))
                .isEqualTo(LogLineFormatter.Kind.LEVEL_INFO);
        assertThat(LogLineFormatter.levelOf("FATAL[0000] start service: boom"))
                .isEqualTo(LogLineFormatter.Kind.LEVEL_ERROR);
        assertThat(LogLineFormatter.levelOf("2026-04-15 09:22:18 WARN dns: slow"))
                .isEqualTo(LogLineFormatter.Kind.LEVEL_WARN);
        assertThat(LogLineFormatter.levelOf("DEBUG[1234] info about the info"))
                .isEqualTo(LogLineFormatter.Kind.LEVEL_DEBUG);
        assertThat(LogLineFormatter.levelOf("goroutine 1 [running]:"))
                .isEqualTo(LogLineFormatter.Kind.PLAIN);
        assertThat(LogLineFormatter.levelOf(null)).isEqualTo(LogLineFormatter.Kind.PLAIN);
    }

    @Test
    void emptyLineProducesNoSegments() {
        assertThat(LogLineFormatter.format("")).isEmpty();
        assertThat(LogLineFormatter.format(null)).isEmpty();
    }

    @Test
    void fatalBracketContextLineIsTokenized() {
        // sing-box short-form FATAL line
        String line = "FATAL[0000] start service: start dns/https[direct-dns]:"
                + " detour to an empty direct outbound makes no sense";
        List<LogLineFormatter.Segment> segs = LogLineFormatter.format(line);

        assertThat(segs).extracting(LogLineFormatter.Segment::kind)
                .containsSequence(
                        LogLineFormatter.Kind.LEVEL_ERROR,
                        LogLineFormatter.Kind.CONTEXT);
        assertThat(segs.get(0).text()).isEqualTo("FATAL");
        assertThat(segs.get(1).text()).isEqualTo("[0000]");

        // the rest carries the error-tinted message kind
        assertThat(segs.stream()
                .anyMatch(s -> s.kind() == LogLineFormatter.Kind.MSG_ERROR))
                .isTrue();
    }

    @Test
    void infoLineWithTimestampAndModule() {
        String line = "+0900 2026-04-15 09:22:18 INFO network: updated default interface en0";
        List<LogLineFormatter.Segment> segs = LogLineFormatter.format(line);

        assertThat(segs).extracting(LogLineFormatter.Segment::kind)
                .contains(
                        LogLineFormatter.Kind.TIMESTAMP,
                        LogLineFormatter.Kind.LEVEL_INFO,
                        LogLineFormatter.Kind.MODULE,
                        LogLineFormatter.Kind.MSG);

        LogLineFormatter.Segment tsSeg = segs.stream()
                .filter(s -> s.kind() == LogLineFormatter.Kind.TIMESTAMP)
                .findFirst().orElseThrow();
        assertThat(tsSeg.text()).contains("+0900").contains("2026-04-15").contains("09:22:18");

        LogLineFormatter.Segment modSeg = segs.stream()
                .filter(s -> s.kind() == LogLineFormatter.Kind.MODULE)
                .findFirst().orElseThrow();
        assertThat(modSeg.text()).isEqualTo("network:");
    }

    @Test
    void warnLineGetsWarnKinds() {
        String line = "WARN something is slightly off";
        List<LogLineFormatter.Segment> segs = LogLineFormatter.format(line);

        assertThat(segs).extracting(LogLineFormatter.Segment::kind)
                .contains(LogLineFormatter.Kind.LEVEL_WARN, LogLineFormatter.Kind.MSG_WARN);
    }

    @Test
    void debugLineUsesDebugKind() {
        String line = "DEBUG latency-tester fallback 42";
        List<LogLineFormatter.Segment> segs = LogLineFormatter.format(line);

        assertThat(segs).extracting(LogLineFormatter.Segment::kind)
                .contains(LogLineFormatter.Kind.LEVEL_DEBUG)
                .contains(LogLineFormatter.Kind.MSG);
    }

    @Test
    void unknownLineIsReturnedAsSingleMessage() {
        String line = "[bundle-singbox] already present: /Users/dima/bin/sing-box";
        List<LogLineFormatter.Segment> segs = LogLineFormatter.format(line);

        // No level keyword → no level segment
        assertThat(segs).extracting(LogLineFormatter.Segment::kind)
                .doesNotContain(
                        LogLineFormatter.Kind.LEVEL_ERROR,
                        LogLineFormatter.Kind.LEVEL_WARN,
                        LogLineFormatter.Kind.LEVEL_INFO,
                        LogLineFormatter.Kind.LEVEL_DEBUG);

        // Reassembling the segments yields the original line
        StringBuilder sb = new StringBuilder();
        for (LogLineFormatter.Segment s : segs) {
            sb.append(s.text());
        }
        assertThat(sb.toString()).isEqualTo(line);
    }

    @Test
    void caseInsensitiveLevelMatching() {
        assertThat(LogLineFormatter.format("error oops").get(0).kind())
                .isEqualTo(LogLineFormatter.Kind.LEVEL_ERROR);
        assertThat(LogLineFormatter.format("Warning stuff").get(0).kind())
                .isEqualTo(LogLineFormatter.Kind.LEVEL_WARN);
    }

    @Test
    void reassemblyPreservesOriginalText() {
        String[] samples = {
                "FATAL[0000] start service: start dns/https: detour error",
                "+0900 2026-04-15 09:22:18 INFO network: up",
                "DEBUG foo bar baz",
                "this is plain text without level",
                ""
        };
        for (String s : samples) {
            StringBuilder reassembled = new StringBuilder();
            for (LogLineFormatter.Segment seg : LogLineFormatter.format(s)) {
                reassembled.append(seg.text());
            }
            assertThat(reassembled.toString()).isEqualTo(s);
        }
    }
}
