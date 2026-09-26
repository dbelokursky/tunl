package com.vlessclient.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/** Tracks failed config writes and retries the latest in-memory state of each affected file. */
public final class PersistenceState {

    private final Map<String, Runnable> pending = new LinkedHashMap<>();
    private final Map<String, Integer> unreadable = new LinkedHashMap<>();
    private final ReadOnlyBooleanWrapper unsaved = new ReadOnlyBooleanWrapper();
    private final ReadOnlyBooleanWrapper hasUnreadable = new ReadOnlyBooleanWrapper();
    private final ReadOnlyStringWrapper unreadableFiles = new ReadOnlyStringWrapper("");
    /** Files that could not be opened at startup, and why; they are never written. */
    private final Map<String, String> held = new LinkedHashMap<>();
    /** Damaged files moved aside at startup, and where each is now. */
    private final Map<String, String> setAside = new LinkedHashMap<>();
    private final ReadOnlyObjectWrapper<Map<String, String>> heldFiles =
            new ReadOnlyObjectWrapper<>(Map.of());
    private final ReadOnlyObjectWrapper<Map<String, String>> setAsideFiles =
            new ReadOnlyObjectWrapper<>(Map.of());
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

    /**
     * Records a file that is there and could not be opened. It is left as it
     * is, and no save writes over it until the app is started again: over it,
     * a save would replace data the app never saw with what it started
     * without.
     *
     * @param file   the file's name
     * @param reason why it could not be opened
     */
    public synchronized void couldNotOpen(String file, String reason) {
        held.put(file, reason);
        publishFileNotices();
    }

    /**
     * Lets saves write {@code file} again: the file the hold protected is
     * gone, because the user cleared it.
     *
     * @param file the file's name
     */
    public synchronized void released(String file) {
        if (held.remove(file) != null) {
            publishFileNotices();
        }
    }

    /**
     * Whether saves of {@code file} are held, because it could not be opened.
     *
     * @param file the file's name
     * @return true when no save may write it
     */
    public synchronized boolean isHeld(String file) {
        return held.containsKey(file);
    }

    /**
     * Records a damaged file moved aside, so the user hears of it: the app
     * started without it, which looks like data loss unless it says where the
     * file went.
     *
     * @param file  the file's name
     * @param where the path it has now
     */
    public synchronized void setAside(String file, String where) {
        setAside.put(file, where);
        publishFileNotices();
    }

    /** Clears the notices of files set aside, once the user has read them. */
    public synchronized void dismissSetAside() {
        setAside.clear();
        publishFileNotices();
    }

    /** The files that could not be opened at startup, each with why, in that order. */
    public synchronized Map<String, String> heldReasons() {
        return snapshot(held);
    }

    /** The damaged files set aside and not yet dismissed, each with where it is now. */
    public synchronized Map<String, String> setAsideLocations() {
        return snapshot(setAside);
    }

    /** {@link #heldReasons()} for the banner, set on the FX thread. */
    public ReadOnlyObjectProperty<Map<String, String>> heldFilesProperty() {
        return heldFiles.getReadOnlyProperty();
    }

    /** {@link #setAsideLocations()} for the banner, set on the FX thread. */
    public ReadOnlyObjectProperty<Map<String, String>> setAsideFilesProperty() {
        return setAsideFiles.getReadOnlyProperty();
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

    private void publishFileNotices() {
        Map<String, String> heldNow = snapshot(held);
        Map<String, String> setAsideNow = snapshot(setAside);
        onFxThread(() -> {
            heldFiles.set(heldNow);
            setAsideFiles.set(setAsideNow);
        });
    }

    private static Map<String, String> snapshot(Map<String, String> files) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(files));
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
     * loaded before it starts, during tests there is no toolkit at all, and a
     * shutdown hook may run in a JVM that never started one.
     */
    private static void onFxThread(Runnable update) {
        try {
            if (FxExecutor.isFxThread()) {
                update.run();
            } else {
                Platform.runLater(update);
            }
        } catch (IllegalStateException toolkitNotRunning) {
            update.run();
        }
    }
}
