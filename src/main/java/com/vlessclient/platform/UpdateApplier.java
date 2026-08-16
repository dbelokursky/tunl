package com.vlessclient.platform;

/**
 * Installs a {@link PendingUpdate} over the running installation.
 *
 * <p>A process cannot overwrite the files it is executing, so no
 * implementation does the work itself: each one hands off to a small helper
 * that waits for this process to exit, performs the swap, and starts the new
 * build. That is the same shape Telegram Desktop's separate {@code Updater}
 * binary has, for the same reason.</p>
 *
 * <p>The handoff happens at startup rather than at quit. Both moments would
 * satisfy "applies on the next restart", but only at startup is there someone
 * left to tell: a swap kicked off while the user is quitting fails in silence,
 * and on a machine that reboots right after, may not run at all.</p>
 */
public interface UpdateApplier {

    /** What {@link #apply} managed to do. */
    enum Outcome {

        /**
         * The helper is running and waiting for this process to exit. The
         * caller must quit promptly and touch nothing else on the way out —
         * the files it would write are about to be replaced.
         */
        HANDED_OFF,

        /**
         * This installation cannot update itself: not a packaged build, a
         * translocated macOS copy, or a Linux package owned by the system
         * package manager. The staged installer is left alone for the user.
         */
        UNSUPPORTED,

        /** The handoff itself failed; the staged installer is still valid. */
        FAILED
    }

    /**
     * Starts the swap for an installer that has just been re-verified.
     *
     * @param update the staged update to install
     * @return what happened; {@link Outcome#HANDED_OFF} obliges the caller to exit
     */
    Outcome apply(PendingUpdate update);

    /**
     * Whether this platform can install updates from inside the app at all.
     *
     * <p>Distinct from {@link Outcome#UNSUPPORTED}, which also covers runtime
     * conditions a given launch happens to be in. This answers the standing
     * question, and the answer is what decides whether downloading an
     * installer is worth doing: where it is false, the bytes would be spent on
     * a file nothing is ever going to run.</p>
     *
     * @return true when an update downloaded here could be applied here
     */
    boolean selfUpdates();

    /**
     * Returns the applier for the host platform.
     *
     * @return the applier for the host platform
     */
    static UpdateApplier current() {
        return switch (Platform.current()) {
            case WINDOWS -> new WindowsUpdateApplier();
            case LINUX -> new LinuxUpdateApplier();
            // OTHER keeps the mac fallback: unix-like defaults beat crashing.
            case MAC, OTHER -> new MacUpdateApplier();
        };
    }
}
