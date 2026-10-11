package com.vlessclient.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The appliers hand a swap to a helper that runs after this process is gone,
 * which is the worst possible place for a mistake: nothing is left to report
 * it, and the app the user double-clicks may be the thing that broke. These
 * tests cover the parts that can be checked before that point — where the
 * installed bundle is, when not to touch it, and whether the generated scripts
 * are well-formed and correctly quoted.
 */
class UpdateApplierTest {

    // -- macOS: finding the bundle to replace --

    @Test
    void bundleRootWalksUpFromTheLauncherInsideTheBundle() {
        Path launcher = Path.of("/Applications/Tunl.app/Contents/MacOS/Tunl");

        assertThat(MacUpdateApplier.bundleRoot(launcher))
                .isEqualTo(Path.of("/Applications/Tunl.app"));
    }

    @Test
    void bundleRootRefusesALauncherThatIsNotInsideABundle() {
        // Deleting three levels up from an arbitrary executable is exactly the
        // guess this must never make.
        assertThat(MacUpdateApplier.bundleRoot(Path.of("/usr/local/bin/tunl"))).isNull();
        assertThat(MacUpdateApplier.bundleRoot(Path.of("/opt/tunl/bin/tunl"))).isNull();
    }

    @Test
    void translocatedCopiesAreRecognised() {
        // Gatekeeper runs unsigned apps from a read-only random mount; updating
        // that copy would leave the real one untouched.
        assertThat(InstalledApp.isTranslocated(Path.of(
                "/private/var/folders/ab/AppTranslocation/1234-5678/d/Tunl.app"))).isTrue();
        assertThat(InstalledApp.isTranslocated(Path.of("/Applications/Tunl.app"))).isFalse();
        // A directory whose name merely starts the same way is not a mount.
        assertThat(InstalledApp.isTranslocated(
                Path.of("/Users/dev/AppTranslocationTests/Tunl.app"))).isFalse();
        assertThat(InstalledApp.isTranslocated(null)).isFalse();
    }

    /**
     * A copy running from where it was downloaded cannot be updated in place,
     * and moving it to Applications is what fixes that: a translocated copy (a
     * quarantined app opened where it lies) or the disk image itself, which is
     * mounted read-only.
     */
    @Test
    void aCopyRunningFromTheDownloadIsRecognised() {
        assertThat(InstalledApp.runsFromDownload(Path.of(
                "/private/var/folders/ab/AppTranslocation/1234-5678/d/Tunl.app"), dir -> true))
                .isTrue();
        assertThat(InstalledApp.runsFromDownload(Path.of("/Volumes/Tunl/Tunl.app"), dir -> false))
                .as("the mounted disk image").isTrue();
        assertThat(InstalledApp.runsFromDownload(Path.of("/Volumes/Apps/Tunl.app"), dir -> true))
                .as("a drive the user keeps apps on").isFalse();
        assertThat(InstalledApp.runsFromDownload(Path.of("/Applications/Tunl.app"), dir -> false))
                .as("an Applications folder this account cannot write is no download")
                .isFalse();
        assertThat(InstalledApp.runsFromDownload(null, dir -> true)).isFalse();
    }

    /**
     * Such a copy used to count as self-updating: it fetched the installer
     * (about a hundred megabytes), announced a restart, and at the restart
     * refused to touch the copy, then fetched it again a week later. Now it
     * fetches nothing and says why.
     */
    @Test
    void aLaunchFromTheDownloadNeitherFetchesNorPromisesAnUpdate(@TempDir Path tmp)
            throws IOException {
        Path translocated = tmp.resolve("AppTranslocation/1234/d/Tunl.app/Contents/MacOS/Tunl");
        Path installed = tmp.resolve("Applications/Tunl.app/Contents/MacOS/Tunl");
        for (Path launcher : List.of(translocated, installed)) {
            Files.createDirectories(launcher.getParent());
            Files.writeString(launcher, "");
        }
        String prior = System.getProperty(InstalledApp.APP_PATH_PROPERTY);
        try {
            System.setProperty(InstalledApp.APP_PATH_PROPERTY, translocated.toString());
            assertThat(new MacUpdateApplier().hold())
                    .isEqualTo(UpdateApplier.Hold.MOVE_TO_APPLICATIONS);
            assertThat(new MacUpdateApplier().selfUpdates()).isFalse();

            System.setProperty(InstalledApp.APP_PATH_PROPERTY, installed.toString());
            assertThat(new MacUpdateApplier().hold()).isEqualTo(UpdateApplier.Hold.NONE);
            assertThat(new MacUpdateApplier().selfUpdates()).isTrue();
        } finally {
            if (prior == null) {
                System.clearProperty(InstalledApp.APP_PATH_PROPERTY);
            } else {
                System.setProperty(InstalledApp.APP_PATH_PROPERTY, prior);
            }
        }
    }

