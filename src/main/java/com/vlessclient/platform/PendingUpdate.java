package com.vlessclient.platform;

import java.nio.file.Path;

/**
 * A downloaded, verified installer waiting on disk for the next application
 * start. Produced by {@code UpdateStaging} when a background download
 * completes, consumed by {@link UpdateApplier} at the next launch.
 *
 * <p>The digest travels with the file for a reason: it is checked twice. Once
 * against the bytes as they arrive, and again immediately before the installer
 * is handed to the OS — an installer that sits in the staging directory
 * between two runs is a file anything on the machine could have swapped in the
 * meantime, and by then it is about to be executed with the user's rights.</p>
 *
 * @param version   the release version this installer upgrades to, without a
 *                  leading {@code v}
 * @param installer the verified installer file (DMG/MSI/DEB)
 * @param digest    the {@code sha256:<hex>} the release published for it
 */
public record PendingUpdate(String version, Path installer, String digest) {

    /**
     * Rejects an update that could not be applied safely: a nameless version,
     * no file, or a digest that is missing or in some other algorithm than the
     * SHA-256 the verification step knows how to check.
     */
    public PendingUpdate {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version is required");
        }
        if (installer == null) {
            throw new IllegalArgumentException("installer is required");
        }
        if (digest == null || !digest.startsWith("sha256:")) {
            throw new IllegalArgumentException("sha256 digest is required, got: " + digest);
        }
    }
}
