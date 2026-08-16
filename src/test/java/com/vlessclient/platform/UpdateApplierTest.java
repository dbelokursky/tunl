package com.vlessclient.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

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

    // -- Linux: deliberately does nothing --

    @Test
    void linuxDefersToThePackageManager() {
        PendingUpdate update = new PendingUpdate("1.6.0", Path.of("/tmp/tunl.deb"),
                "sha256:0000000000000000000000000000000000000000000000000000000000000000");

        assertThat(new LinuxUpdateApplier().apply(update))
                .isEqualTo(UpdateApplier.Outcome.UNSUPPORTED);
    }

    @Test
    void onlyThePlatformsWithAnInstallerPathClaimToSelfUpdate() {
        // What this drives: on a platform that answers false, the updater does
        // not spend a hundred megabytes on a file it could never install.
        assertThat(new MacUpdateApplier().selfUpdates()).isTrue();
        assertThat(new WindowsUpdateApplier().selfUpdates()).isTrue();
        assertThat(new LinuxUpdateApplier().selfUpdates()).isFalse();
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
