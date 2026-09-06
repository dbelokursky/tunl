package com.vlessclient.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;

/** Tracks failed config writes and retries the latest in-memory state of each affected file. */
public final class PersistenceState {

    private final Map<String, Runnable> pending = new LinkedHashMap<>();
    private final ReadOnlyBooleanWrapper unsaved = new ReadOnlyBooleanWrapper();
    private long publication;

    /** Records a failed write. Retry must save current state, not an old serialized payload. */
    public synchronized void failed(String file, Runnable retry) {
        pending.put(file, retry);
        publish();
    }

    /** Clears only the file that was successfully written. Other failures remain visible. */
    public synchronized void saved(String file) {
        pending.remove(file);
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

    /** Retries each failed writer once, without holding this lock during disk or keychain I/O. */
    public void retry() {
        List<Runnable> writes;
        synchronized (this) {
            writes = List.copyOf(pending.values());
        }
        writes.forEach(Runnable::run);
    }

    private void publish() {
        long version = ++publication;
        boolean value = !pending.isEmpty();
        Runnable update = () -> {
            synchronized (this) {
                if (version == publication) {
                    unsaved.set(value);
                }
            }
        };
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
