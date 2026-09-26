package com.vlessclient.service;

import com.vlessclient.model.ServerConfig;
import com.vlessclient.testing.TestServers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

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
        return TestServers.server()
                .id(id)
                .name(name)
                .build();
    }

    private static TestClock clockAt(String isoInstant) {
        return new TestClock(Instant.parse(isoInstant));
    }

    /** Every store a test opened, so none is left writing once the test ends. */
    private final List<TrafficHistoryStore> opened = new ArrayList<>();

    /**
     * Opens a store and remembers it. A sample that arrives a minute after the
     * last write queues one on the store's io thread, and a test that returned
     * before it ran had JUnit deleting the temp dir while the write landed in it.
     */
    private TrafficHistoryStore open(Path directory, Clock clock) {
        return open(directory, clock, new PersistenceState());
    }

    private TrafficHistoryStore open(Path directory, Clock clock, PersistenceState persistence) {
        TrafficHistoryStore store = new TrafficHistoryStore(directory, clock, persistence);
        opened.add(store);
        return store;
    }

    @AfterEach
    void waitForTheWritesBeforeTheTempDirGoes() {
        for (TrafficHistoryStore store : opened) {
            assertThat(store.awaitIdle(10_000))
                    .as("a write still on its way when JUnit deletes the temp dir")
                    .isTrue();
        }
    }

    @Test
    void samplesSplitByServerAndSurviveAReload(@TempDir Path dir) {
        TestClock clock = clockAt("2026-09-05T10:00:00Z");
        TrafficHistoryStore store = open(dir, clock);

        store.record(server("a", "Amsterdam 01"), 1_000, 9_000);
        store.record(server("b", "Frankfurt 02"), 500, 1_500);
        store.record(server("a", "Amsterdam 01"), 0, 1_000);
        store.flush();

        TrafficHistoryStore reopened = open(dir, clock);
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
        TrafficHistoryStore store = open(dir, clock);

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
        TrafficHistoryStore store = open(dir, clock);

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
        TrafficHistoryStore store = open(dir, clock);
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
        TrafficHistoryStore store = open(dir, clock);
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
        TrafficHistoryStore store = open(dir, clock);
        store.record(server("a", "Amsterdam 01"), 1_000, 1_000);
        store.flush();
        assertThat(dir.resolve("traffic-history.json")).exists();

        store.reset();

        assertThat(store.lastDays(1).get(0).total()).isZero();
        assertThat(Files.exists(dir.resolve("traffic-history.json")))
                .as("reset is the only way to clear this record, so it has to "
                        + "leave nothing behind on disk")
                .isFalse();
        assertThat(open(dir, clock).topServers(5, 7)).isEmpty();
    }

    @Test
    void aCorruptFileStartsEmptyInsteadOfFailingStartup(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("traffic-history.json"), "{ not json",
                StandardCharsets.UTF_8);
        TestClock clock = clockAt("2026-09-05T10:00:00Z");

        TrafficHistoryStore store = open(dir, clock);

        assertThat(store.topServers(5, 30)).isEmpty();
        store.record(server("a", "Amsterdam 01"), 1_000, 1_000);
        store.flush();
        assertThat(store.lastDays(1).get(0).total()).isEqualTo(2_000);
    }

    /**
     * A history that would not parse was dropped in memory, and the first
     * flush, a minute into the next tunnel, wrote over it. Nothing in it is
     * ever pruned, so that file may have held years of days.
     */
    @Test
    void aFileThatWillNotParseIsMovedAsideBeforeAnythingIsWritten(@TempDir Path dir)
            throws IOException {
        Files.writeString(dir.resolve("traffic-history.json"), "{ not json",
                StandardCharsets.UTF_8);
        TrafficHistoryStore store = open(dir, clockAt("2026-09-05T10:00:00Z"));

        store.record(server("a", "Amsterdam 01"), 1_000, 1_000);
        store.flush();

        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.filter(file -> file.getFileName().toString()
                            .startsWith("traffic-history.json.corrupt-")))
                    .singleElement()
                    .satisfies(aside -> assertThat(aside).hasContent("{ not json"));
        }
    }

    /**
     * A file that is there but cannot be read says nothing about what it
     * holds: an antivirus or a backup tool can have it open at startup. The
     * first flush replaced it with a minute of traffic.
     */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "POSIX permissions make the file unreadable")
    void aFileThatCannotBeReadIsNotWrittenOver(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("traffic-history.json");
        String history = """
                {"version": 1, "days": [
                  {"date": "2025-01-01",
                   "servers": [{"serverId": "a", "upload": 5, "download": 5}]}
                ]}""";
        Files.writeString(file, history, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
        assumeFalse(Files.isReadable(file), "this user can read any file");
        TrafficHistoryStore store = open(dir, clockAt("2026-09-05T10:00:00Z"));

        store.record(server("a", "Amsterdam 01"), 1_000, 1_000);
        store.flush();

        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        assertThat(file).hasContent(history);
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "POSIX permissions make the file unreadable")
    void clearingAFileThatCannotBeReadLetsANewRecordStart(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("traffic-history.json");
        Files.writeString(file, "{}", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
        assumeFalse(Files.isReadable(file), "this user can read any file");
        TestClock clock = clockAt("2026-09-05T10:00:00Z");
        TrafficHistoryStore store = open(dir, clock);

        store.reset();
        store.record(server("a", "Amsterdam 01"), 1_000, 1_000);
        store.flush();

        assertThat(open(dir, clock).lastDays(1).get(0).total())
                .as("the file nobody could read is gone, so there is nothing left to protect")
                .isEqualTo(2_000);
    }

    @Test
    void bytesWithNoNamedServerAreKeptRatherThanDropped(@TempDir Path dir) {
        TestClock clock = clockAt("2026-09-05T10:00:00Z");
        TrafficHistoryStore store = open(dir, clock);

        store.record(null, 1_000, 2_000);

        assertThat(store.lastDays(1).get(0).total())
                .as("an automatic mode can move bytes before the group monitor "
                        + "names the exit; losing them would understate the day")
                .isEqualTo(3_000);
        assertThat(store.topServers(5, 1)).singleElement()
                .extracting(TrafficHistoryStore.ServerTotal::serverId)
                .isEqualTo("unknown");
    }

    @Test
    void aDayIsBrokenDownByServerWithoutTheDaysAroundIt(@TempDir Path dir) {
        TestClock clock = clockAt("2026-09-05T10:00:00Z");
        TrafficHistoryStore store = open(dir, clock);

        store.record(server("a", "Amsterdam 01"), 1_000, 1_000);
        clock.advance(Duration.ofDays(1));
        store.record(server("a", "Amsterdam 01"), 0, 5_000);
        store.record(server("b", "Frankfurt 02"), 2_000, 4_000);

        List<TrafficHistoryStore.ServerTotal> day =
                store.serversForDay(LocalDate.of(2026, 9, 6));

        assertThat(day).extracting(TrafficHistoryStore.ServerTotal::serverName)
                .as("busiest first, as the window view already orders them")
                .containsExactly("Frankfurt 02", "Amsterdam 01");
        assertThat(day.get(1).total())
                .as("the 2 KB Amsterdam carried the day before must not leak in -- "
                        + "a bar stands for one day and so must its breakdown")
                .isEqualTo(5_000);
        assertThat(store.serversForDay(LocalDate.of(2026, 9, 7)))
                .as("a quiet day is empty rather than absent; twenty-five of the "
                        + "thirty bars in the panel are this day")
                .isEmpty();
    }

    @Test
    void theWholeRecordIsTotalledFromItsFirstDay(@TempDir Path dir) {
        TestClock clock = clockAt("2026-07-30T12:00:00Z");
        TrafficHistoryStore store = open(dir, clock);
        assertThat(store.firstRecordedDate()).as("nothing recorded yet").isEmpty();
        assertThat(store.totalRecorded()).isZero();

        store.record(server("a", "Amsterdam 01"), 1_000, 2_000);
        clock.advance(Duration.ofDays(45));
        store.record(server("b", "Frankfurt 02"), 3_000, 4_000);
        assertThat(store.awaitIdle(10_000)).isTrue();

        assertThat(store.firstRecordedDate()).contains(LocalDate.of(2026, 7, 30));
        assertThat(store.totalRecorded())
                .as("Settings clears the whole record, so it reports the whole record -- "
                        + "not the thirty days the dashboard panel draws")
                .isEqualTo(10_000);

        store.reset();

        assertThat(store.firstRecordedDate()).isEmpty();
        assertThat(store.totalRecorded()).isZero();
    }

    @Test
    void aDayKeyThatIsNotADateDoesNotMoveWhereTheRecordStarts(@TempDir Path dir)
            throws IOException {
        // "2026-00-00" sorts ahead of every real day, so it is the key the
        // lookup meets first -- and LocalDate refuses month zero.
        Files.writeString(dir.resolve("traffic-history.json"), """
                {"version": 1, "days": [
                  {"date": "2026-00-00",
                   "servers": [{"serverId": "a", "upload": 1, "download": 1}]},
                  {"date": "2026-09-01",
                   "servers": [{"serverId": "a", "upload": 5, "download": 5}]}
                ]}""", StandardCharsets.UTF_8);

        TrafficHistoryStore store = open(dir, clockAt("2026-09-05T10:00:00Z"));

        assertThat(store.firstRecordedDate()).contains(LocalDate.of(2026, 9, 1));
    }

    /**
     * A write that failed was only logged: the history stopped growing on
     * disk while the window said nothing. It is shown with the other unsaved
     * files now, and their Retry writes it.
     */
    @Test
    void aFailedWriteIsShownAndRetriedWithTheOtherSaves(@TempDir Path dir) throws IOException {
        PersistenceState persistence = new PersistenceState();
        TrafficHistoryStore store = open(dir, clockAt("2026-09-05T10:00:00Z"), persistence);
        store.record(server("a", "Amsterdam 01"), 1_000, 1_000);
        Path file = dir.resolve("traffic-history.json");
        Files.createDirectory(file);
        Files.writeString(file.resolve("blocker"), "not the history", StandardCharsets.UTF_8);

        store.flush();

        assertThat(persistence.failedFiles()).containsExactly("traffic-history.json");

        Files.delete(file.resolve("blocker"));
        Files.delete(file);
        persistence.retry();

        assertThat(persistence.failedFiles()).isEmpty();
        assertThat(open(dir, clockAt("2026-09-05T10:00:00Z")).lastDays(1).get(0).total())
                .isEqualTo(2_000);
    }

    /**
     * A history that cannot be opened is named in the window, and clearing the
     * record releases it: what the hold protected is gone.
     */
    @Test
    void aFileThatCannotBeOpenedIsShownUntilTheRecordIsCleared(@TempDir Path dir)
            throws IOException {
        // Empty, so that clearing the record can remove it.
        Files.createDirectory(dir.resolve("traffic-history.json"));
        PersistenceState persistence = new PersistenceState();
        TrafficHistoryStore store = open(dir, clockAt("2026-09-05T10:00:00Z"), persistence);

        assertThat(persistence.heldReasons()).containsKey("traffic-history.json");

        store.reset();
        store.record(server("a", "Amsterdam 01"), 1_000, 1_000);
        store.flush();

        assertThat(persistence.heldReasons()).isEmpty();
        assertThat(dir.resolve("traffic-history.json")).isRegularFile();
    }

    @Test
    void aFileThatWillNotParseIsNamedWithWhereItWent(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("traffic-history.json"), "{ not json",
                StandardCharsets.UTF_8);
        PersistenceState persistence = new PersistenceState();

        open(dir, clockAt("2026-09-05T10:00:00Z"), persistence);

        assertThat(persistence.setAsideLocations()).hasEntrySatisfying("traffic-history.json",
                where -> assertThat(Path.of(where)).hasContent("{ not json"));
    }

    /**
     * A newer build's history was read, its fields dropped, and the file
     * written back claiming the newer version, so that build would take what
     * was left for its own format. The fields ride along now, under the
     * version this build writes.
     */
    @Test
    void aHistoryFromANewerBuildKeepsWhatThisBuildDoesNotKnow(@TempDir Path dir)
            throws IOException {
        Path file = dir.resolve("traffic-history.json");
        Files.writeString(file, """
                {"version": 2, "retention": "forever", "days": [
                  {"date": "2025-01-01", "weather": "fog",
                   "servers": [{"serverId": "a", "upload": 5, "download": 5,
                                "protocol": "vless"}]}
                ]}""", StandardCharsets.UTF_8);
        TrafficHistoryStore store = open(dir, clockAt("2026-09-05T10:00:00Z"));

        store.record(server("a", "Amsterdam 01"), 1_000, 1_000);
        store.flush();

        JsonNode saved = JsonMapper.builder().build().readTree(file.toFile());
        assertThat(saved.path("version").asInt()).as("the format this build wrote").isEqualTo(1);
        assertThat(saved.path("retention").asString()).isEqualTo("forever");
        JsonNode oldDay = saved.path("days").get(0);
        assertThat(oldDay.path("weather").asString()).isEqualTo("fog");
        assertThat(oldDay.path("servers").get(0).path("protocol").asString()).isEqualTo("vless");
        assertThat(store.totalRecorded()).as("and the old day still counts").isEqualTo(2_010);
    }
}
