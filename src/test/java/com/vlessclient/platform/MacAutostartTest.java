package com.vlessclient.platform;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MacAutostartTest {

    private static final String PLIST_NAME = "com.vlessclient.client.plist";

    private static final int UID = 501;

    @TempDir
    Path tempDir;

    /**
     * Records every launchctl invocation; {@code print-disabled} answers with
     * {@code disabled}, every other command succeeds.
     */
    private static final class FakeLaunchctl implements CommandRunner {
        final List<List<String>> calls = new ArrayList<>();
        String disabled;

        FakeLaunchctl(String disabled) {
            this.disabled = disabled;
        }

        @Override
        public CommandRunner.Result run(List<String> command) {
            calls.add(List.copyOf(command));
            if (command.get(1).equals("print-disabled")) {
                return new CommandRunner.Result(0, disabled);
            }
            return new CommandRunner.Result(0, "");
        }
    }

    /** What {@code launchctl print-disabled gui/<uid>} prints since macOS 13. */
    private static String printDisabled(String... entries) {
        StringBuilder out = new StringBuilder("disabled services = {\n");
        for (String entry : entries) {
            out.append('\t').append(entry).append('\n');
        }
        return out.append("}\n\nlogin item associations = {\n}\n").toString();
    }

    /** An autostart over {@code dir} whose launchd has switched nothing off. */
    private static MacAutostart autostart(Path dir) {
        return new MacAutostart(dir, new FakeLaunchctl(printDisabled()), () -> UID);
    }

    @Test
    void isEnabled_falseWhenPlistAbsent() {
        assertThat(autostart(tempDir).isEnabled()).isFalse();
    }

    @Test
    void setEnabledTrue_writesPlistAndReportsEnabled() throws IOException {
        MacAutostart service = autostart(tempDir);

        service.setEnabled(true);

        assertThat(service.isEnabled()).isTrue();
        String plist = Files.readString(tempDir.resolve(PLIST_NAME));
        assertThat(plist).contains("<key>Label</key>");
        assertThat(plist).contains("<key>RunAtLoad</key>");
        assertThat(plist).contains("<true/>");
        assertThat(plist).contains("com.vlessclient.app.Launcher");
    }

    @Test
    void setEnabledFalse_removesPlist() throws IOException {
        MacAutostart service = autostart(tempDir);
        service.setEnabled(true);
        assertThat(service.isEnabled()).isTrue();

        service.setEnabled(false);

        assertThat(service.isEnabled()).isFalse();
        assertThat(tempDir.resolve(PLIST_NAME)).doesNotExist();
    }

    @Test
    void setEnabledFalse_whenAlreadyDisabledIsNoOp() throws IOException {
        MacAutostart service = autostart(tempDir);

        service.setEnabled(false);

        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void setEnabledTrue_createsLaunchAgentsDirWhenMissing() throws IOException {
        Path missingDir = tempDir.resolve("Library").resolve("LaunchAgents");
        MacAutostart service = autostart(missingDir);

        service.setEnabled(true);

        assertThat(service.isEnabled()).isTrue();
    }

    @Test
    void refresh_rewritesPlistWhenEnabled() throws IOException {
        MacAutostart service = autostart(tempDir);
        service.setEnabled(true);
        Path plist = tempDir.resolve(PLIST_NAME);
        Files.writeString(plist, "stale");

        service.refresh();

        assertThat(Files.readString(plist)).contains("com.vlessclient.app.Launcher");
    }

    @Test
    void refresh_doesNothingWhenDisabled() {
        MacAutostart service = autostart(tempDir);

        service.refresh();

        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void buildLaunchCommand_reconstructsJavaInvocation() {
        List<String> command = JvmLaunchCommand.current();

        assertThat(command).isNotEmpty();
        assertThat(command.getFirst()).endsWith("java");
        assertThat(command).contains("-cp");
        assertThat(command.getLast()).isEqualTo("com.vlessclient.app.Launcher");
    }

    @Test
    void buildPlist_escapesXmlSpecialCharacters() {
        String plist = MacAutostart.buildPlist(List.of("/opt/a & b/<x>"));

        assertThat(plist).contains("/opt/a &amp; b/&lt;x&gt;");
    }

    /**
     * An installed app has no {@code bin/java}: jlink strips it from the
     * runtime every installer ships. A login item built from the JVM's own
     * command line therefore named a file that does not exist, and launchd
     * gave up at every login ("spawn failed", EX_CONFIG) while Settings showed
     * the box ticked.
     */
    @Test
    void anInstalledAppStartsAtLoginThroughItsOwnLauncher() throws IOException {
        Path launcher = fakeLauncher(tempDir.resolve("Applications"));

        withAppPath(launcher, () -> autostart(tempDir).setEnabled(true));

        assertThat(programArguments(Files.readString(tempDir.resolve(PLIST_NAME))))
                .containsExactly(launcher.toString());
    }

    /**
     * {@code refresh()} runs at every start, so the first start of a fixed
     * build repairs the login item an older build wrote.
     */
    @Test
    void aLoginItemThatNamesAMissingJavaIsRepairedAtTheNextStart() throws IOException {
        Path launcher = fakeLauncher(tempDir.resolve("Applications"));
        Path plist = tempDir.resolve(PLIST_NAME);
        Files.writeString(plist, MacAutostart.buildPlist(List.of(
                launcher.getParent().getParent().resolve("runtime/Contents/Home/bin/java")
                        .toString(),
                "-cp", "app.jar", "com.vlessclient.app.Launcher")));

        withAppPath(launcher, () -> autostart(tempDir).refresh());

        assertThat(programArguments(Files.readString(plist)))
                .containsExactly(launcher.toString());
    }

    /**
     * A quarantined app started from Downloads or from the disk image runs
     * from a randomized mount macOS removes again. A login item naming it
     * would fail at the next login just the same, so it is refused, and an
     * item written by a proper install is left as it is.
     */
    @Test
    void aTranslocatedCopyDoesNotWriteItsTemporaryPathIntoTheLoginItem() throws IOException {
        Path installed = fakeLauncher(tempDir.resolve("Applications"));
        Path translocated = fakeLauncher(tempDir.resolve("private/var/folders/xy/T")
                .resolve("AppTranslocation").resolve("5C1A0F2E").resolve("d"));
        Path plist = tempDir.resolve(PLIST_NAME);
        withAppPath(installed, () -> autostart(tempDir).setEnabled(true));
        String written = Files.readString(plist);

        withAppPath(translocated, () -> {
            MacAutostart service = autostart(tempDir);
            assertThatThrownBy(() -> service.setEnabled(true))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Applications");
            service.refresh();
        });

        assertThat(Files.readString(plist)).isEqualTo(written);
    }

    /**
     * System Settings, General, Login Items ("Allow in the Background")
     * switches the agent off without touching its plist; launchd keeps the
     * label as disabled for the user's session. Only the file was read, so
     * Settings kept the box ticked for an agent launchd no longer starts.
     */
    @Test
    void anAgentSwitchedOffInLoginItemsIsOff() throws IOException {
        autostart(tempDir).setEnabled(true);
        FakeLaunchctl launchctl = new FakeLaunchctl(printDisabled(
                "\"com.apple.Siri.agent\" => enabled",
                "\"com.vlessclient.client\" => disabled"));

        assertThat(new MacAutostart(tempDir, launchctl, () -> UID).isEnabled()).isFalse();
        assertThat(launchctl.calls)
                .containsExactly(List.of("launchctl", "print-disabled", "gui/" + UID));
        // Before macOS 13 the same switch printed as true.
        launchctl.disabled = printDisabled("\"com.vlessclient.client\" => true");
        assertThat(new MacAutostart(tempDir, launchctl, () -> UID).isEnabled()).isFalse();
    }

    @Test
    void anAgentLaunchdLeftOnOrCannotSpeakForIsOn() throws IOException {
        autostart(tempDir).setEnabled(true);

        for (String answer : List.of(
                printDisabled("\"com.vlessclient.client\" => enabled"),
                printDisabled("\"com.vlessclient.client\" => false"),
                printDisabled("\"com.vlessclient.client.other\" => disabled"),
                "")) {
            assertThat(new MacAutostart(tempDir, new FakeLaunchctl(answer), () -> UID)
                    .isEnabled()).as(answer).isTrue();
        }
        // A launchctl that fails, or no uid to name the session by, leaves
        // the file to decide, as before.
        CommandRunner failing = command -> new CommandRunner.Result(113, "Bad request.");
        assertThat(new MacAutostart(tempDir, failing, () -> UID).isEnabled()).isTrue();
        CommandRunner missing = command -> {
            throw new IOException("launchctl not found");
        };
        assertThat(new MacAutostart(tempDir, missing, () -> UID).isEnabled()).isTrue();
        FakeLaunchctl unasked = new FakeLaunchctl(
                printDisabled("\"com.vlessclient.client\" => disabled"));
        assertThat(new MacAutostart(tempDir, unasked, () -> -1).isEnabled()).isTrue();
        assertThat(unasked.calls).isEmpty();
    }

    @Test
    void refreshLeavesAnAgentSwitchedOffInLoginItemsAlone() throws IOException {
        Path plist = tempDir.resolve(PLIST_NAME);
        Files.writeString(plist, "stale");
        FakeLaunchctl launchctl = new FakeLaunchctl(
                printDisabled("\"com.vlessclient.client\" => disabled"));

        new MacAutostart(tempDir, launchctl, () -> UID).refresh();

        assertThat(Files.readString(plist)).isEqualTo("stale");
    }

    /**
     * Ticking the box only rewrote the plist, and launchd's switch, which
     * wins, still said off: the user's re-enable did nothing.
     */
    @Test
    void enablingClearsLaunchdsSwitch() throws IOException {
        FakeLaunchctl launchctl = new FakeLaunchctl(
                printDisabled("\"com.vlessclient.client\" => disabled"));

        new MacAutostart(tempDir, launchctl, () -> UID).setEnabled(true);

        assertThat(launchctl.calls).contains(
                List.of("launchctl", "enable", "gui/" + UID + "/com.vlessclient.client"));
        assertThat(tempDir.resolve(PLIST_NAME)).isRegularFile();
    }

    /** A switch launchd does not hold is not written into its database. */
    @Test
    void enablingLeavesLaunchdAloneWhenNothingIsSwitchedOff() throws IOException {
        FakeLaunchctl launchctl = new FakeLaunchctl(printDisabled());

        new MacAutostart(tempDir, launchctl, () -> UID).setEnabled(true);

        assertThat(launchctl.calls).noneSatisfy(call -> assertThat(call).contains("enable"));
    }

    /** launchd names the session by the process's uid, which Java has no API for. */
    @Test
    void theSessionIsNamedByThisProcesssUid() throws IOException {
        Assumptions.assumeTrue(Platform.current() != Platform.WINDOWS, "no getuid on Windows");
        Path mine = Files.writeString(tempDir.resolve("mine"), "");

        assertThat(MacAutostart.processUid()).isEqualTo(Files.getAttribute(mine, "unix:uid"));
    }

    /** A jpackage launcher at {@code <dir>/Tunl.app/Contents/MacOS/Tunl}. */
    private static Path fakeLauncher(Path dir) throws IOException {
        Path launcher = dir.resolve("Tunl.app").resolve("Contents").resolve("MacOS")
                .resolve("Tunl");
        Files.createDirectories(launcher.getParent());
        return Files.writeString(launcher, "#!/bin/sh\n");
    }

    /** The launcher sets {@code jpackage.app-path} to itself; tests stand in for it. */
    private static void withAppPath(Path launcher, IoAction action) throws IOException {
        String previous = System.getProperty(InstalledApp.APP_PATH_PROPERTY);
        System.setProperty(InstalledApp.APP_PATH_PROPERTY, launcher.toString());
        try {
            action.run();
        } finally {
            if (previous == null) {
                System.clearProperty(InstalledApp.APP_PATH_PROPERTY);
            } else {
                System.setProperty(InstalledApp.APP_PATH_PROPERTY, previous);
            }
        }
    }

    private static List<String> programArguments(String plist) {
        String array = plist.substring(plist.indexOf("<array>"), plist.indexOf("</array>"));
        return Pattern.compile("<string>(.*?)</string>").matcher(array).results()
                .map(match -> match.group(1))
                .toList();
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }
}
