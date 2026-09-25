package com.vlessclient.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
 */
public final class MacAutostart implements Autostart {

    private static final Logger log = LoggerFactory.getLogger(MacAutostart.class);

    private static final String LABEL = "com.vlessclient.client";
    private static final String PLIST_NAME = LABEL + ".plist";

    private final Path launchAgentsDir;

    public MacAutostart() {
        this(Path.of(System.getProperty("user.home"), "Library", "LaunchAgents"));
    }

    MacAutostart(Path launchAgentsDir) {
        this.launchAgentsDir = launchAgentsDir;
    }

    @Override
    public boolean isEnabled() {
        return Files.isRegularFile(plistPath());
    }

    @Override
    public void setEnabled(boolean enabled) throws IOException {
        if (enabled) {
            install();
        } else {
            uninstall();
        }
    }

    @Override
    public void refresh() {
        if (!isEnabled()) {
            return;
        }
        try {
            install();
        } catch (IOException e) {
            log.warn("Could not refresh LaunchAgent plist", e);
        }
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

    private static String xmlEscape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
