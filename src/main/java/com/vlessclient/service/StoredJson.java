package com.vlessclient.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads a data file's JSON and says which way it failed: a file that could
 * not be opened is left as it is, where one that does not parse is set aside.
 *
 * <p>Jackson reads a file itself and reports both as a {@link JacksonException},
 * an unopenable file as a {@code JacksonIOException} around the
 * {@link IOException}, and the stores took both for damage. A
 * {@code servers.json} that a virus scanner or a sync client held for a
 * moment, or that a restore left owned by another user, was renamed to
 * {@code .corrupt-…}, and the app started with no servers.</p>
 */
final class StoredJson {

    /** How a read ended. */
    sealed interface Read permits Parsed, Missing, Unopenable, Damaged {
    }

    /** The file's JSON. */
    record Parsed(JsonNode root) implements Read {
    }

    /** No file: a first start. */
    record Missing() implements Read {
    }

    /** The file is there and could not be read: it stays, untouched. */
    record Unopenable(IOException cause) implements Read {
    }

    /** The file was read and is not JSON: it is damaged. */
    record Damaged(JacksonException cause) implements Read {
    }

    /**
     * Reads it a few times before calling it unopenable: a scanner or a sync
     * client lets go of a file within a moment.
     */
    private static final int ATTEMPTS = 3;
    private static final long PAUSE_MS = 150;

    private StoredJson() {
    }

    /**
     * Reads {@code file}.
     *
     * @param mapper the mapper to read with
     * @param file   the data file
     * @return what the read found
     */
    static Read read(ObjectMapper mapper, Path file) {
        // notExists, not !exists: a path that cannot be looked at (a directory
        // without access) is not a missing file, and reading it says why.
        if (Files.notExists(file)) {
            return new Missing();
        }
        IOException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            // Read here, then parsed: the read's exceptions come from NIO and
            // say why (AccessDeniedException, or Windows' "being used by
            // another process"), where Jackson's own FileInputStream says
            // "not found"; and whatever the parse throws is about the content.
            try {
                JsonNode root = mapper.readTree(Files.readAllBytes(file));
                if (holdsNothing(root)) {
                    return new Damaged(new StreamReadException(
                            "the file holds no JSON value: it is empty, blank or null"));
                }
                return new Parsed(root);
            } catch (IOException e) {
                last = e;
            } catch (JacksonException e) {
                // Where it broke, not Jackson's message, which quotes the
                // token it could not read: in a servers.json whose credential
                // lost its quotes, that token is the credential, and the
                // stores log the cause.
                return new Damaged(new StreamReadException("not valid JSON (" + where(e) + ")"));
            }
            if (attempt < ATTEMPTS && !pause()) {
                break;
            }
        }
        return new Unopenable(last);
    }

    /**
     * Whether a parse found no value at all. Jackson reads an empty or blank
     * file as a missing node and {@code null} as a null node, and throws for
     * neither, so both came back as parsed. Every store then mapped the root
     * to null and dereferenced it: a settings.json truncated to 0 bytes threw
     * a NullPointerException in ConfigStore's constructor, and the app did
     * not start again until the file was deleted by hand.
     */
    private static boolean holdsNothing(JsonNode root) {
        return root == null || root.isMissingNode() || root.isNull();
    }

    /** Waits before the next attempt; false when interrupted. */
    private static boolean pause() {
        try {
            Thread.sleep(PAUSE_MS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Where a parse or a mapping failed, for the log: the exception's kind,
     * and the line and column or the field of what could not be read, but
     * never its message. Jackson's message quotes the token or the value it
     * could not read, and in a file edited by hand that is a credential which
     * lost its quotes, or one pasted into a field of another type.
     *
     * @param failure what Jackson threw
     * @return a description holding nothing of the content
     */
    static String where(JacksonException failure) {
        StringBuilder where = new StringBuilder(failure.getClass().getSimpleName());
        TokenStreamLocation location = failure.getLocation();
        if (location != null && location.getLineNr() > 0) {
            where.append(" at line ").append(location.getLineNr())
                    .append(", column ").append(location.getColumnNr());
        }
        String field = field(failure);
        if (field != null) {
            where.append(" in field ").append(field);
        }
        return where.toString();
    }

    /**
     * The field a mapping failed in, as the entry's class names it, or null
     * when the entry itself is of the wrong kind. Only the first of the path:
     * further in, a map's keys come from the file.
     *
     * @param failure what Jackson threw
     * @return the entry's field, or null
     */
    static String field(JacksonException failure) {
        List<JacksonException.Reference> path = failure.getPath();
        return path.isEmpty() ? null : path.getFirst().getPropertyName();
    }

    /**
     * The reason an unopenable file gives, for the log and the banner: the
     * exception's class and message, without a stack.
     *
     * @param cause what the read threw
     * @return a short reason
     */
    static String reason(IOException cause) {
        if (cause == null) {
            return "unknown error";
        }
        String message = cause.getMessage();
        String type = cause.getClass().getSimpleName();
        return message == null || message.isBlank() ? type : type + ": " + message;
    }
}
