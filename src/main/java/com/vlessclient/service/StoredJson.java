package com.vlessclient.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.core.JacksonException;
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
                return new Parsed(mapper.readTree(Files.readAllBytes(file)));
            } catch (IOException e) {
                last = e;
            } catch (JacksonException e) {
                return new Damaged(e);
            }
            if (attempt < ATTEMPTS && !pause()) {
                break;
            }
        }
        return new Unopenable(last);
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
