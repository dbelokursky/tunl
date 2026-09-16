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

    public static void main(String[] args) {
        System.out.println(SingleInstance.acquire(Path.of(args[0])).isPresent()
                ? "acquired" : "refused");
    }
}
