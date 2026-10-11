package com.vlessclient.platform;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WindowsAutostartTest {

    private static final String RUN_KEY =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String APPROVED_KEY =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run";

    /** Records every reg.exe invocation and replies with a programmed result. */
    private static final class FakeReg implements CommandRunner {
        final List<List<String>> calls = new ArrayList<>();
        private final Function<List<String>, CommandRunner.Result> responder;

        FakeReg(Function<List<String>, CommandRunner.Result> responder) {
            this.responder = responder;
        }

        @Override
        public CommandRunner.Result run(List<String> command) {
            calls.add(List.copyOf(command));
            return responder.apply(command);
        }
    }

    private static CommandRunner.Result ok() {
        return new CommandRunner.Result(0, "");
    }

    @Test
    void isEnabled_trueWhenRegQuerySucceeds() {
        FakeReg reg = new FakeReg(cmd -> ok());
        assertThat(new WindowsAutostart(reg).isEnabled()).isTrue();
        assertThat(reg.calls.getFirst()).containsSequence("reg", "query");
        assertThat(reg.calls.getFirst()).contains("VlessClient");
    }

    @Test
    void isEnabled_falseWhenRegQueryFails() {
        FakeReg reg = new FakeReg(cmd -> new CommandRunner.Result(1,
                "ERROR: The system was unable to find the specified registry key or value."));
        assertThat(new WindowsAutostart(reg).isEnabled()).isFalse();
    }

    @Test
    void isEnabled_falseWhenRegExecutableMissing() {
        // When reg.exe cannot even be launched the IOException is swallowed and
        // autostart reports disabled rather than propagating.
        CommandRunner throwing = cmd -> {
            throw new IOException("reg not found");
        };
        assertThat(new WindowsAutostart(throwing).isEnabled()).isFalse();
    }

    @Test
    void setEnabledTrue_writesRunValueWithLaunchCommand() throws IOException {
        FakeReg reg = new FakeReg(cmd -> ok());

        new WindowsAutostart(reg).setEnabled(true);

        List<String> add = reg.calls.getFirst();
        assertThat(add).containsSequence("reg", "add");
        assertThat(add).containsSequence("/v", "VlessClient");
        assertThat(add).containsSequence("/t", "REG_SZ");
        assertThat(add).contains("/f");
        int dataIndex = add.indexOf("/d") + 1;
        assertThat(add.get(dataIndex)).contains("com.vlessclient.app.Launcher");
    }

    @Test
    void setEnabledTrue_throwsWhenRegAddFails() {
        FakeReg reg = new FakeReg(cmd -> new CommandRunner.Result(1, "ERROR: Access is denied."));

        assertThatThrownBy(() -> new WindowsAutostart(reg).setEnabled(true))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("reg add failed");
    }

    @Test
    void setEnabledFalse_deletesRunValue() throws IOException {
        FakeReg reg = new FakeReg(cmd -> ok());

        new WindowsAutostart(reg).setEnabled(false);

        List<String> delete = reg.calls.getFirst();
        assertThat(delete).containsSequence("reg", "delete");
        assertThat(delete).containsSequence("/v", "VlessClient");
        assertThat(delete).contains("/f");
    }

    @Test
    void setEnabledFalse_missingValueIsNotAnError() {
        FakeReg reg = new FakeReg(cmd -> new CommandRunner.Result(1,
                "ERROR: The system was unable to find the specified registry key or value."));

        // A missing value means the goal (no autostart entry) is already met.
        assertThatCode(() -> new WindowsAutostart(reg).setEnabled(false))
                .doesNotThrowAnyException();
    }

    @Test
    void refresh_reinstallsWhenEnabled() throws IOException {
        // The queries report a Run value and no Task Manager switch; the last
        // call is the add, since the stored value is not this launcher.
        FakeReg reg = new FakeReg(cmd -> ok());

        new WindowsAutostart(reg).refresh();

        assertThat(reg.calls.getFirst()).containsSequence("reg", "query", RUN_KEY);
        assertThat(reg.calls.getLast()).containsSequence("reg", "add");
    }

    /** What {@code reg query} prints for one value. */
    private static CommandRunner.Result value(String key, String type, String data) {
        return new CommandRunner.Result(0, "\r\n" + key + "\r\n    VlessClient    " + type
                + "    " + data + "\r\n\r\n");
    }

    private static CommandRunner.Result notFound() {
        return new CommandRunner.Result(1,
                "ERROR: The system was unable to find the specified registry key or value.");
    }

    /**
     * A registry where the Run value holds {@code stored} and Task Manager's
     * switch, if any, holds {@code approved}; every write succeeds.
     */
    private static FakeReg registry(String stored, String approved) {
        return new FakeReg(cmd -> {
            if (cmd.get(1).equals("query") && cmd.get(2).equals(RUN_KEY)) {
                return stored == null ? notFound() : value(RUN_KEY, "REG_SZ", stored);
            }
            if (cmd.get(1).equals("query") && cmd.get(2).equals(APPROVED_KEY)) {
                return approved == null ? notFound()
                        : value(APPROVED_KEY, "REG_BINARY", approved);
            }
            return ok();
        });
    }

    /**
     * Task Manager's Startup tab (and Settings, Apps, Startup) switches a Run
     * entry off without touching it: it writes a value of the same name under
     * StartupApproved\Run whose first byte is 03. Only the Run value was read,
     * so Settings kept the box ticked for an app Windows no longer starts.
     */
    @Test
    void anEntryTaskManagerSwitchedOffIsOff() {
        FakeReg reg = registry("\"C:\\Tunl\\Tunl.exe\"", "030000004A3B8C1D2E5FD901");

        assertThat(new WindowsAutostart(reg).isEnabled()).isFalse();
        assertThat(reg.calls).anySatisfy(call ->
                assertThat(call).containsSequence("reg", "query", APPROVED_KEY));
    }

    @Test
    void anEntryTaskManagerLeftOnOrNeverSawIsOn() {
        assertThat(new WindowsAutostart(registry("\"C:\\Tunl\\Tunl.exe\"",
                "020000000000000000000000")).isEnabled()).isTrue();
        assertThat(new WindowsAutostart(registry("\"C:\\Tunl\\Tunl.exe\"", null))
                .isEnabled()).isTrue();
        // An answer it cannot read does not switch autostart off.
        assertThat(new WindowsAutostart(registry("\"C:\\Tunl\\Tunl.exe\"", "zz"))
                .isEnabled()).isTrue();
    }

    @Test
    void refreshLeavesAnEntryTaskManagerSwitchedOffAlone() {
        FakeReg reg = registry("\"C:\\Old\\Tunl.exe\"", "030000004A3B8C1D2E5FD901");

        new WindowsAutostart(reg).refresh();

        assertThat(reg.calls).noneSatisfy(call -> assertThat(call).contains("add"));
    }

    /**
     * Ticking the box only re-added the Run value, which Task Manager's switch
     * overrides, so the user's re-enable did nothing. Removing the switch
     * puts the entry back the way Windows treats one it never saw.
     */
    @Test
    void enablingClearsTaskManagersSwitch() throws IOException {
        FakeReg reg = registry(null, "030000004A3B8C1D2E5FD901");

        new WindowsAutostart(reg).setEnabled(true);

        assertThat(reg.calls).anySatisfy(call -> assertThat(call)
                .containsSequence("reg", "delete", APPROVED_KEY, "/v", "VlessClient", "/f"));
    }

    /**
     * Every start rewrote the Run value with {@code reg add} even when it
     * already held this launcher: one more process before the window, at
     * login, for nothing.
     */
    @Test
    void refreshDoesNotRewriteAValueThatIsAlreadyCurrent() {
        FakeReg reg = registry(WindowsAutostart.launchCommandLine(), null);

        new WindowsAutostart(reg).refresh();

        assertThat(reg.calls).noneSatisfy(call -> assertThat(call).contains("add"));
    }

    @Test
    void refreshRewritesAValueThatNamesAnotherLauncher() {
        FakeReg reg = registry("\"C:\\Old place\\Tunl.exe\"", null);

        new WindowsAutostart(reg).refresh();

        assertThat(reg.calls.getLast()).containsSequence("reg", "add", RUN_KEY);
    }

    @Test
    void refresh_doesNothingWhenDisabled() {
        FakeReg reg = new FakeReg(cmd -> new CommandRunner.Result(1, "not found"));

        new WindowsAutostart(reg).refresh();

        // Only the query ran; no add attempted.
        assertThat(reg.calls).hasSize(1);
        assertThat(reg.calls.getFirst()).containsSequence("reg", "query");
    }

    @Test
    void launchCommandLine_underJpackage_registersTheNativeLauncher() {
        String line = WindowsAutostart.launchCommandLine(
                java.util.Optional.of("C:\\Users\\u\\AppData\\Local\\VLESS Client\\VLESS Client.exe"));

        // The installed exe itself, quoted (path has spaces) — no java command.
        assertThat(line).isEqualTo("\"C:\\Users\\u\\AppData\\Local\\VLESS Client\\VLESS Client.exe\"");
    }

    @Test
    void launchCommandLine_devJavaRun_fallsBackToJavawInvocation() {
        String line = WindowsAutostart.launchCommandLine(
                java.util.Optional.of("C:\\jdk\\bin\\java.exe"));

        assertThat(line).contains("com.vlessclient.app.Launcher");
        assertThat(line).contains("-cp");
        // javaw, not java: no console window at every login.
        assertThat(line).doesNotContain("java.exe");
    }

    @Test
    void launchCommandLine_unknownProcessCommand_fallsBackToJvmInvocation() {
        String line = WindowsAutostart.launchCommandLine(java.util.Optional.empty());

        assertThat(line).contains("com.vlessclient.app.Launcher");
        assertThat(line).contains("-cp");
    }
}
