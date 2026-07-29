package com.vlessclient.service.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link McpAuditLog} that appends one tab-separated line per tool call to
 * {@code logs/mcp-audit.log} in the app data dir. Best-effort: failures to write
 * are logged but never propagate to the caller.
 */
public class FileAuditLog implements McpAuditLog {

    private static final Logger log = LoggerFactory.getLogger(FileAuditLog.class);

    private final Path auditFile;

    public FileAuditLog(Path dataDir) {
        this.auditFile = dataDir.resolve("logs").resolve("mcp-audit.log");
    }

    @Override
    public synchronized void record(String tool, String argsSummary, boolean error,
                                    String message) {
        String line = String.join("\t",
                Instant.now().toString(),
                tool,
                error ? "ERROR" : "OK",
                sanitize(argsSummary),
                sanitize(message)) + System.lineSeparator();
        try {
            Files.createDirectories(auditFile.getParent());
            Files.writeString(auditFile, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Failed to write MCP audit entry: {}", e.getMessage());
        }
    }

    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
