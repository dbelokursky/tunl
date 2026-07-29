package com.vlessclient.service.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Set;

/**
 * Manages the bearer token that guards the local MCP server.
 *
 * <p>The token is a 256-bit random value stored as hex in {@code mcp-token}
 * inside the app data dir, with {@code 0600} permissions so only the current
 * user can read it. Agents present it as {@code Authorization: Bearer <token>}.
 * The file is created on first access and can be regenerated on demand.</p>
 */
public class McpTokenStore {

    private static final Logger log = LoggerFactory.getLogger(McpTokenStore.class);
    private static final String TOKEN_FILE = "mcp-token";
    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path tokenPath;

    public McpTokenStore(Path dataDir) {
        this.tokenPath = dataDir.resolve(TOKEN_FILE);
    }

    /** @return the on-disk token path (for surfacing in the UI). */
    public Path tokenPath() {
        return tokenPath;
    }

    /**
     * Reads the existing token, generating and persisting a new one if none
     * exists yet.
     *
     * @return the current bearer token (hex)
     * @throws IOException if the token can't be read or created
     */
    public synchronized String getOrCreate() throws IOException {
        if (Files.exists(tokenPath)) {
            String existing = Files.readString(tokenPath, StandardCharsets.UTF_8).trim();
            if (!existing.isEmpty()) {
                return existing;
            }
        }
        return regenerate();
    }

    /**
     * Generates a fresh token, overwriting any existing one, and returns it.
     */
    public synchronized String regenerate() throws IOException {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);

        Files.createDirectories(tokenPath.getParent());
        Files.writeString(tokenPath, token, StandardCharsets.UTF_8);
        restrictPermissions(tokenPath);
        log.info("Generated new MCP token at {}", tokenPath);
        return token;
    }

    private void restrictPermissions(Path path) {
        try {
            Set<PosixFilePermission> ownerOnly =
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, ownerOnly);
        } catch (UnsupportedOperationException | IOException e) {
            // Non-POSIX filesystem (unlikely on macOS) — best effort only.
            log.warn("Could not restrict permissions on {}: {}", path, e.getMessage());
        }
    }
}
