package com.vlessclient.service;

import java.util.concurrent.ThreadFactory;

/**
 * Thread factories for the background executors.
 *
 * <p>Every executor in the app runs daemon threads named after their job, so
 * a forgotten executor can never keep the JVM alive and a thread dump reads
 * as a list of features. The same four-line factory was written out at each
 * site.</p>
 */
public final class DaemonThreads {

    private DaemonThreads() {
    }

    /**
     * A factory producing daemon threads with the given name.
     *
     * @param name the thread name, e.g. {@code update-checker}
     * @return the factory
     */
    public static ThreadFactory factory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
