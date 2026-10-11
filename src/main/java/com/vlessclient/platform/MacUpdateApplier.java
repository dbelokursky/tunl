package com.vlessclient.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Replaces the installed {@code .app} bundle from a verified DMG.
 *
 * <p>The whole bundle is copied, never patched in place. Swapping only the
 * shaded jar inside {@code Contents/app} would move a fraction of the bytes,
 * but it breaks the bundle's code signature seal — and the release workflow is
 * already wired to sign and notarize the DMG as soon as the certificates
 * exist, so an updater that invalidates signatures would start failing exactly
 * when signing starts working.</p>
 */
final class MacUpdateApplier implements UpdateApplier {

    private static final Logger log = LoggerFactory.getLogger(MacUpdateApplier.class);

    /**
     * Mounts the DMG, copies the bundle out of it and swaps it in, then
     * relaunches. Arguments arrive as argv rather than being interpolated into
     * the text, so paths with spaces need no quoting anywhere.
     *
     * <p>Every failure path ends in {@code open "$DMG"}: if the automatic swap
     * cannot be completed, the user gets the installer they already trusted
     * enough to download, rather than nothing.</p>
     */
    static final String RELAY_SCRIPT = """
            # Applies a Tunl update once the app being replaced has exited.
            # Runs as the body of `bash -c`; never written to disk.
            set -u

            PID="$1"
            DMG="$2"
            TARGET="$3"

            log() { echo "[$(date -u +%Y-%m-%dT%H:%M:%SZ)] $*"; }

            # 30s, not a couple: the user may have asked for this from inside
            # the app while connected, so the exit being waited on includes
            # tearing down a live tunnel.
            log "waiting for pid $PID to exit"
            for _ in $(seq 1 300); do
                kill -0 "$PID" 2>/dev/null || break
                sleep 0.1
            done
            if kill -0 "$PID" 2>/dev/null; then
                log "pid $PID still running after 30s, aborting"
                exit 1
            fi

            MOUNT="$(mktemp -d "${TMPDIR:-/tmp}/tunl-update.XXXXXX")"
            cleanup() {
                hdiutil detach "$MOUNT" -quiet 2>/dev/null || true
                rmdir "$MOUNT" 2>/dev/null || true
            }
            trap cleanup EXIT

            if ! hdiutil attach "$DMG" -nobrowse -readonly -noautoopen \\
                    -mountpoint "$MOUNT" -quiet; then
                log "cannot mount $DMG"
                open "$DMG"
                exit 1
            fi

            BUNDLE="$(basename "$TARGET")"
            SRC="$MOUNT/$BUNDLE"
            if [ ! -d "$SRC" ]; then
                log "no $BUNDLE inside the image"
                open "$DMG"
                exit 1
            fi

            NEW="$TARGET.new"
            OLD="$TARGET.old"
            rm -rf "$NEW" "$OLD"
            if ! ditto "$SRC" "$NEW"; then
                log "copy out of the image failed"
                rm -rf "$NEW"
                open "$DMG"
                exit 1
            fi

            # Move the old bundle aside before moving the new one in, and put it
            # back if that second move fails: a half-replaced bundle is a worse
            # outcome than a stale one.
            if ! mv "$TARGET" "$OLD"; then
                log "cannot move $TARGET aside (no write permission?)"
                rm -rf "$NEW"
                open "$DMG"
                exit 1
            fi
            if ! mv "$NEW" "$TARGET"; then
                log "swap failed, rolling back"
                mv "$OLD" "$TARGET"
                rm -rf "$NEW"
                open "$DMG"
                exit 1
            fi
            rm -rf "$OLD"

            # Unsigned builds inherit no quarantine flag from an HTTP download,
            # but a stray one would make the freshly installed app unopenable.
            xattr -dr com.apple.quarantine "$TARGET" 2>/dev/null || true

            log "installed $TARGET, relaunching"
            open -a "$TARGET"
            """;

    @Override
    public Outcome apply(PendingUpdate update) {
        Path launcher = InstalledApp.launcher();
        Path bundle = launcher == null ? null : bundleRoot(launcher);
        Hold hold = hold(launcher, Files::isWritable);
        if (bundle == null || hold != Hold.NONE) {
            log.warn("Not installing update {} here ({}): {}", update.version(), hold,
                    hold == Hold.MOVE_TO_APPLICATIONS
                            ? "it runs from where it was downloaded; move Tunl to "
                                    + "/Applications first"
                            : "not a packaged build, or its folder cannot be written");
            return Outcome.UNSUPPORTED;
        }

        try {
            // Passed as the -c body rather than written to a file, so nothing
            // lands on disk between here and the run that could be swapped
            // for something else. Arguments still ride argv: bash -c binds
            // the name after the body to $0 and the rest to $1..$3, so no
            // path is ever interpolated into the script text.
            new ProcessBuilder("/bin/bash", "-c", RELAY_SCRIPT,
                    "tunl-update",
                    String.valueOf(ProcessHandle.current().pid()),
                    update.installer().toString(),
                    bundle.toString())
                    .redirectErrorStream(true)
                    // The relay outlives this process, so its output is the
                    // only account of what happened.
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(
                            UpdateHelperLog.file().toFile()))
                    .start();

            log.info("Update {} handed off to the installer relay", update.version());
            return Outcome.HANDED_OFF;
        } catch (IOException e) {
            log.error("Failed to start the update relay: {}", e.getMessage());
            return Outcome.FAILED;
        }
    }

    /**
     * Not while the app runs from where it was downloaded. Such a copy used
     * to count as self-updating: it fetched the installer, offered a restart,
     * refused the swap at every launch, and fetched it again a week later.
     */
    @Override
    public boolean selfUpdates() {
        return hold() == Hold.NONE;
    }

    @Override
    public Hold hold() {
        return hold(InstalledApp.launcher(), Files::isWritable);
    }

    /**
     * What keeps the bundle around {@code launcher} from being replaced.
     *
     * <p>The relay swaps the bundle with {@code ditto} and {@code mv} as this
     * user, so both the bundle and the folder it sits in must be writable. A
     * standard account running a Tunl an administrator put in
     * {@code /Applications} counted as self-updating: it downloaded every
     * release, and every handoff failed at the copy and quit the app.</p>
     *
     * @param launcher the launcher executable, or {@code null} when not packaged
     * @param writable whether a path can be written
     * @return what keeps it from updating itself, or {@link Hold#NONE}
     */
    static Hold hold(Path launcher, Predicate<Path> writable) {
        Path bundle = launcher == null ? null : bundleRoot(launcher);
        if (bundle == null) {
            return Hold.INSTALL_MANUALLY;
        }
        if (InstalledApp.runsFromDownload(bundle, writable)) {
            return Hold.MOVE_TO_APPLICATIONS;
        }
        Path folder = bundle.getParent();
        if (folder == null || !writable.test(folder) || !writable.test(bundle)) {
            return Hold.INSTALL_MANUALLY;
        }
        return Hold.NONE;
    }

    /**
     * Walks up from {@code Tunl.app/Contents/MacOS/Tunl} to {@code Tunl.app}.
     *
     * @param launcher the launcher executable inside the bundle
     * @return the bundle root, or {@code null} when the launcher is not in one
     */
    static Path bundleRoot(Path launcher) {
        Path macOsDir = launcher.getParent();
        Path contents = macOsDir == null ? null : macOsDir.getParent();
        Path bundle = contents == null ? null : contents.getParent();
        if (bundle == null || bundle.getFileName() == null) {
            return null;
        }
        return bundle.getFileName().toString().endsWith(".app") ? bundle : null;
    }
}
