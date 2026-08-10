package com.vlessclient.service.mcp;

import com.vlessclient.service.Redact;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link McpAuditLog} that emits one tab-separated line per tool call through
 * the dedicated {@code mcp.audit} logback logger, which writes to a rolling
 * {@code mcp-audit.log} (size- and age-bounded, see {@code logback.xml}). Using
 * logback gives rotation and cleanup for free instead of an unbounded append.
 */
public class FileAuditLog implements McpAuditLog {

    private static final Logger AUDIT = LoggerFactory.getLogger("mcp.audit");

    @Override
    public void record(String tool, String argsSummary, boolean error, String message) {
        AUDIT.info(String.join("\t",
                Instant.now().toString(),
                tool,
                error ? "ERROR" : "OK",
                sanitize(argsSummary),
                sanitize(message)));
    }

    /**
     * Keeps one call on one line, and takes a second pass at any URL that
     * reached this far. McpServer already redacts the arguments structurally;
     * this catches the {@code message} side, where a tool's exception text can
     * quote back the share link it failed to parse.
     */
    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return Redact.urlsIn(value)
                .replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
