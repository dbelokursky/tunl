package com.vlessclient.service.mcp;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The bearer token that guards the local MCP server: created once, reused
 * until regenerated, never blank, and readable by nobody else.
 */
class McpTokenStoreTest {

    private static final boolean POSIX = FileSystems.getDefault()
            .supportedFileAttributeViews().contains("posix");
    private static final String HEX_256_BIT = "[0-9a-f]{64}";

    @TempDir
    Path tempDir;

    @Test
    void getOrCreateWritesA256BitHexTokenOnceAndServesItAfterwards() throws IOException {
        McpTokenStore store = new McpTokenStore(tempDir);

        String first = store.getOrCreate();

        assertThat(first).matches(HEX_256_BIT);
        assertThat(store.tokenPath()).isEqualTo(tempDir.resolve("mcp-token"));
        assertThat(Files.readString(store.tokenPath())).isEqualTo(first);
        assertThat(store.getOrCreate()).isEqualTo(first);
        assertThat(new McpTokenStore(tempDir).getOrCreate())
                .as("a second store over the same directory reads the token, not a new one")
                .isEqualTo(first);
    }

    @Test
    void getOrCreateCreatesTheDataDirectoryItLivesIn() throws IOException {
        Path dataDir = tempDir.resolve("nested").resolve("data");
        McpTokenStore store = new McpTokenStore(dataDir);

        String token = store.getOrCreate();

        assertThat(Files.readString(dataDir.resolve("mcp-token"))).isEqualTo(token);
    }

    @Test
    void regenerateReplacesTheTokenOnDiskAndForLaterReads() throws IOException {
        McpTokenStore store = new McpTokenStore(tempDir);
        String first = store.getOrCreate();

        String second = store.regenerate();

        assertThat(second).matches(HEX_256_BIT).isNotEqualTo(first);
        assertThat(Files.readString(store.tokenPath())).isEqualTo(second);
        assertThat(store.getOrCreate()).isEqualTo(second);
        assertThat(store.regenerate()).isNotEqualTo(second);
    }

    @Test
    void aBlankTokenFileIsReplacedRatherThanServedAsAnEmptyToken() throws IOException {
        McpTokenStore store = new McpTokenStore(tempDir);
        Files.writeString(store.tokenPath(), " \n\t\n");

        String token = store.getOrCreate();

        assertThat(token).matches(HEX_256_BIT);
        assertThat(Files.readString(store.tokenPath())).isEqualTo(token);
    }

    @Test
    void whitespaceAroundAStoredTokenIsNotPartOfIt() throws IOException {
        McpTokenStore store = new McpTokenStore(tempDir);
        Files.writeString(store.tokenPath(), "  abc123\n");

        assertThat(store.getOrCreate()).isEqualTo("abc123");
    }

    @Test
    void theTokenFileIsReadableByItsOwnerOnly() throws IOException {
        assumeTrue(POSIX, "POSIX permissions only");
        McpTokenStore store = new McpTokenStore(tempDir);

        store.getOrCreate();
        assertThat(Files.getPosixFilePermissions(store.tokenPath()))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));

        store.regenerate();
        assertThat(Files.getPosixFilePermissions(store.tokenPath()))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    /** A token file an older build left world-readable is tightened on the next read. */
    @Test
    void aWorldReadableTokenFromAnOlderBuildIsTightenedWhenServed() throws IOException {
        assumeTrue(POSIX, "POSIX permissions only");
        McpTokenStore store = new McpTokenStore(tempDir);
        Files.createDirectories(store.tokenPath().getParent());
        Files.writeString(store.tokenPath(), "0123456789abcdef");
        Files.setPosixFilePermissions(store.tokenPath(),
                PosixFilePermissions.fromString("rw-r--r--"));

        assertThat(store.getOrCreate()).isEqualTo("0123456789abcdef");
        assertThat(Files.getPosixFilePermissions(store.tokenPath()))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }
}
