package com.vlessclient.platform;

import java.io.IOException;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * macOS autostart via a per-user LaunchAgent so the application starts
 * automatically when the user logs in.
 *
 * <p>The agent is a property-list file at
 * {@code ~/Library/LaunchAgents/com.vlessclient.client.plist}. launchd scans
 * that directory when the user logs in and, because the plist sets
 * {@code RunAtLoad}, starts the application: the installed app's launcher
 * ({@code Tunl.app/Contents/MacOS/Tunl}), see {@link #launchCommand()}.
 * Enabling autostart writes the file; disabling it deletes the file.</p>
 *
 * <p>No {@code launchctl load} is performed: the running app is already
 * started, and loading a {@code RunAtLoad} agent would immediately spawn a
 * second instance. Writing (or deleting) the file is enough — launchd picks
 * it up at the next login.</p>
 *
 * <p>System Settings, General, Login Items ("Allow in the Background")
 * switches the agent off without touching the file: launchd records the label
 * as disabled for the user's session, which {@link #isEnabled()} reads with
 * {@code launchctl print-disabled} and enabling clears with
 * {@code launchctl enable}.</p>
 */
public final class MacAutostart implements Autostart {

    private static final Logger log = LoggerFactory.getLogger(MacAutostart.class);

    private static final String LABEL = "com.vlessclient.client";
    private static final String PLIST_NAME = LABEL + ".plist";

    /**
     * Our label in {@code launchctl print-disabled} output, switched off: it
     * prints {@code "label" => disabled} since macOS 13 and
     * {@code "label" => true} before.
     */
    private static final Pattern SWITCHED_OFF = Pattern.compile(
            "^\\s*\"" + Pattern.quote(LABEL) + "\"\\s*=>\\s*(?:disabled|true)\\s*$",
            Pattern.MULTILINE);

    private final Path launchAgentsDir;
    private final CommandRunner launchctl;
    private final IntSupplier uid;

    public MacAutostart() {
        this(Path.of(System.getProperty("user.home"), "Library", "LaunchAgents"),
                CommandRunner.system(), MacAutostart::processUid);
    }

    /**
     * Test seam.
     *
     * @param launchAgentsDir where the agent's plist lives
     * @param launchctl runs {@code launchctl}
     * @param uid the user whose login session launchd starts the agent in, or
     *     -1 when it cannot be told
     */
    MacAutostart(Path launchAgentsDir, CommandRunner launchctl, IntSupplier uid) {
        this.launchAgentsDir = launchAgentsDir;
        this.launchctl = launchctl;
        this.uid = uid;
    }

    @Override
    public boolean isEnabled() {
        // The file alone was read. Switching Tunl off under Login Items leaves
        // it in place, so Settings kept the box ticked for an agent launchd no
        // longer starts.
        return Files.isRegularFile(plistPath()) && !switchedOffInLaunchd();
    }

    // Synchronized with refresh(), which runs off the launch thread at start:
    // a refresh that had read "on" must not write the entry back after the
    // user's untick has removed it.
    @Override
    public synchronized void setEnabled(boolean enabled) throws IOException {
        if (enabled) {
            install();
            // Rewriting the plist alone left launchd's switch saying off,
            // which wins, so ticking the box again did nothing.
            if (switchedOffInLaunchd()) {
                clearLaunchdSwitch();
            }
        } else {
            uninstall();
        }
    }

    @Override
    public synchronized void refresh() {
        if (!isEnabled()) {
            return;
        }
        try {
            install();
        } catch (IOException e) {
            log.warn("Could not refresh LaunchAgent plist", e);
        }
    }

    /**
     * Whether launchd has the agent switched off for this user's session. An
     * answer that cannot be had or read leaves the file to decide, as it did
     * before this was asked at all.
     */
    private boolean switchedOffInLaunchd() {
        String domain = guiDomain();
        if (domain == null) {
            return false;
        }
        try {
            CommandRunner.Result result =
                    launchctl.run(List.of("launchctl", "print-disabled", domain));
            return result.exitCode() == 0 && SWITCHED_OFF.matcher(result.output()).find();
        } catch (IOException e) {
            log.warn("Could not ask launchd whether the login item is switched off", e);
            return false;
        }
    }

    private void clearLaunchdSwitch() {
        try {
            CommandRunner.Result result = launchctl.run(
                    List.of("launchctl", "enable", guiDomain() + "/" + LABEL));
            if (result.exitCode() != 0) {
                log.warn("launchctl enable returned {}: {}",
                        result.exitCode(), result.output());
            }
        } catch (IOException e) {
            log.warn("Could not clear launchd's switch for the login item", e);
        }
    }

    /** launchd's domain for this user's login session, or null without a uid. */
    private String guiDomain() {
        int id = uid.getAsInt();
        return id < 0 ? null : "gui/" + id;
    }

    private void install() throws IOException {
        List<String> command = launchCommand();
        Files.createDirectories(launchAgentsDir);
        Files.writeString(plistPath(), buildPlist(command));
        log.info("Login item installed: {}", plistPath());
    }

    /**
     * What launchd runs at login: the installed app's own launcher, or, for a
     * run from the IDE or a jar, this run's JVM invocation.
     *
     * <p>Never the JVM invocation of an installed app. jlink strips
     * {@code bin/java} from the runtime every installer ships
     * ({@code scripts/jlink-options.txt}), so a login item built from
     * {@code java.home} named a file the app does not have: launchd gave up at
     * each login with EX_CONFIG while Settings showed the box ticked. The
     * launcher is also what an update leaves in place, the way the Windows Run
     * value and the Linux desktop entry already use theirs.</p>
     *
     * @return the argv-style command
     * @throws IOException when the app runs from a translocated copy, a
     *     randomized mount macOS removes again
     */
    static List<String> launchCommand() throws IOException {
        Path launcher = InstalledApp.launcher();
        if (launcher == null) {
            return JvmLaunchCommand.current();
        }
        if (InstalledApp.isTranslocated(launcher)) {
            throw new IOException("Tunl runs from a temporary copy macOS made of it;"
                    + " move Tunl to Applications to start it at login");
        }
        return List.of(launcher.toString());
    }

    private void uninstall() throws IOException {
        if (Files.deleteIfExists(plistPath())) {
            log.info("Login item removed: {}", plistPath());
        }
    }

    private Path plistPath() {
        return launchAgentsDir.resolve(PLIST_NAME);
    }

    /**
     * Renders a launchd property list that runs {@code command} at login.
     *
     * @param command the argv-style launch command
     * @return the plist XML document
     */
    static String buildPlist(List<String> command) {
        StringBuilder plist = new StringBuilder();
        plist.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        plist.append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" ");
        plist.append("\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n");
        plist.append("<plist version=\"1.0\">\n");
        plist.append("<dict>\n");
        plist.append("    <key>Label</key>\n");
        plist.append("    <string>").append(LABEL).append("</string>\n");
        plist.append("    <key>ProgramArguments</key>\n");
        plist.append("    <array>\n");
        for (String arg : command) {
            plist.append("        <string>").append(xmlEscape(arg)).append("</string>\n");
        }
        plist.append("    </array>\n");
        plist.append("    <key>RunAtLoad</key>\n");
        plist.append("    <true/>\n");
        plist.append("    <key>LimitLoadToSessionType</key>\n");
        plist.append("    <string>Aqua</string>\n");
        plist.append("    <key>ProcessType</key>\n");
        plist.append("    <string>Interactive</string>\n");
        plist.append("</dict>\n");
        plist.append("</plist>\n");
        return plist.toString();
    }

    /**
     * This process's user id, read once: it does not change while the app runs.
     *
     * @return the uid, or -1 where it cannot be read
     */
    static int processUid() {
        return ProcessUser.UID;
    }

    private static final class ProcessUser {

        static final int UID = currentUid();

        /**
         * The user id from libc's {@code getuid}: launchd names the user's
         * login session {@code gui/<uid>}, and Java has no API for it.
         *
         * @return the uid, or -1 where it cannot be read
         */
        private static int currentUid() {
            try {
                Linker linker = Linker.nativeLinker();
                MethodHandle getuid = linker.downcallHandle(
                        linker.defaultLookup().findOrThrow("getuid"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT));
                return (int) getuid.invokeExact();
            } catch (Throwable t) {
                log.warn("Cannot read the user id launchd knows this session by: {}",
                        t.toString());
                return -1;
            }
        }
    }

    private static String xmlEscape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
