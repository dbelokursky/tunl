package com.vlessclient.app;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A JVM for {@code JvmOptionsHeapTest}: grows the heap past 200 MB of live
 * data, lets the data go and sits idle, the way the app does once its window
 * is back in the tray. Prints the committed heap after the growth
 * ({@code grown <bytes>}) and then after the idle wait ({@code idle <bytes>}),
 * which ends as soon as the heap is at or below the bound given in MB, or
 * after fifteen seconds.
 */
public final class HeapGivesBackProbe {

    private static final int CHUNK_BYTES = 256 * 1024;

    private HeapGivesBackProbe() {
    }

    public static void main(String[] args) throws InterruptedException {
        long boundBytes = Long.parseLong(args[0]) * 1024 * 1024;
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();

        // Chunks below half a region, so they are ordinary objects and not
        // humongous ones that G1 would free and uncommit on its own.
        List<byte[]> held = new ArrayList<>();
        for (int i = 0; i < 800; i++) {
            held.add(new byte[CHUNK_BYTES]);
        }
        System.out.println("grown " + memory.getHeapMemoryUsage().getCommitted());
        held.clear();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        long committed;
        do {
            Thread.sleep(250);
            committed = memory.getHeapMemoryUsage().getCommitted();
        } while (committed > boundBytes && System.nanoTime() < deadline);
        System.out.println("idle " + committed);
    }
}
