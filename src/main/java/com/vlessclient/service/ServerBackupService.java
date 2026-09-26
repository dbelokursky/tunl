package com.vlessclient.service;

import com.vlessclient.app.I18n;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.platform.SecureFiles;
import com.vlessclient.service.outbound.CoreSettings;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Moves the whole server list in and out of a single file.
 *
 * <p>Until now a server could only leave the app one at a time, as a share
 * link copied to the clipboard — and WireGuard, which has no share-link
 * format at all, could not leave it even that way. There was no way to take a
 * backup before a reinstall, and no way to carry a list to a second machine.</p>
 *
 * <p>The exported file is the same shape as {@code servers.json} — the v1
 * envelope {@code {"config_version":1,"servers":[…]}} — with one deliberate
 * difference: credentials are written in <strong>plain text</strong>. A
 * {@code servers.json} credential may be a sealed tag naming an entry in this
 * machine's keychain, and such a tag restores to nothing anywhere else, which
 * is exactly what a backup must not do. The file is therefore written
 * owner-only through {@link SecureFiles#writePrivately}, and the UI warns
 * before writing it.</p>
 *
 * <p>Import also accepts a plain-text list of share links, one per line, so
 * the file another client exported is usable here without conversion. The
 * same parsing takes links out of pasted text for the Servers view's clipboard
 * import; see {@link #importShareLinks}.</p>
 */
public class ServerBackupService {

    private static final Logger log = LoggerFactory.getLogger(ServerBackupService.class);

    /**
     * Version stamped into an exported file. Matches the {@code servers.json}
     * stream on purpose: the two carry the same objects, so a reader that
     * understands one understands the other.
     */
    public static final int BACKUP_VERSION = 1;

    /** Guards against reading a whole disk image someone renamed to .json. */
    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;

    private final ConfigStore configStore;
    private final ShareLinkParser shareLinkParser;
    private final ObjectMapper objectMapper;

    /**
     * Creates a backup service over the given store and share-link parser.
     *
     * @param configStore     the store whose servers are exported and restored
     * @param shareLinkParser parses the share-link form of an import file
     */
    public ServerBackupService(ConfigStore configStore, ShareLinkParser shareLinkParser) {
        this(configStore, shareLinkParser, JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build());
    }

    /**
     * Test seam: the mapper both directions go through, so a test can write a
     * file in a shape a hand-rolled or third-party export would produce and
     * check it still reads back.
     */
    ServerBackupService(ConfigStore configStore, ShareLinkParser shareLinkParser,
                        ObjectMapper objectMapper) {
        this.configStore = configStore;
        this.shareLinkParser = shareLinkParser;
        this.objectMapper = objectMapper;
    }

    /** One entry an import could not use, and why. */
    public record Skip(String entry, String reason) {
    }

    /**
     * What an import did. {@code added} and {@code updated} are server counts,
     * {@code skipped} lists the entries that were not usable.
     */
    public record ImportResult(int added, int updated, List<Skip> skipped) {

        /** Copies the list so the result cannot change under its reader. */
        public ImportResult {
            skipped = List.copyOf(skipped);
        }
    }

    /**
     * What an export wrote.
     *
     * @param written how many servers the file holds
     * @param leftOut the names of the servers left out because a credential
     *                of theirs could not be read from the keychain
     */
    public record Export(int written, List<String> leftOut) {

        /** Copies the list so the result cannot change under its reader. */
        public Export {
            leftOut = List.copyOf(leftOut);
        }
    }

    /**
     * Writes every configured server to {@code file} as JSON, credentials in
     * plain text, owner-only. A server whose credential the keychain did not
     * return holds its sealed tag, which restores to nothing on another
     * machine, so it is left out and named.
     *
     * @param file the file to write; an existing file is replaced
     * @return what was written and what was left out
     * @throws IOException if the file could not be written
     */
    public Export exportAll(Path file) throws IOException {
        // One marshalled read of the FX-owned list, for the same reason
        // ConnectionService takes one: iterating it from a background thread
        // races the FX thread's mutations.
        List<ServerConfig> snapshot =
                FxExecutor.get(() -> List.copyOf(configStore.getServers()));
        List<ServerConfig> readable = snapshot.stream()
                .filter(server -> !CoreSettings.hasUnreadableCredential(server))
                .toList();
        final List<String> leftOut = snapshot.stream()
                .filter(CoreSettings::hasUnreadableCredential)
                .map(ServerConfig::getName)
                .toList();
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("config_version", BACKUP_VERSION);
        envelope.set("servers", objectMapper.valueToTree(readable));
        SecureFiles.writePrivately(file, objectMapper.writeValueAsBytes(envelope));
        log.info("Exported {} servers to {}, left out {} with unreadable credentials",
                readable.size(), file, leftOut.size());
        return new Export(readable.size(), leftOut);
    }

    /**
     * Restores servers from {@code file}, which may be an exported JSON
     * backup, a bare JSON array of servers, or a plain-text list of share
     * links (one per line; blank lines and {@code #} comments ignored).
     *
     * <p>A server whose id is already configured is replaced in place; every
     * other server is added with a fresh id when it carries none. The whole
     * file is parsed before anything is stored, so a file that cannot be read
     * leaves the list exactly as it was.</p>
     *
     * @param file the file to read
     * @return what was added, updated and skipped
     * @throws IOException              if the file could not be read
     * @throws IllegalArgumentException if the file holds no readable server
     */
    public ImportResult importFile(Path file) throws IOException {
        String text = readText(file);
        List<Skip> skipped = new ArrayList<>();
        String leading = text.stripLeading();
        List<ServerConfig> parsed = leading.startsWith("{") || leading.startsWith("[")
                ? parseJson(leading, skipped)
                : parseShareLinks(text, skipped);

        if (parsed.isEmpty()) {
            throw new IllegalArgumentException(noServersMessage(skipped));
        }
        return apply(parsed, skipped);
    }

    /**
     * Imports the share links in a block of free text, such as the clipboard.
     *
     * <p>The links go through the same parsing, redacted skip reporting and
     * single batched save as the share-link form of {@link #importFile}. Only
     * the reading of the text differs, because what a messenger puts on the
     * clipboard is not a tidy one-link-per-line file:</p>
     *
     * <ul>
     *   <li>Links are separated by any whitespace, not only by line breaks. A
     *       chat routinely puts two links on one line, and read as one line
     *       the second link becomes part of the first one's name, credential
     *       included.</li>
     *   <li>Words that are not links ("here are your servers:") are ignored
     *       rather than reported as skipped: nobody meant them as a server.
     *       Anything with a {@code ://} counts as a link, so a subscription
     *       URL is reported as skipped instead of vanishing.</li>
     *   <li>Finding nothing usable is an answer, not an error: nothing is
     *       added and the stored list is not touched.</li>
     * </ul>
     *
     * <p>A raw space inside a link ends it. A well-formed link has none, since
     * the name in its fragment is percent-encoded, so this can only shorten
     * the name of a link that was malformed already.</p>
     *
     * @param text the text to read; null reads as empty
     * @return what was added, and the links that were skipped
     */
    public ImportResult importShareLinks(String text) {
        List<String> links = text == null ? List.of()
                : Arrays.stream(text.strip().split("\\s+"))
                        .filter(token -> token.contains("://"))
                        .toList();
        List<Skip> skipped = new ArrayList<>();
        List<ServerConfig> parsed = parseShareLinks(links, skipped);
        if (parsed.isEmpty()) {
            log.info("Imported no servers from text: {} link(s) skipped", skipped.size());
            return new ImportResult(0, 0, skipped);
        }
        return apply(parsed, skipped);
    }

    /** Reads the file as UTF-8 text, refusing anything oversized or binary. */
    private static String readText(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_FILE_BYTES) {
            throw new IllegalArgumentException(
                    "File is too large to be a server list (" + size + " bytes)");
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            throw new IllegalArgumentException("File is not UTF-8 text", e);
        }
    }

    /**
     * Reads the envelope form and the bare-array form. A file that opens like
     * JSON and then will not parse is an error rather than a skip: the user
     * pointed at the wrong file, or the right one is damaged, and either way
     * silently importing nothing would be the worst answer.
     */
    private List<ServerConfig> parseJson(String text, List<Skip> skipped) {
        JsonNode root;
        try {
            root = objectMapper.readTree(text);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("File is not valid JSON: " + e.getMessage(), e);
        }
        JsonNode items = root.isArray() ? root : root.path("servers");
        if (!items.isArray()) {
            throw new IllegalArgumentException(
                    "JSON file has no \"servers\" array; this is not a server backup");
        }
        List<ServerConfig> parsed = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            try {
                ServerConfig server = objectMapper.convertValue(item, ServerConfig.class);
                String refusal = admissionRefusal(server);
                if (refusal == null) {
                    parsed.add(server);
                } else {
                    skipped.add(new Skip(entryName(item, i), refusal));
                }
            } catch (IllegalArgumentException | JacksonException e) {
                skipped.add(new Skip(entryName(item, i), e.getMessage()));
            }
        }
        return parsed;
    }

    /**
     * Holds a restored server to what a link has to pass, since a backup is
     * edited by hand as often as it is exported. An entry without a protocol
     * made every connect fail, one the core refuses was stored and then left
     * out of every connect, and its name went into the log as it was, where a
     * link's name is cleaned of control characters first.
     *
     * @return why the server cannot be stored, or null when it can; the name
     *     is cleaned either way
     */
    private static String admissionRefusal(ServerConfig server) {
        server.setName(ShareLinkParser.cleanName(server.getName()));
        if (server.getProtocol() == null) {
            return I18n.get("servers.backup.import.no.protocol");
        }
        return CoreSettings.refusal(server).map(CoreSettings.Refusal::reason).orElse(null);
    }

    /** The name a skipped JSON entry is reported under; never a credential. */
    private static String entryName(JsonNode item, int index) {
        String name = item == null ? "" : item.path("name").asString("");
        return name.isBlank() ? "#" + (index + 1) : name;
    }

    /**
     * Reads a share-link list. Skips are per line here rather than fatal: a
     * list assembled by hand routinely has one stale line in it, and dropping
     * the other thirty over it helps nobody.
     */
    private List<ServerConfig> parseShareLinks(String text, List<Skip> skipped) {
        return parseShareLinks(Arrays.asList(text.split("\\R")), skipped);
    }

    /**
     * Parses each entry as one share link: a file's lines and the links picked
     * out of pasted text alike. Blank entries and {@code #} comments are passed
     * over; an entry that does not parse becomes a skip.
     */
    private List<ServerConfig> parseShareLinks(List<String> lines, List<Skip> skipped) {
        List<ServerConfig> parsed = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            try {
                parsed.add(shareLinkParser.parseForImport(line));
            } catch (RuntimeException e) {
                // The line is a credential-bearing URL; report it redacted so
                // the message is safe to paste into a bug report. The reason
                // too, since a parser message can quote the line: it is shown
                // in the import report, and a file with no readable line fails
                // with the first one.
                skipped.add(new Skip(Redact.url(line), Redact.urlsIn(e.getMessage())));
            }
        }
        return parsed;
    }

    private static String noServersMessage(List<Skip> skipped) {
        if (skipped.isEmpty()) {
            return "The file contains no servers";
        }
        return "No servers could be read from the file; the first problem was: "
                + skipped.get(0).reason();
    }

    /**
     * Stores the parsed servers in one batch, so the list fires a single change.
     *
     * <p>Which server is active is a local choice, not backup data: a restored
     * server keeps whatever the store already says about it, and a newly added
     * one arrives inactive. Without that, importing a file exported while some
     * other server was active would leave two servers flagged active and break
     * the store's one-active invariant.</p>
     */
    private ImportResult apply(List<ServerConfig> parsed, List<Skip> skipped) {
        Map<String, Boolean> activeById = new LinkedHashMap<>();
        FxExecutor.get(() -> configStore.getServers()).forEach(
                existing -> activeById.put(existing.getId(), existing.isActive()));

        // Keyed by id so a file naming the same server twice upserts once,
        // with the last entry winning — the same thing a second import of the
        // same file would do.
        Map<String, ServerConfig> byId = new LinkedHashMap<>();
        for (ServerConfig server : parsed) {
            if (server.getId() == null || server.getId().isBlank()) {
                server.setId(UUID.randomUUID().toString());
            }
            Boolean wasActive = activeById.get(server.getId());
            server.setActive(wasActive != null && wasActive);
            byId.put(server.getId(), server);
        }

        int updated = 0;
        for (String id : byId.keySet()) {
            if (activeById.containsKey(id)) {
                updated++;
            }
        }
        int added = byId.size() - updated;

        configStore.applyServerBatch(List.copyOf(byId.values()), List.of());
        log.info("Imported servers: {} added, {} updated, {} skipped",
                added, updated, skipped.size());
        return new ImportResult(added, updated, skipped);
    }
}
