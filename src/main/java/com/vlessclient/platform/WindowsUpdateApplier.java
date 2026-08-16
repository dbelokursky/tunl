package com.vlessclient.platform;

import java.io.IOException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Installs a verified MSI over the current installation by handing it to
 * Windows Installer.
 *
 * <p>No file swapping of our own happens here, and none should: the package is
 * built with a fixed {@code --win-upgrade-uuid}, which makes a newer MSI a
 * major upgrade of the installed product — msiexec removes the old files,
 * lays down the new ones and keeps the Start-menu entries pointing at them,
 * with a rollback if any of it fails. It is also a
 * {@code --win-per-user-install}, so none of this raises a UAC prompt.</p>
 */
final class WindowsUpdateApplier implements UpdateApplier {

    private static final Logger log = LoggerFactory.getLogger(WindowsUpdateApplier.class);

    private static final String LOG_NAME = "apply-update.log";

    @Override
    public Outcome apply(PendingUpdate update) {
        Path launcher = InstalledApp.launcher();
        if (launcher == null) {
            log.info("Not a packaged build — leaving the staged update in place");
            return Outcome.UNSUPPORTED;
        }

        try {
            Path workDir = update.installer().getParent();
            String relay = relayScript(
                    ProcessHandle.current().pid(), update.installer(), launcher);

            // The script travels as an -EncodedCommand blob rather than a file,
            // the same way WindowsTunLauncher carries its elevation scripts:
            // nothing is written where it could be swapped between the write
            // and the run. No file also means no -ExecutionPolicy Bypass,
            // which governs script files and would be inert here anyway.
            new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
                    "-EncodedCommand", WindowsTunLauncher.encode(relay))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(
                            workDir.resolve(LOG_NAME).toFile()))
                    .start();

            log.info("Update {} handed off to the installer relay", update.version());
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
     * Builds the relay script. Paths are baked in as PowerShell literals
     * because {@code -EncodedCommand} carries no argv to pass them through —
     * the same trade {@link WindowsTunLauncher} makes, and it rests on the
     * same {@link WindowsTunLauncher#psLiteral} seam: every per-user install
     * path contains the Windows username, and a username may contain a quote.
     *
     * @param pid       the process the script must outlive
     * @param installer the verified MSI
     * @param launcher  the executable to start once the upgrade is done
     * @return the script text
     */
    static String relayScript(long pid, Path installer, Path launcher) {
        // $pid is a PowerShell automatic variable (this shell's own id), hence
        // the deliberately different name.
        return """
                # Applies a Tunl update once the app being replaced has exited.
                # Written by WindowsUpdateApplier; regenerated on every update.
                $ErrorActionPreference = 'Continue'
                $waitFor = %d
                $msi = %s
                $exe = %s

                Write-Output "waiting for pid $waitFor to exit"
                try { Wait-Process -Id $waitFor -Timeout 30 -ErrorAction Stop } catch { }
                if (Get-Process -Id $waitFor -ErrorAction SilentlyContinue) {
                    Write-Output "pid $waitFor still running, aborting"
                    exit 1
                }

                # /qb keeps a progress bar on screen: the app has just vanished,
                # so something has to show that the update is running.
                $installed = Start-Process msiexec `
                    -ArgumentList '/i', $msi, '/qb', '/norestart' -Wait -PassThru
                # 3010 is "success, reboot pending" and is not a failure here.
                if ($installed.ExitCode -ne 0 -and $installed.ExitCode -ne 3010) {
                    Write-Output "msiexec failed with $($installed.ExitCode)"
                    Start-Process $msi
                    exit 1
                }

                Write-Output "installed, relaunching"
                Start-Process $exe
                """.formatted(pid,
                WindowsTunLauncher.psLiteral(installer.toString()),
                WindowsTunLauncher.psLiteral(launcher.toString()));
    }
}
