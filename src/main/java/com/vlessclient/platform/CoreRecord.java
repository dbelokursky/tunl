package com.vlessclient.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The core this app run started without elevation, written down so the next
 * run can end it when this one dies without stopping it.
 *
 * <p>Such a core is a plain child process, and nothing ends a child when its
 * parent is killed. After kill -9, "End task" or a crash, sing-box kept the
 * SOCKS and HTTP ports and, in system proxy mode, the OS proxy. The next run
 * cleared the proxy but could not start a core of its own: every start failed
 * on "address already in use", and recovery kept retrying. The TUN wrappers
 * watch the app's pid for this; a direct core has no wrapper.</p>
 *
 * <p>A TUN core is recorded too, in a file of its own ({@link #forTunnel()}):
 * the process its launcher hands back, whose life mirrors the core's. The app
 * cannot end that core, which runs with the administrator's rights, but the
 * next run can wait for it to close before starting another beside it.</p>
 *
 * <p>Only the recorded process is ended: the same pid, started at the same
 * moment and, where the system reports it, from the same executable. A pid
 * the system has given to another program since is left alone.</p>
 */
public final class CoreRecord {

    /** The record's name in the data directory. */
    public static final String FILE_NAME = "core.pid";

    /** The TUN core's record, beside the direct core's. */
    public static final String TUNNEL_FILE_NAME = "tun-core.pid";

    private static final Logger log = LoggerFactory.getLogger(CoreRecord.class);

    /**
     * How far apart two readings of one process's start may be. Linux counts
     * a start from the boot time, which it reports in whole seconds, so two
     * JVMs can read the same start a second apart.
     */
    private static final Duration START_TOLERANCE = Duration.ofSeconds(2);

    /** How long a leftover core gets to exit on its own, restoring the OS proxy. */
    private static final Duration GRACE = Duration.ofSeconds(5);

    /** How long a killed core gets before it is reported as still running. */
    private static final Duration KILL_WAIT = Duration.ofSeconds(2);

    private static final String PID = "pid";
    private static final String START = "start";
    private static final String COMMAND = "command";

    /** What Linux appends to a process's executable once the file is deleted. */
    private static final String DELETED = " (deleted)";

    private final Path file;

    /**
     * Whether an entry names the executable too. A TUN core's record does not
     * ({@link #forTunnel()}): the launcher's process can replace its program
     * as it runs, as pkexec does with the program it starts, and was then
     * taken for another process. Its pid and start time name it well enough.
     */
    private final boolean recordsCommand;

    /** Guards the file. Private, because {@link #inDataDir()} hands instances out. */
    private final Object lock = new Object();

    /** What the next run found where a record was expected. */
    public enum Leftover {
        /** No record, a damaged one, or a record whose core has exited. */
        NONE,
        /** The recorded core was still running, and has been ended. */
        ENDED,
        /** The recorded pid belongs to another process now, which was left alone. */
        NOT_THE_CORE
    }

    /**
     * One recorded core.
     *
     * @param pid     its process id
     * @param start   when the system says it started
     * @param command its executable, or empty when the system did not say
     */
    public record Entry(long pid, Instant start, String command) {

        /** Whether {@code process}, which has this entry's pid, is the recorded core. */
        boolean names(ProcessHandle process) {
            ProcessHandle.Info info = process.info();
            Optional<Instant> started = info.startInstant();
            if (started.isEmpty()
                    || Duration.between(start, started.get()).abs()
                            .compareTo(START_TOLERANCE) > 0) {
                return false;
            }
            return command.isEmpty()
                    || info.command().map(live -> sameExecutable(command, live)).orElse(true);
        }

        private String text() {
            return String.format("%s=%d%n%s=%s%n%s=%s%n",
                    PID, pid, START, start, COMMAND, command);
        }
    }

    /**
     * A record kept in {@code file}.
     *
     * @param file where the record is written
     */
    public CoreRecord(Path file) {
        this(file, true);
    }

    private CoreRecord(Path file, boolean recordsCommand) {
        this.file = file;
        this.recordsCommand = recordsCommand;
    }

    /**
     * A record in the app's data directory, beside the single-instance lock.
     *
     * @return the record the app keeps
     */
    public static CoreRecord inDataDir() {
        return new CoreRecord(PlatformPaths.current().dataDir().resolve(FILE_NAME));
    }

    /**
     * The record kept beside this one for a TUN core: the process its
     * launcher hands back, whose life mirrors the core's.
     *
     * @return the TUN core's record
     */
    public CoreRecord forTunnel() {
        return new CoreRecord(file.resolveSibling(TUNNEL_FILE_NAME), false);
    }

    /**
     * The recorded core, while it is still running. A record whose core has
     * exited, or whose pid the system has given to another process, is
     * removed, and so is one that cannot be read.
     *
     * @return the running core; empty when there is none
     */
    public Optional<ProcessHandle> runningCore() {
        synchronized (lock) {
            Optional<Entry> recorded = read();
            Optional<ProcessHandle> process = recorded
                    .flatMap(entry -> ProcessHandle.of(entry.pid()))
                    .filter(ProcessHandle::isAlive)
                    .filter(handle -> recorded.get().names(handle));
            if (process.isEmpty()) {
                if (recorded.isPresent()) {
                    clear(recorded.get());
                } else {
                    delete();
                }
            }
            return process;
        }
    }

    /**
     * Whether {@code live}, a running process's executable as the system
     * reports it, is the recorded {@code command}.
     *
     * <p>Linux reads the executable from /proc/PID/exe, which gains
     * " (deleted)" once the file is gone. A release that pins another core
     * deletes the old binary at startup, so without this the core it left
     * running read as another program.</p>
     *
     * @param command the executable the record holds
     * @param live    the executable the system reports now
     * @return true when they name the same file
     */
    static boolean sameExecutable(String command, String live) {
        return live.equals(command) || live.equals(command + DELETED);
    }

    /**
     * Writes {@code core} down as the running core, replacing an earlier entry.
     * A failure is logged, not thrown: without the record only the cleanup
     * after a crash is lost.
     *
     * @param core the core just started
     * @return the entry written, or null when none could be
     */
    public Entry write(ProcessHandle core) {
        ProcessHandle.Info info = core.info();
        Optional<Instant> start = info.startInstant();
        if (start.isEmpty()) {
            log.warn("The system does not say when sing-box ({}) started; "
                    + "if the app dies, the next run cannot end it", core.pid());
            return null;
        }
        Entry entry = new Entry(core.pid(), start.get(),
                recordsCommand ? info.command().orElse("") : "");
        synchronized (lock) {
            return store(entry) ? entry : null;
        }
    }

    /** Writes {@code entry} as it is; tests record a process under another start. */
    void write(Entry entry) {
        synchronized (lock) {
            store(entry);
        }
    }

    /**
     * Removes the record if it still holds {@code entry}. The record of a core
     * started after it stays.
     *
     * @param entry what {@link #write(ProcessHandle)} returned for the stopped
     *              core; null does nothing
     */
    public void clear(Entry entry) {
        synchronized (lock) {
            if (entry != null && read().filter(entry::equals).isPresent()) {
                delete();
            }
        }
    }

    /**
     * Ends the core an earlier run left running, and removes the record.
     * Called once at startup, before this run starts a core of its own.
     *
     * @return what the record named
     */
    public Leftover endLeftover() {
        synchronized (lock) {
            Optional<Entry> recorded = read();
            try {
                Optional<ProcessHandle> process =
                        recorded.flatMap(entry -> ProcessHandle.of(entry.pid()));
                if (process.isEmpty()) {
                    return Leftover.NONE;
                }
                if (!recorded.get().names(process.get())) {
                    log.info("The sing-box an earlier run recorded ({}) is gone; "
                            + "another process has its pid now", process.get().pid());
                    return Leftover.NOT_THE_CORE;
                }
                log.warn("Ending sing-box ({}), left running by an earlier run of the app",
                        process.get().pid());
                end(process.get());
                return Leftover.ENDED;
            } finally {
                // Only the entry read here: a core recorded meanwhile stays
                // recorded. A record that could not be read goes too.
                if (recorded.isPresent()) {
                    clear(recorded.get());
                } else {
                    delete();
                }
            }
        }
    }

    /**
     * The entry the record holds.
     *
     * @return the entry; empty when there is none or it cannot be read
     */
    public Optional<Entry> read() {
        List<String> lines;
        synchronized (lock) {
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (NoSuchFileException e) {
                return Optional.empty();
            } catch (IOException e) {
                log.warn("Could not read the sing-box record {}: {}", file, e.getMessage());
                return Optional.empty();
            }
        }
        Map<String, String> fields = new HashMap<>();
        for (String line : lines) {
            int split = line.indexOf('=');
            if (split > 0) {
                fields.put(line.substring(0, split), line.substring(split + 1));
            }
        }
        try {
            return Optional.of(new Entry(Long.parseLong(fields.getOrDefault(PID, "")),
                    Instant.parse(fields.getOrDefault(START, "")),
                    fields.getOrDefault(COMMAND, "")));
        } catch (NumberFormatException | DateTimeException e) {
            log.warn("Ignoring a damaged sing-box record {}", file);
            return Optional.empty();
        }
    }

    private boolean store(Entry entry) {
        try {
            SecureFiles.writePrivately(file, entry.text().getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            log.warn("Could not record the running sing-box in {}: {}", file, e.getMessage());
            return false;
        }
    }

    private void delete() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not remove the sing-box record {}: {}", file, e.getMessage());
        }
    }

    /** SIGTERM first where there is one, so the core restores the OS proxy itself. */
    private static void end(ProcessHandle core) {
        core.destroy();
        if (exits(core, GRACE)) {
            return;
        }
        core.destroyForcibly();
        if (!exits(core, KILL_WAIT)) {
            log.error("sing-box ({}), left running by an earlier run, did not exit",
                    core.pid());
        }
    }

    private static boolean exits(ProcessHandle process, Duration timeout) {
        try {
            process.onExit().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (ExecutionException e) {
            return !process.isAlive();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !process.isAlive();
        }
    }
}
