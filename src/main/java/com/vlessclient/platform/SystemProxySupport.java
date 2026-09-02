package com.vlessclient.platform;

/**
 * Whether sing-box's {@code set_system_proxy} can actually work on this host.
 *
 * <p>On macOS ({@code networksetup}) and Windows (WinINET) the OS proxy store
 * is always present. On Linux sing-box writes the GNOME store and — as the
 * gnomeless CI probe pinned — <em>hard-fails at startup</em> when the
 * {@code org.gnome.system.proxy} schema is missing: {@code FATAL start
 * inbound: set system proxy: gsettings … No such schema}. The generator
 * therefore only emits the flag where this reports true, so Connect keeps
 * working on KDE/headless boxes (local listeners only, proxy set manually).</p>
 */
@FunctionalInterface
public interface SystemProxySupport {

    /** True when the OS proxy store sing-box writes is usable on this host. */
    boolean canAutoConfigure();

    /**
     * Returns the capability check for the host platform.
     *
     * @return the capability check for the host platform
     */
    static SystemProxySupport current() {
        return switch (Platform.current()) {
            case LINUX -> gnomeProxySchemaPresent(CommandRunner.system());
            // macOS/Windows stores are part of the OS; OTHER rides the mac path.
            case MAC, WINDOWS, OTHER -> () -> true;
        };
    }

    /**
     * Probes the GNOME proxy schema the same way sing-box will use it: one
     * {@code gsettings get} — exit 0 means the schema (and a working dconf)
     * is there. A missing binary or schema reports false.
     */
    static SystemProxySupport gnomeProxySchemaPresent(CommandRunner runner) {
        return new GnomeProxySchemaProbe(runner);
    }
}
