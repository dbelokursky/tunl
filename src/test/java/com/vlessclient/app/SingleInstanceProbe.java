package com.vlessclient.app;

import java.nio.file.Path;

/**
 * A second process for {@link SingleInstanceTest}: tries to take the data
 * directory and prints whether it got it. The lock is released when this JVM
 * exits, as it would be for a real copy of the app.
 */
final class SingleInstanceProbe {

    private SingleInstanceProbe() {
    }

    public static void main(String[] args) throws InterruptedException {
        boolean acquired = SingleInstance.acquire(Path.of(args[0])).isPresent();
        if (args.length > 1 && args[1].equals("hold")) {
            // A running copy whose own shutdown hook takes a while, as the
            // app's does while it waits for a save and flushes the history.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Thread.sleep(3_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "probe-saving"));
            System.out.println("ready");
            System.out.flush();
            Thread.sleep(60_000);
            return;
        }
        System.out.println(acquired ? "acquired" : "refused");
    }
}
