package com.vlessclient.service.mcp;

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

    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
