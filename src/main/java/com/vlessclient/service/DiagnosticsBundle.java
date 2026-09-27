package com.vlessclient.service;

import com.vlessclient.app.AppVersion;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.RoutingConfig;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.platform.PlatformPaths;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Collects everything a bug report needs into one zip the user can attach.
 *
 * <p>A report used to be assembled by hand: find the log directory, work out
 * which app and core version was running, and describe the configuration from
 * memory. Most reports therefore arrived with one of the three missing, and
 * the ones that did carry a config carried the credentials with it.</p>
 *
 * <p>The bundle holds {@code app-info.txt}, the tail of {@code tunl.log} with
 * every URL, every name a server goes by and every public IP address
 * redacted, the sing-box configuration this build would generate
 * right now with every secret value replaced, and {@code settings.json} /
 * {@code routing.json} with their URLs redacted, when they exist. It
 * deliberately does <em>not</em> hold {@code servers.json},
 * {@code subscriptions.json}, the MCP token file or anything from the OS
 * keychain.</p>
 */
public class DiagnosticsBundle {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsBundle.class);

    /** Entry names, so callers and tests name the same strings. */
    public static final String APP_INFO_ENTRY = "app-info.txt";
    public static final String LOG_ENTRY = "tunl.log";
    public static final String CONFIG_ENTRY = "sing-box.json";
    public static final String SETTINGS_ENTRY = "settings.json";
    public static final String ROUTING_ENTRY = "routing.json";

    /** Enough log to see how the app got here, small enough to attach. */
    private static final int LOG_TAIL_LINES = 2000;

    /** Stand-in for a value stripped out of the generated configuration. */
    private static final String REDACTED = Redact.REDACTED;

    /**
     * Field names whose value is a credential in the sing-box schema. Matched
     * on the field name anywhere in the document rather than at known paths,
     * because the document nests — {@code outbounds[].transport}, the
     * WireGuard {@code endpoints[].peers[]}, {@code experimental.clash_api} —
     * and a path list is one schema change away from leaking.
     */
    private static final Set<String> SECRET_FIELDS = Set.of(
            "uuid", "password", "private_key", "pre_shared_key", "psk", "secret");

    /** Any field whose name contains one of these is a secret too. */
    private static final List<String> SECRET_FRAGMENTS = List.of("token", "secret", "password");

    /**
     * Field names that identify the user's server without authenticating to
     * it: the address, the SNI, the transport host, path and gRPC service, the
     * REALITY key and short id, a Shadowsocks plugin's options (the host and
     * path a v2ray-plugin server answers on), and for WireGuard the peer's key
     * and reserved bytes and the interface address. Not credentials, but the
     * bundle is what gets attached to a public issue, and {@link #appInfo}
     * keeps the address out for the same reason. Matched only inside {@code outbounds} and
     * {@code endpoints}, whatever the value's type (a WireGuard address is a
     * list): the same key names elsewhere are DNS resolver tags and rule
     * references, which the bundle is there to show.
     */
    private static final Set<String> IDENTIFYING_FIELDS = Set.of(
            "server", "server_name", "host", "sni", "path", "service_name",
            "address", "public_key", "short_id", "reserved", "plugin_opts");

    /**
     * A host name or an IPv4 address in free text: labels joined by dots, in
     * any script, so an internationalized server name is found too.
     */
    private static final Pattern HOST_IN_TEXT =
            Pattern.compile("[\\w-]+(?:\\.[\\w-]+)+", Pattern.UNICODE_CHARACTER_CLASS);

    /** The host a Shadowsocks plugin is told: v2ray-plugin's host, simple-obfs's obfs-host. */
    private static final Pattern PLUGIN_HOST = Pattern.compile("(?:^|;)\\s*(?:obfs-)?host=([^;]+)");

    private final ConfigStore configStore;
    private final SingBoxConfigGenerator configGenerator;
    private final RoutingService routingService;
    private final Path logsDir;
    private final ObjectMapper objectMapper;

    /**
     * Creates a bundle writer reading the platform's log directory.
     *
     * @param configStore     supplies servers, settings and the data directory
     * @param configGenerator builds the configuration snapshot
     * @param routingService  supplies the routing rules, or {@code null}
     */
    public DiagnosticsBundle(ConfigStore configStore, SingBoxConfigGenerator configGenerator,
                             RoutingService routingService) {
        this(configStore, configGenerator, routingService, PlatformPaths.current().logsDir());
    }

    /** Test seam: read the log tail from an explicit directory. */
    DiagnosticsBundle(ConfigStore configStore, SingBoxConfigGenerator configGenerator,
                      RoutingService routingService, Path logsDir) {
        this.configStore = configStore;
        this.configGenerator = configGenerator;
        this.routingService = routingService;
        this.logsDir = logsDir;
        this.objectMapper = JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build();
    }

    /**
     * Writes the diagnostics zip to {@code file}, replacing anything there.
     *
     * <p>Every section is best-effort: a section that cannot be produced is
     * written as a short note explaining why rather than failing the bundle,
     * because a report missing one file still beats no report at all.</p>
     *
     * @param file the zip file to write
     * @throws IOException if the archive itself could not be written
     */
    public void writeTo(Path file) throws IOException {
        try (OutputStream out = Files.newOutputStream(file);
                ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            write(zip, APP_INFO_ENTRY, appInfo());
            write(zip, LOG_ENTRY, logTail());
            write(zip, CONFIG_ENTRY, redactedConfig());
            copyRedactedIfPresent(zip, SETTINGS_ENTRY);
            copyRedactedIfPresent(zip, ROUTING_ENTRY);
        }
        log.info("Wrote diagnostics bundle to {}", file);
    }

    private static void write(ZipOutputStream zip, String name, String content)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /**
     * Copies a data-directory file with every URL in it cut down to its scheme
     * and host, as the log tail is: a private DNS resolver in the settings
     * keeps the account it belongs to in its path, and the bypass list takes
     * whole URLs. Only the two files named by the constants above are ever
     * passed here; the credential-bearing files in the same directory are not
     * in the bundle at all.
     */
    private void copyRedactedIfPresent(ZipOutputStream zip, String name) throws IOException {
        Path source = configStore.getDataDir().resolve(name);
        if (!Files.isRegularFile(source)) {
            return;
        }
        // Lenient decoding: a stray invalid byte must not cost the report this file.
        String content = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        write(zip, name, Redact.urlsIn(content));
    }

    /**
     * The environment the report is about: what was running, on what, and in
     * which mode. Only the active server's protocol goes in — its address and
     * credentials are what the user is asking us not to publish.
     */
    private String appInfo() {
        AppSettings settings = configStore.getSettings();
        ServerConfig active = activeServer();
        Map<String, String> lines = new LinkedHashMap<>();
        lines.put("app.version", AppVersion.VERSION);
        lines.put("os.name", System.getProperty("os.name", "?"));
        lines.put("os.version", System.getProperty("os.version", "?"));
        lines.put("os.arch", System.getProperty("os.arch", "?"));
        lines.put("java.version", System.getProperty("java.version", "?"));
        lines.put("singbox.pinned.version", SingBoxInstaller.PINNED_VERSION);
        lines.put("proxy.mode", String.valueOf(settings.getProxyMode()));
        lines.put("server.selection", String.valueOf(settings.getServerSelection()));
        lines.put("active.server.protocol", active == null || active.getProtocol() == null
                ? "none" : active.getProtocol().getValue());
        lines.put("mcp.enabled", String.valueOf(settings.isMcpEnabled()));

        StringBuilder text = new StringBuilder();
        lines.forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
        return text.toString();
    }

    private ServerConfig activeServer() {
        return FxExecutor.get(() -> List.copyOf(configStore.getServers())).stream()
                .filter(ServerConfig::isActive)
                .findFirst()
                .orElse(null);
    }

    /**
     * The last {@value #LOG_TAIL_LINES} lines of {@code tunl.log}, with every
     * URL redacted. Subscription URLs and share links both carry their
     * credential in the URL itself, and the log is precisely the file people
     * attach to bug reports. The names a server goes by and every public IP
     * address are redacted too: an unexpected exit leaves the core's last
     * lines in the log, and the core quotes the server it dialed, the address
     * that name resolved to and the SNI a certificate did not match.
     */
    private String logTail() {
        Path file = logsDir.resolve(LOG_ENTRY);
        if (!Files.isRegularFile(file)) {
            return "(no log file at " + file.getFileName() + ")\n";
        }
        Deque<String> tail = new ArrayDeque<>(LOG_TAIL_LINES);
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (tail.size() == LOG_TAIL_LINES) {
                    tail.removeFirst();
                }
                tail.addLast(line);
            }
        } catch (IOException e) {
            return "(could not read the log file: " + e.getMessage() + ")\n";
        }
        // Only the lines kept: the log runs to 10 MB, and this runs on the UI thread.
        Set<String> names = serverNames();
        return tail.stream()
                .map(line -> Redact.publicIpsIn(serverNamesIn(Redact.urlsIn(line), names)))
                .collect(Collectors.joining("\n", "", "\n"));
    }

    /**
     * Every name a configured server goes by, in lower case: its address, the
     * SNI of its TLS or REALITY hello, and the host its transport or its
     * Shadowsocks plugin asks for. Only dotted names and IPv4 addresses are
     * taken, as {@link Redact#publicIpsIn} covers IPv6; so WireGuard's
     * reserved bytes, kept where the SNI would be, are not.
     */
    private Set<String> serverNames() {
        List<String> values = new ArrayList<>();
        for (ServerConfig server : FxExecutor.get(() -> List.copyOf(configStore.getServers()))) {
            values.add(server.getAddress());
            if (server.getTls() != null) {
                values.add(server.getTls().getServerName());
            }
            if (server.getTransport() != null) {
                values.add(server.getTransport().getHost());
            }
            if (server.getPluginOpts() != null) {
                Matcher host = PLUGIN_HOST.matcher(server.getPluginOpts());
                while (host.find()) {
                    values.add(host.group(1));
                }
            }
        }
        Set<String> names = new HashSet<>();
        for (String value : values) {
            if (value != null) {
                Matcher name = HOST_IN_TEXT.matcher(value);
                while (name.find()) {
                    names.add(name.group().toLowerCase(Locale.ROOT));
                }
            }
        }
        return names;
    }

    /**
     * Replaces every host in {@code line} that is one of {@code names}. Each
     * host in the line is looked up once, rather than each name searched for
     * throughout: a subscription brings hundreds of servers.
     */
    private static String serverNamesIn(String line, Set<String> names) {
        if (names.isEmpty()) {
            return line;
        }
        return HOST_IN_TEXT.matcher(line).replaceAll(host -> Matcher.quoteReplacement(
                names.contains(host.group().toLowerCase(Locale.ROOT)) ? REDACTED : host.group()));
    }

    /**
     * The configuration the core would be started with right now, with every
     * secret value replaced. Generated rather than read back from disk: the
     * running core's config file lives in a temp directory that may already be
     * gone, and regenerating shows what this build would do with the settings
     * as they stand.
     */
    private String redactedConfig() {
        ServerConfig active = activeServer();
        if (active == null) {
            return "{\n  \"note\": \"no active server; nothing to generate\"\n}\n";
        }
        List<ServerConfig> candidates = FxExecutor.get(
                () -> List.copyOf(configStore.getServers()));
        String json;
        try {
            json = configGenerator.generate(
                    candidates, active, configStore.getSettings(), safeRoutingConfig());
        } catch (RuntimeException e) {
            log.warn("Could not generate the configuration for diagnostics", e);
            return "{\n  \"note\": \"could not generate the configuration\"\n}\n";
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            redactSecrets(root);
            for (String section : List.of("outbounds", "endpoints")) {
                JsonNode nodes = root.get(section);
                if (nodes != null) {
                    redactIdentifying(nodes);
                }
            }
            for (JsonNode server : root.path("dns").path("servers")) {
                // A private resolver keeps the account it belongs to in its
                // path; the resolver's type and host are what a report needs.
                if (server instanceof ObjectNode object && object.has("path")) {
                    object.put("path", REDACTED);
                }
            }
            return objectMapper.writeValueAsString(root) + "\n";
        } catch (JacksonException e) {
            // Redaction is the only reason this file is safe to include, so a
            // config we cannot walk is a config we must not ship.
            log.warn("Could not redact the generated configuration", e);
            return "{\n  \"note\": \"configuration could not be redacted; omitted\"\n}\n";
        }
    }

    private RoutingConfig safeRoutingConfig() {
        if (routingService == null) {
            return null;
        }
        try {
            return routingService.getConfig();
        } catch (RuntimeException e) {
            log.debug("Routing rules unavailable for diagnostics: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Walks the whole document replacing secret values, so a field nested
     * anywhere — including one a future schema adds — cannot slip through.
     */
    private static void redactSecrets(JsonNode node) {
        if (node instanceof ObjectNode object) {
            for (String name : List.copyOf(object.propertyNames())) {
                JsonNode value = object.get(name);
                if (isSecretField(name) && value != null && value.isValueNode()) {
                    object.put(name, REDACTED);
                } else {
                    redactSecrets(value);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (JsonNode child : array) {
                redactSecrets(child);
            }
        }
    }

    /**
     * Replaces the server-identifying values under an outbound or endpoint,
     * lists included: a list's elements carry no field name to match.
     */
    private static void redactIdentifying(JsonNode node) {
        if (node instanceof ObjectNode object) {
            for (String name : List.copyOf(object.propertyNames())) {
                JsonNode value = object.get(name);
                if (IDENTIFYING_FIELDS.contains(name.toLowerCase(Locale.ROOT)) && value != null) {
                    object.put(name, REDACTED);
                } else {
                    redactIdentifying(value);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (JsonNode child : array) {
                redactIdentifying(child);
            }
        }
    }

    private static boolean isSecretField(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (SECRET_FIELDS.contains(lower)) {
            return true;
        }
        return SECRET_FRAGMENTS.stream().anyMatch(lower::contains);
    }
}
