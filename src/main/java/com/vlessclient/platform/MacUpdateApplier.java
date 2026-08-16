package com.vlessclient.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
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

    private static final String SCRIPT_NAME = "apply-update.sh";
    private static final String LOG_NAME = "apply-update.log";

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
            #!/bin/bash
            # Applies a Tunl update once the app being replaced has exited.
            # Written by MacUpdateApplier; regenerated on every update.
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
        if (launcher == null) {
            log.info("Not a packaged build — leaving the staged update in place");
            return Outcome.UNSUPPORTED;
        }
        Path bundle = bundleRoot(launcher);
        if (bundle == null) {
            log.warn("Launcher {} is not inside an .app bundle", launcher);
            return Outcome.UNSUPPORTED;
        }
        if (InstalledApp.isTranslocated(bundle)) {
            log.warn("Running translocated from {} — updating it would replace a "
                    + "throwaway copy. Move Tunl to /Applications first.", bundle);
            return Outcome.UNSUPPORTED;
        }

        try {
            Path workDir = update.installer().getParent();
            Path script = workDir.resolve(SCRIPT_NAME);
            Files.writeString(script, RELAY_SCRIPT);
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));

            // The relay outlives this process, so its output is the only
            // account of what happened. Keep it next to the installer.
            new ProcessBuilder("/bin/bash", script.toString(),
                    String.valueOf(ProcessHandle.current().pid()),
                    update.installer().toString(),
                    bundle.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(
                            workDir.resolve(LOG_NAME).toFile()))
                    .start();

            log.info("Update {} handed off to {}", update.version(), script);
            return Outcome.HANDED_OFF;
        } catch (IOException e) {
            log.error("Failed to start the update relay: {}", e.getMessage());
            return Outcome.FAILED;
        }
    }

    @Override
    public boolean selfUpdates() {
        return true;
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
