package com.vlessclient.testing;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Every thread of this JVM as text, for a test that timed out waiting for one.
 *
 * <p>This is the dump {@code jcmd Thread.dump_to_file} writes: platform and
 * virtual threads with their stacks. {@link Thread#getAllStackTraces()} leaves
 * virtual threads out, and a virtual thread that never ran shows only here,
 * waiting for a carrier.</p>
 */
public final class ThreadDump {

    private ThreadDump() {
    }

    /**
     * Dumps every thread.
     *
     * @return the dump, or a line saying why there is none
     */
    public static String ofAllThreads() {
        try {
            Path file = Files.createTempFile("threads-", ".txt");
            // dumpThreads refuses a file that exists.
            Files.delete(file);
            try {
                ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class).dumpThreads(
                        file.toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.TEXT_PLAIN);
                return Files.readString(file);
            } finally {
                Files.deleteIfExists(file);
            }
        } catch (IOException | RuntimeException e) {
            return "(no thread dump: " + e + ")";
        }
    }

    /** A carrier of virtual threads: a worker of the default scheduler's pool. */
    private static final Pattern CARRIER =
            Pattern.compile("^#\\d+ \"ForkJoinPool-\\d+-worker-\\d+\" .*");

    /** The selector thread every open java.net.http client keeps. */
    private static final Pattern HTTP_SELECTOR =
            Pattern.compile("^#\\d+ \"HttpClient-\\d+-SelectorManager\" .*");

    private static final int FRAMES = 14;
    private static final int MAX_LINES = 150;

    /**
     * The part of the dump that says why background work did not run, short
     * enough for a failure message: the carriers of virtual threads counted
     * by state, the HTTP clients' selector threads counted with two shown,
     * every other virtual thread that runs or is in this app's code with its
     * stack, and the FX thread.
     *
     * <p>Work the UI hands off runs on virtual threads. When it all stops at
     * once, which UI tests on Windows runners have shown, either the carriers
     * are held by threads that do not give them back or the work is waiting on
     * something; this tells the two apart.</p>
     *
     * @return the summary, or a line saying why there is none
     */
    public static String forBackgroundWork() {
        return summarize(ofAllThreads());
    }

    /** {@link #forBackgroundWork()} over a given dump; a test seam. */
    static String summarize(String text) {
        // Without its carriage returns: on Windows the dump ended its lines
        // in two of them, so a split on a blank line cut at every line, and
        // every thread came out as its header alone.
        String dump = text.replace("\r", "");
        List<String> kept = new ArrayList<>();
        Map<String, Integer> carriers = new TreeMap<>();
        int virtual = 0;
        int running = 0;
        int selectors = 0;
        int selectorsRunning = 0;
        for (String entry : dump.split("\n\n")) {
            String[] lines = entry.strip().split("\n");
            String header = lines[0];
            if (!header.startsWith("#")) {
                continue;
            }
            if (CARRIER.matcher(header).matches()) {
                carriers.merge(state(header), 1, Integer::sum);
                continue;
            }
            if (!header.contains("\" virtual ")) {
                if (header.contains("\"JavaFX Application Thread\"")) {
                    keep(kept, lines, FRAMES);
                }
                continue;
            }
            virtual++;
            boolean runs = header.contains(" RUNNABLE ");
            if (runs) {
                running++;
            }
            if (HTTP_SELECTOR.matcher(header).matches()) {
                selectors++;
                if (runs) {
                    selectorsRunning++;
                }
                if (selectors <= 2) {
                    keep(kept, lines, 6);
                }
            } else if (runs || entry.contains("com.vlessclient")) {
                keep(kept, lines, FRAMES);
            }
        }
        StringBuilder summary = new StringBuilder()
                .append("threads: ").append(virtual).append(" virtual (").append(running)
                .append(" running); carriers ").append(carriers)
                .append("; HTTP client selectors: ").append(selectors).append(" (")
                .append(selectorsRunning).append(" running)");
        int shown = 0;
        for (String line : kept) {
            if (shown++ == MAX_LINES) {
                summary.append("\n  ...");
                break;
            }
            summary.append("\n  ").append(line);
        }
        return summary.toString();
    }

    /** The state in a dump header: {@code #12 "name" [virtual] STATE time}. */
    private static String state(String header) {
        String[] words = header.substring(header.lastIndexOf('"') + 1).trim().split(" ");
        return words.length >= 2 && words[0].equals("virtual") ? words[1] : words[0];
    }

    private static void keep(List<String> kept, String[] lines, int frames) {
        for (int i = 0; i < lines.length && i <= frames; i++) {
            kept.add(lines[i].strip());
        }
        if (lines.length > frames + 1) {
            kept.add("  ...");
        }
    }
}
