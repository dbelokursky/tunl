package com.vlessclient.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MacAutostartTest {

    private static final String PLIST_NAME = "com.vlessclient.client.plist";

    @TempDir
    Path tempDir;

    @Test
    void isEnabled_falseWhenPlistAbsent() {
        assertThat(new MacAutostart(tempDir).isEnabled()).isFalse();
    }

    @Test
    void setEnabledTrue_writesPlistAndReportsEnabled() throws IOException {
        MacAutostart service = new MacAutostart(tempDir);

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
        MacAutostart service = new MacAutostart(tempDir);
        service.setEnabled(true);
        assertThat(service.isEnabled()).isTrue();

        service.setEnabled(false);

        assertThat(service.isEnabled()).isFalse();
        assertThat(tempDir.resolve(PLIST_NAME)).doesNotExist();
    }

    @Test
    void setEnabledFalse_whenAlreadyDisabledIsNoOp() throws IOException {
        MacAutostart service = new MacAutostart(tempDir);

        service.setEnabled(false);

        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void setEnabledTrue_createsLaunchAgentsDirWhenMissing() throws IOException {
        Path missingDir = tempDir.resolve("Library").resolve("LaunchAgents");
        MacAutostart service = new MacAutostart(missingDir);

        service.setEnabled(true);

        assertThat(service.isEnabled()).isTrue();
    }

    @Test
    void refresh_rewritesPlistWhenEnabled() throws IOException {
        MacAutostart service = new MacAutostart(tempDir);
        service.setEnabled(true);
        Path plist = tempDir.resolve(PLIST_NAME);
        Files.writeString(plist, "stale");

        service.refresh();

        assertThat(Files.readString(plist)).contains("com.vlessclient.app.Launcher");
    }

    @Test
    void refresh_doesNothingWhenDisabled() {
        MacAutostart service = new MacAutostart(tempDir);

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

        withAppPath(launcher, () -> new MacAutostart(tempDir).setEnabled(true));

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

        withAppPath(launcher, () -> new MacAutostart(tempDir).refresh());

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
        withAppPath(installed, () -> new MacAutostart(tempDir).setEnabled(true));
        String written = Files.readString(plist);

        withAppPath(translocated, () -> {
            MacAutostart service = new MacAutostart(tempDir);
            assertThatThrownBy(() -> service.setEnabled(true))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Applications");
            service.refresh();
        });

        assertThat(Files.readString(plist)).isEqualTo(written);
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