    @Test
    void macRelayScriptIsValidBash() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/bash")), "no /bin/bash on this host");

        // A syntax error in the script only surfaces during a real update,
        // when the app is already on its way out. Checked the way it runs:
        // as a -c body, not a file.
        assertThat(run(List.of("/bin/bash", "-n", "-c", MacUpdateApplier.RELAY_SCRIPT)))
                .isZero();
    }

    @Test
    void macRelayScriptRollsBackAndFallsBackToTheInstaller() {
        // Two properties worth pinning: a failed swap restores the bundle it
        // moved aside, and every dead end still gives the user the installer.
        assertThat(MacUpdateApplier.RELAY_SCRIPT).contains("mv \"$OLD\" \"$TARGET\"");
        assertThat(MacUpdateApplier.RELAY_SCRIPT).contains("open \"$DMG\"");
    }

    // -- Windows: quoting the paths baked into the relay --

    @Test
    void windowsRelayScriptCannotBeEscapedByAPathWithAQuoteInIt() {
        // -EncodedCommand carries no argv, so both paths are text inside the
        // script. A per-user install path contains the Windows username, and a
        // username may legally contain a quote — the case that would otherwise
        // turn a path into code. (psLiteral itself is WindowsTunLauncher's,
        // and its own tests prove the escaping.)
        String script = WindowsUpdateApplier.relayScript(1,
                Path.of("C:\\Users\\O'Brien\\update\\tunl.msi"),
                Path.of("C:\\Users\\O'Brien\\Tunl.exe"));

        assertThat(script).contains("$msi = 'C:\\Users\\O''Brien\\update\\tunl.msi'");
        assertThat(script).contains("$exe = 'C:\\Users\\O''Brien\\Tunl.exe'");
    }

    @Test
    void windowsRelayScriptCarriesQuotedPathsAndTheRightPid() {
        String script = WindowsUpdateApplier.relayScript(4242,
                Path.of("C:\\Users\\Dev User\\AppData\\Local\\Tunl\\update\\tunl.msi"),
                Path.of("C:\\Users\\Dev User\\AppData\\Local\\Tunl\\Tunl.exe"));

        assertThat(script).contains("$waitFor = 4242");
        assertThat(script).contains("$msi = 'C:\\Users\\Dev User\\AppData"
                + "\\Local\\Tunl\\update\\tunl.msi'");
        assertThat(script).contains("$exe = 'C:\\Users\\Dev User\\AppData\\Local\\Tunl\\Tunl.exe'");
        // $pid is PowerShell's own process id; using it as the name of the
        // process to wait for would make the script wait on itself.
        assertThat(script).doesNotContain("$pid ");
    }

    /**
     * The PowerShell literal above is only half of it. Start-Process joins an
     * -ArgumentList array with spaces and quotes nothing, so this path reached
     * msiexec as "/i C:\Users\Dev User\...": the package was "C:\Users\Dev"
     * and every update failed for anyone with a space in their user name. The
     * path has to reach msiexec inside double quotes, as one argument.
     */
    @Test
    void windowsRelayHandsMsiexecThePathAsOneQuotedArgument() {
        String script = WindowsUpdateApplier.relayScript(4242,
                Path.of("C:\\Users\\Dev User\\AppData\\Local\\Tunl\\update\\tunl.msi"),
                Path.of("C:\\Users\\Dev User\\AppData\\Local\\Tunl\\Tunl.exe"));

        assertThat(script).contains("-ArgumentList ('/i \"{0}\" /qb /norestart' -f $msi)");
        assertThat(script).doesNotContain("'/i', $msi");
    }

    // -- Linux: deliberately does nothing --

    @Test
    void linuxDefersToThePackageManager() {
        PendingUpdate update = new PendingUpdate("1.6.0", Path.of("/tmp/tunl.deb"),
                "sha256:0000000000000000000000000000000000000000000000000000000000000000");

        assertThat(new LinuxUpdateApplier().apply(update))
                .isEqualTo(UpdateApplier.Outcome.UNSUPPORTED);
    }

    /**
     * What this drives: where the answer is false, the updater does not spend
     * a hundred megabytes on a file it could never install. This JVM is no
     * installed package, and macOS and Windows used to claim to update it
     * anyway: a jar or development run downloaded an installer that apply()
     * then refused, and fetched it again every week.
     */
    @Test
    void anUnpackagedRunUpdatesItselfNowhere() {
        assertThat(InstalledApp.launcher()).as("tests run outside a package").isNull();

        assertThat(new MacUpdateApplier().selfUpdates()).isFalse();
        assertThat(new MacUpdateApplier().hold()).isEqualTo(UpdateApplier.Hold.INSTALL_MANUALLY);
        assertThat(new WindowsUpdateApplier().selfUpdates()).isFalse();
        assertThat(new WindowsUpdateApplier().hold())
                .isEqualTo(UpdateApplier.Hold.INSTALL_MANUALLY);
        assertThat(new LinuxUpdateApplier().selfUpdates()).isFalse();
    }

    @Test
    void anInstalledWindowsBuildUpdatesItself(@TempDir Path tmp) throws IOException {
        Path launcher = tmp.resolve("Tunl/Tunl.exe");
        Files.createDirectories(launcher.getParent());
        Files.writeString(launcher, "");
        String prior = System.getProperty(InstalledApp.APP_PATH_PROPERTY);
        try {
            System.setProperty(InstalledApp.APP_PATH_PROPERTY, launcher.toString());
            assertThat(new WindowsUpdateApplier().selfUpdates()).isTrue();
            assertThat(new WindowsUpdateApplier().hold()).isEqualTo(UpdateApplier.Hold.NONE);
        } finally {
            if (prior == null) {
                System.clearProperty(InstalledApp.APP_PATH_PROPERTY);
            } else {
                System.setProperty(InstalledApp.APP_PATH_PROPERTY, prior);
            }
        }
    }

    /**
     * The relay replaces the bundle with ditto and mv as this user. A standard
     * account running a Tunl an administrator installed in /Applications
     * counted as self-updating: every release was downloaded, and every
     * handoff failed at the copy and quit the app.
     */
    @Test
    void aMacBundleThisAccountCannotReplaceIsLeftToTheUser() {
        Path launcher = Path.of("/Applications/Tunl.app/Contents/MacOS/Tunl");
        Path bundle = Path.of("/Applications/Tunl.app");
        Path folder = Path.of("/Applications");

        assertThat(MacUpdateApplier.hold(launcher, p -> true)).isEqualTo(UpdateApplier.Hold.NONE);
        assertThat(MacUpdateApplier.hold(launcher, p -> !p.equals(folder)))
                .isEqualTo(UpdateApplier.Hold.INSTALL_MANUALLY);
        assertThat(MacUpdateApplier.hold(launcher, p -> !p.equals(bundle)))
                .isEqualTo(UpdateApplier.Hold.INSTALL_MANUALLY);
        assertThat(MacUpdateApplier.hold(null, p -> true))
                .isEqualTo(UpdateApplier.Hold.INSTALL_MANUALLY);
        assertThat(MacUpdateApplier.hold(Path.of("/opt/tunl/bin/Tunl"), p -> true))
                .as("not inside a bundle at all")
                .isEqualTo(UpdateApplier.Hold.INSTALL_MANUALLY);
    }

    private static int run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("timed out: " + command);
        }
        return process.exitValue();
    }
}
