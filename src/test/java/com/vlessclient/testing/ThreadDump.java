package com.vlessclient.testing;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
