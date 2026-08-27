package com.vlessclient.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Windows: {@code sing-box.exe} shipped as a {@code .zip}, extracted in-process. */
public final class WindowsCorePlatform implements CorePlatform {

    @Override
    public String osKey() {
        return "windows";
    }

    @Override
    public String binaryName() {
        return "sing-box.exe";
    }

    @Override
    public String archiveExtension() {
        return "zip";
    }

    @Override
    public void extract(Path archive, Path destDir) throws IOException {
        Path root = destDir.toAbsolutePath().normalize();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = root.resolve(entry.getName()).normalize();
                // Zip-slip guard: never write outside the destination directory.
                if (!target.startsWith(root)) {
                    throw new IOException("Zip entry escapes target dir: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    if (target.equals(root)) {
                        throw new IOException("Zip entry does not name a file: " + entry.getName());
                    }
                    Files.createDirectories(SecureFiles.parentDirectory(target));
                    Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
