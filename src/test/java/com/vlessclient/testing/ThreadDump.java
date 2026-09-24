package com.vlessclient.testing;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    private static final int FRAMES = 14;
    private static final int MAX_LINES = 160;

    /**
     * The part of the dump that says why background work did not run, short
     * enough for a failure message: whether the carriers of virtual threads
     * are free or each held by one, every virtual thread in this app's code
     * or running, and the FX thread.
     *
     * <p>Work the UI hands off runs on virtual threads. When it all stops at
     * once, which UI tests on Windows runners have shown, either the carriers
     * are held by threads that do not give them back or the work is waiting on
     * something; this tells the two apart.</p>
     *
     * @return the summary, or a line saying why there is none
     */
    public static String forBackgroundWork() {
        String dump = ofAllThreads();
        List<String> kept = new ArrayList<>();
        int virtual = 0;
        int running = 0;
        int carriers = 0;
        int carriersBusy = 0;
        for (String entry : dump.split("\\R\\R")) {
            String[] lines = entry.strip().split("\\R");
            String header = lines[0];
            if (!header.startsWith("#")) {
                continue;
            }
            boolean isVirtual = header.contains("\" virtual ");
            if (isVirtual) {
                virtual++;
                boolean runs = header.contains(" RUNNABLE ");
                if (runs) {
                    running++;
                }
                if (runs || entry.contains("com.vlessclient")) {
                    keep(kept, lines, FRAMES);
                }
            } else if (CARRIER.matcher(header).matches()) {
                carriers++;
                if (entry.contains("Continuation.run")) {
                    carriersBusy++;
                }
                keep(kept, lines, 3);
            } else if (header.contains("\"JavaFX Application Thread\"")) {
                keep(kept, lines, FRAMES);
            }
        }
        StringBuilder summary = new StringBuilder()
                .append("threads: ").append(virtual).append(" virtual (")
                .append(running).append(" running), ").append(carriersBusy).append(" of ")
                .append(carriers).append(" carriers busy");
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

    private static void keep(List<String> kept, String[] lines, int frames) {
        for (int i = 0; i < lines.length && i <= frames; i++) {
            kept.add(lines[i].strip());
        }
        if (lines.length > frames + 1) {
            kept.add("  ...");
        }
    }
}
