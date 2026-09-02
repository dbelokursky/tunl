package com.vlessclient.platform;

import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Linux arm of {@link SystemProxySupport}: one {@code gsettings get}
 * against {@code org.gnome.system.proxy}.
 *
 * <p>A class rather than a lambda so the outcome can be logged. Reporting
 * {@code false} silently is what made this failure invisible: the generator
 * then drops {@code set_system_proxy}, the tunnel comes up, every health
 * target answers through the local inbound, and nothing anywhere says the OS
 * was never told about the proxy. {@link LinuxSystemProxyGuard} logs the same
 * two failure shapes, so this matches its neighbour.</p>
 */
final class GnomeProxySchemaProbe implements SystemProxySupport {

    private static final Logger log = LoggerFactory.getLogger(GnomeProxySchemaProbe.class);

    private final CommandRunner runner;

    GnomeProxySchemaProbe(CommandRunner runner) {
        this.runner = runner;
    }

    @Override
    public boolean canAutoConfigure() {
        try {
            CommandRunner.Result result = runner.run(List.of(
                    "gsettings", "get", "org.gnome.system.proxy", "mode"));
            if (result.exitCode() != 0) {
                log.warn("No usable GNOME proxy schema (gsettings exit {}): {}",
                        result.exitCode(), result.output().trim());
                return false;
            }
            return true;
        } catch (IOException e) {
            log.warn("Could not probe the GNOME proxy schema (no gsettings?): {}",
                    e.getMessage());
            return false;
        }
    }
}
