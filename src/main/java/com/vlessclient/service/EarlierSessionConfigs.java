package com.vlessclient.service;

import com.vlessclient.platform.PlatformPaths;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Removes the session configurations earlier releases left behind, each in
 * the folder that release wrote them to.
 *
 * <p>A session's configuration carries every configured server's credential
 * in plain text, and a run that did not end cleanly (a crash, a kill, a
 * power cut) left its file where it was. Up to 1.24 that was the system temp
 * dir, which Windows never empties; 1.25 wrote them to the data dir's run
 * folder, which backups copy and Windows roams. The sweep a session start
 * runs only looks in the folder this release writes to
 * ({@link PlatformPaths#runDir()}), so what an earlier release left stayed,
 * beside the sealed servers.json it copies.</p>
 */
public final class EarlierSessionConfigs {

    private static final Logger log = LoggerFactory.getLogger(EarlierSessionConfigs.class);

    /**
     * How the engine has always named them: {@code Files.createTempFile}'s
     * digits between its prefix and suffix.
     */
    private static final Pattern NAME = Pattern.compile(
            Pattern.quote(SingBoxEngine.SESSION_CONFIG_PREFIX) + "\\d+\\.json");

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private EarlierSessionConfigs() {
    }

    /**
     * Removes them. Once at startup, and only by the copy that holds the data
     * directory: a copy that does not may be running beside one that still
     * writes there.
     */
    public static void remove() {
        try {
            PlatformPaths paths = PlatformPaths.current();
            remove(Path.of(System.getProperty("java.io.tmpdir")), paths.dataDir(),
                    paths.runDir());
        } catch (RuntimeException e) {
            log.warn("Could not look for session configurations an earlier release left", e);
        }
    }

    /**
     * {@link #remove()} for the given folders.
     *
     * <p>The folder this release writes to is left to the session's own sweep:
     * this runs beside an auto-connect, whose core may be about to read its
     * configuration once an elevation prompt is answered.</p>
     *
     * @param tempDir the system temp dir, where 1.24 and earlier wrote them
     * @param dataDir the data directory, whose run folder 1.25 wrote them to
     * @param runDir  where this release writes them
     * @return how many were removed
     */
    static int remove(Path tempDir, Path dataDir, Path runDir) {
        int removed = 0;
        Path oldRunDir = dataDir.resolve("run");
        if (!oldRunDir.equals(runDir)) {
            // The data dir is this app's own: a session config there is one of its.
            removed += removeIn(oldRunDir, "the data folder", file -> true);
            removeIfEmpty(oldRunDir);
        }
        if (!tempDir.equals(runDir)) {
            // Shared with every other program, and on Linux with every user.
            removed += removeIn(tempDir, "the system temp folder",
                    file -> ownedAsDataDir(file, dataDir) && cachesIn(file, dataDir));
        }
        return removed;
    }

    private static int removeIn(Path dir, String where, Predicate<Path> ours) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int removed = 0;
        try (DirectoryStream<Path> named = Files.newDirectoryStream(dir,
                EarlierSessionConfigs::namedAsOne)) {
            for (Path file : named) {
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && ours.test(file)) {
                    try {
                        Files.delete(file);
                        removed++;
                    } catch (IOException e) {
                        log.warn("Could not remove a session configuration an earlier release"
                                + " left in {}: {}", where, e.toString());
                    }
                }
            }
        } catch (IOException | DirectoryIteratorException e) {
            log.warn("Could not look for session configurations in {}: {}", where, e.toString());
        }
        if (removed > 0) {
            log.info("Removed {} session configuration(s) an earlier release left in {}",
                    removed, where);
        }
        return removed;
    }

    /** Whether {@code file} is named as the engine names a session configuration. */
    private static boolean namedAsOne(Path file) {
        Path name = file.getFileName();
        return name != null && NAME.matcher(name.toString()).matches();
    }

    /**
     * Whether the user who owns the data directory owns {@code file}, where
     * the system tells users apart by uid: in a shared /tmp another user's
     * file is not even read. Windows' temp dir is the profile's own.
     */
    private static boolean ownedAsDataDir(Path file, Path dataDir) {
        if (!file.getFileSystem().supportedFileAttributeViews().contains("unix")) {
            return true;
        }
        try {
            return Files.getAttribute(file, "unix:uid", LinkOption.NOFOLLOW_LINKS)
                    .equals(Files.getAttribute(dataDir, "unix:uid"));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Whether {@code file} keeps the core's cache in this data directory, as
     * every release's session configuration has: another program's names a
     * cache of its own, or none. Read as a stream, so a large file costs no
     * memory.
     */
    private static boolean cachesIn(Path file, Path dataDir) {
        try (JsonParser parser = JSON.createParser(file)) {
            if (parser.nextToken() != JsonToken.START_OBJECT
                    || !enter(parser, "experimental") || !enter(parser, "cache_file")) {
                return false;
            }
            String cache = stringField(parser, "path");
            return cache != null && Path.of(cache).startsWith(dataDir);
        } catch (JacksonException | InvalidPathException e) {
            return false;
        }
    }

    /** Moves into the object at {@code name} in the object the parser is in. */
    private static boolean enter(JsonParser parser, String name) {
        for (String field = parser.nextName(); field != null; field = parser.nextName()) {
            JsonToken value = parser.nextToken();
            if (field.equals(name)) {
                return value == JsonToken.START_OBJECT;
            }
            parser.skipChildren();
        }
        return false;
    }

    /** The string at {@code name} in the object the parser is in, or null. */
    private static String stringField(JsonParser parser, String name) {
        for (String field = parser.nextName(); field != null; field = parser.nextName()) {
            JsonToken value = parser.nextToken();
            if (field.equals(name)) {
                return value == JsonToken.VALUE_STRING ? parser.getString() : null;
            }
            parser.skipChildren();
        }
        return null;
    }

    /** Removes 1.25's run folder once nothing is left in it. */
    private static void removeIfEmpty(Path dir) {
        try {
            Files.deleteIfExists(dir);
        } catch (DirectoryNotEmptyException stillHolding) {
            // A stop signal an earlier release left: empty, and nobody's secret.
        } catch (IOException e) {
            log.debug("Could not remove the data folder's run folder: {}", e.toString());
        }
    }
}
