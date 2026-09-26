package com.vlessclient.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An optional service is looked up with {@link ServiceLocator#find}, never
 * with {@link ServiceLocator#get} inside a catch of
 * {@code IllegalArgumentException}.
 *
 * <p>{@code get} throws that exception for a missing service. It is also what
 * the parsers throw for bad input, what {@code Path.of} throws for a bad path
 * and what {@code URI.create} throws for a bad address. Seventeen lookups
 * were wrapped that way, so a catch meant for a missing service also
 * swallowed whatever else went wrong in its block.</p>
 */
class ServiceLookupStyleTest {

    @Test
    void noLookupIsWrappedInACatchOfIllegalArgumentException() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(Path.of("src", "main", "java"))) {
            for (Path file : tree.filter(path -> path.toString().endsWith(".java")).toList()) {
                found.addAll(wrappedLookups(file));
            }
        }

        assertThat(found).as("ServiceLocator.get inside try { } catch (IllegalArgumentException)")
                .isEmpty();
    }

    /**
     * The lookups in {@code file} whose try block ends in a catch of
     * {@code IllegalArgumentException}. The block is found by indentation,
     * which Checkstyle holds to four spaces a level: the {@code try} is the
     * nearest line above the catch that opens one at the catch's own depth.
     */
    private static List<String> wrappedLookups(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<String> found = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.contains("catch (IllegalArgumentException")) {
                continue;
            }
            String indent = line.substring(0, line.length() - line.stripLeading().length());
            for (int j = i - 1; j >= 0; j--) {
                String above = lines.get(j);
                if (above.startsWith(indent + "try")) {
                    for (int k = j + 1; k < i; k++) {
                        if (lines.get(k).contains("ServiceLocator.get(")) {
                            found.add(file.getFileName() + ":" + (k + 1));
                        }
                    }
                    break;
                }
            }
        }
        return found;
    }
}
