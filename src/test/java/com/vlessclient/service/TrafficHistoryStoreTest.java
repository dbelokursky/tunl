package com.vlessclient.service;

import com.vlessclient.model.ServerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the day/server bucketing in {@link TrafficHistoryStore}.
 *
 * <p>Every test drives an advanceable clock rather than the wall clock: the
 * two things worth pinning here are which day a sample lands in and what
 * survives a reload, and neither is testable against "now".</p>
 */
class TrafficHistoryStoreTest {

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        private Instant now;

        TestClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static ServerConfig server(String id, String name) {
        ServerConfig config = new ServerConfig();
        config.setId(id);
        config.setName(name);
        return config;
    }

    private static TestClock clockAt(String isoInstant) {
        return new TestClock(Instant.parse(isoInstant));
    }

    @Test
    void samplesSplitByServerAndSurviveAReload(@TempDir Path dir) {
        TestClock clock = clockAt("2026-09-05T10:00:00Z");
        TrafficHistoryStore store = new TrafficHistoryStore(dir, clock);

        store.record(server("a", "Amsterdam 01"), 1_000, 9_000);
        store.record(server("b", "Frankfurt 02"), 500, 1_500);
        store.record(server("a", "Amsterdam 01"), 0, 1_000);
        store.flush();

        TrafficHistoryStore reopened = new TrafficHistoryStore(dir, clock);
        List<TrafficHistoryStore.ServerTotal> top = reopened.topServers(5, 7);

        assertThat(top).extracting(TrafficHistoryStore.ServerTotal::serverName)
                .as("busiest first")
                .containsExactly("Amsterdam 01", "Frankfurt 02");
        assertThat(top.get(0).total()).isEqualTo(11_000);
        assertThat(top.get(1).total()).isEqualTo(2_000);
    }

    @Test
    void crossingMidnightStartsANewBucket(@TempDir Path dir) {
        TestClock clock = clockAt("2026-09-05T23:59:00Z");
        TrafficHistoryStore store = new TrafficHistoryStore(dir, clock);

        store.record(server("a", "Amsterdam 01"), 100, 900);
        clock.advance(Duration.ofMinutes(2));
        store.record(server("a", "Amsterdam 01"), 200, 800);
        assertThat(store.awaitIdle(10_000)).isTrue();

        List<TrafficHistoryStore.DayTotal> days = store.lastDays(2);
        assertThat(days).extracting(TrafficHistoryStore.DayTotal::date)
                .containsExactly(LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 6));
        assertThat(days.get(0).total()).as("what was moving before midnight").isEqualTo(1_000);
        assertThat(days.get(1).total()).as("and after it").isEqualTo(1_000);
    }

    @Test
    void idleSamplesAreNotRecorded(@TempDir Path dir) {
        TestClock clock = clockAt("2026-09-05T10:00:00Z");
        TrafficHistoryStore store = new TrafficHistoryStore(dir, clock);

        for (int i = 0; i < 100; i++) {
            store.record(server("a", "Amsterdam 01"), 0, 0);
        }
        store.flush();

        assertThat(store.lastDays(1).get(0).total())
                .as("an idle tunnel samples every second; none of it is traffic")
                .isZero();
        assertThat(Files.exists(dir.resolve("traffic-history.json")))
                .as("nothing changed, so nothing was written")
                .isFalse();
    }

    @Test
    void quietDaysComeBackAsZeroesNotAsGaps(@TempDir Path dir) {
        TestClock clock = clockAt("2026-09-05T10:00:00Z");
        TrafficHistoryStore store = new TrafficHistoryStore(dir, clock);
        store.record(server("a", "Amsterdam 01"), 1_000, 1_000);

        List<TrafficHistoryStore.DayTotal> week = store.lastDays(7);

        assertThat(week).as("a chart needs seven bars, six of them empty").hasSize(7);
        assertThat(week.subList(0, 6)).allSatisfy(day ->
                assertThat(day.total()).isZero());
        assertThat(week.get(6).total()).isEqualTo(2_000);
    }

    @Test
    void monthTotalsCountOnlyTheirOwnMonth(@TempDir Path dir) {
        TestClock clock = clockAt("2026-08-31T12:00:00Z");
        TrafficHistoryStore store = new TrafficHistoryStore(dir, clock);
        store.record(server("a", "Amsterdam 01"), 1_000, 1_000);
        clock.advance(Duration.ofDays(1));
        store.record(server("a", "Amsterdam 01"), 3_000, 4_000);
        assertThat(store.awaitIdle(10_000)).isTrue();

        assertThat(store.totalForMonth(YearMonth.of(2026, 8))).isEqualTo(2_000);
        assertThat(store.totalForMonth(YearMonth.of(2026, 9))).isEqualTo(7_000);
        assertThat(store.totalForMonth(YearMonth.of(2026, 7))).isZero();
    }

    @Test
    void resetClearsMemoryAndRemovesTheFile(@TempDir Path dir) throws IOException {
        TestClock clock = clockAt("2026-09-05T10:00:00Z");
        TrafficHistoryStore store = new TrafficHistoryStore(dir, clock);
        store.record(server("a", "Amsterdam 01"), 1_000, 1_000);
        store.flush();
        assertThat(dir.resolve("traffic-history.json")).exists();

        store.reset();

        assertThat(store.lastDays(1).get(0).total()).isZero();
        assertThat(Files.exists(dir.resolve("traffic-history.json")))
                .as("reset is the only way to clear this record, so it has to "
                        + "leave nothing behind on disk")
                .isFalse();
        assertThat(new TrafficHistoryStore(dir, clock).topServers(5, 7)).isEmpty();
    }

    @Test
    void aCorruptFileStartsEmptyInsteadOfFailingStartup(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("traffic-history.json"), "{ not json",
                StandardCharsets.UTF_8);
        TestClock clock = clockAt("2026-09-05T10:00:00Z");

        TrafficHistoryStore store = new TrafficHistoryStore(dir, clock);

        assertThat(store.topServers(5, 30)).isEmpty();
        store.record(server("a", "Amsterdam 01"), 1_000, 1_000);
        store.flush();
        assertThat(store.lastDays(1).get(0).total()).isEqualTo(2_000);
    }

    @Test
    void bytesWithNoNamedServerAreKeptRatherThanDropped(@TempDir Path dir) {
        TestClock clock = clockAt("2026-09-05T10:00:00Z");
        TrafficHistoryStore store = new TrafficHistoryStore(dir, clock);

        store.record(null, 1_000, 2_000);

        assertThat(store.lastDays(1).get(0).total())
                .as("an automatic mode can move bytes before the group monitor "
                        + "names the exit; losing them would understate the day")
                .isEqualTo(3_000);
        assertThat(store.topServers(5, 1)).singleElement()
                .extracting(TrafficHistoryStore.ServerTotal::serverId)
                .isEqualTo("unknown");
    }
}
