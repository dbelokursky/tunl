package com.vlessclient.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/** Tracks failed config writes and retries the latest in-memory state of each affected file. */
public final class PersistenceState {

    private final Map<String, Runnable> pending = new LinkedHashMap<>();
    private final Map<String, Integer> unreadable = new LinkedHashMap<>();
    private final ReadOnlyBooleanWrapper unsaved = new ReadOnlyBooleanWrapper();
    private final ReadOnlyBooleanWrapper hasUnreadable = new ReadOnlyBooleanWrapper();
    private final ReadOnlyStringWrapper unreadableFiles = new ReadOnlyStringWrapper("");
    private long publication;

    /** Records a failed write. Retry must save current state, not an old serialized payload. */
    public synchronized void failed(String file, Runnable retry) {
        pending.put(file, retry);
        publish();
    }

    /**
     * Clears only the file that was successfully written. Other failures remain
     * visible. Entries of that file this build could not read are gone from it
     * once it is rewritten, so their notice goes with them.
     */
    public synchronized void saved(String file) {
        pending.remove(file);
        if (unreadable.remove(file) != null) {
            publishUnreadable();
        }
        publish();
    }

    /** Files whose latest write failed. Names contain no configuration values or credentials. */
    public synchronized List<String> failedFiles() {
        return List.copyOf(pending.keySet());
    }

    /** Observable flag for the application's unsaved-changes banner. */
    public ReadOnlyBooleanProperty unsavedProperty() {
        return unsaved.getReadOnlyProperty();
    }

    /**
     * Records entries of {@code file} that this build could not read.
     *
     * <p>Loading keeps what it understands and reports the rest here. It is
     * worth telling the user about rather than logging: the next save rewrites
     * the file without those entries, so a rollback that was recoverable
     * stops being so the moment they edit anything.</p>
     */
    public synchronized void couldNotRead(String file, int entries) {
        if (entries <= 0) {
            return;
        }
        unreadable.merge(file, entries, Integer::sum);
        publishUnreadable();
    }

    /** How many entries of each file this build could not read. */
    public synchronized Map<String, Integer> unreadableEntries() {
        return Map.copyOf(unreadable);
    }

    /** The files with unreadable entries, as the banner names them. */
    public ReadOnlyStringProperty unreadableFilesProperty() {
        return unreadableFiles.getReadOnlyProperty();
    }

    /** Observable flag for the banner about entries this build cannot read. */
    public ReadOnlyBooleanProperty hasUnreadableProperty() {
        return hasUnreadable.getReadOnlyProperty();
    }

    /** Retries each failed writer once, without holding this lock during disk or keychain I/O. */
    public void retry() {
        List<Runnable> writes;
        synchronized (this) {
            writes = List.copyOf(pending.values());
        }
        writes.forEach(Runnable::run);
    }

    private void publishUnreadable() {
        boolean any = !unreadable.isEmpty();
        String files = String.join(", ", unreadable.keySet());
        onFxThread(() -> {
            hasUnreadable.set(any);
            unreadableFiles.set(files);
        });
    }

    private void publish() {
        long version = ++publication;
        boolean value = !pending.isEmpty();
        onFxThread(() -> {
            synchronized (this) {
                if (version == publication) {
                    unsaved.set(value);
                }
            }
        });
    }

    /**
     * Runs {@code update} where a JavaFX property may be set: on the FX thread
     * when there is one, inline when the toolkit is not running -- config is
     * loaded before it starts, and during tests there is no toolkit at all.
     */
    private static void onFxThread(Runnable update) {
        try {
            if (Platform.isFxApplicationThread()) {
                update.run();
            } else {
                Platform.runLater(update);
            }
        } catch (IllegalStateException toolkitNotRunning) {
            update.run();
        }
    }
}
