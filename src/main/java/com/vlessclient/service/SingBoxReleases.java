package com.vlessclient.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports whether a newer sing-box has been released than the one this build
 * pins — and stops there.
 *
 * <p>Nothing here installs anything, deliberately. The core is pinned because
 * the config generator is written against that exact version and proved
 * against it by the real-binary smoke suite; a core that arrived on its own
 * would be one nothing had ever run the generator past. It also executes as
 * root under the TUN sudoers rule, which makes an unattended download of it a
 * different proposition from replacing the app. Bumping the pin is a pull
 * request (see {@code .github/workflows/bump-singbox.yml}); this line in
 * Settings exists so somebody knows to look.</p>
 *
 * <p>Asked once per run, when Settings first builds its About block — not on
 * a schedule. One request an hour of the sixty an unauthenticated caller gets
 * per address is worth a line of text; more would not be.</p>
 */
public final class SingBoxReleases {

    private static final Logger log = LoggerFactory.getLogger(SingBoxReleases.class);

    static final String LATEST_URL =
            "https://api.github.com/repos/SagerNet/sing-box/releases/latest";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Remembered for the run: the answer cannot usefully change mid-session. */
    private volatile Optional<String> cached;

    public SingBoxReleases() {
        this(HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build());
    }

    SingBoxReleases(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * The newest released sing-box, when it is newer than what is running.
     *
     * @param currentVersion the version of the core in use, e.g. {@code 1.13.14}
     * @return the newer version, or empty when there is none or the check failed
     */
    public Optional<String> newerThan(String currentVersion) {
        return newerOf(latestStable(), currentVersion);
    }

    /**
     * The comparison itself, kept apart from the request that feeds it.
     *
     * @param latest         what the API reported, if anything
     * @param currentVersion the running core's version
     * @return {@code latest} when it is genuinely newer, otherwise empty
     */
    static Optional<String> newerOf(Optional<String> latest, String currentVersion) {
        if (latest.isEmpty() || currentVersion == null || currentVersion.isBlank()) {
            return Optional.empty();
        }
        return UpdateManager.isNewerVersion(latest.get(), currentVersion)
                ? latest
                : Optional.empty();
    }

    /**
     * The latest stable release tag, without its {@code v}.
     *
     * <p>{@code /releases/latest} is the endpoint that skips prereleases,
     * which matters here: sing-box publishes alpha, beta and rc tags
     * continuously, and none of those are what the pin should follow.</p>
     *
     * @return the version, or empty when it could not be established
     */
    Optional<String> latestStable() {
        Optional<String> remembered = cached;
        if (remembered != null) {
            return remembered;
        }
        Optional<String> answer = fetch();
        // Only a real answer is remembered; a failure should not silence the
        // check for the rest of the run.
        if (answer.isPresent()) {
            cached = answer;
        }
        return answer;
    }

    private Optional<String> fetch() {
        try {
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(LATEST_URL))
                            .header("Accept", "application/vnd.github+json")
                            .timeout(HTTP_TIMEOUT)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.info("sing-box release check returned status {}", response.statusCode());
                return Optional.empty();
            }
            return parseTag(response.body());
        } catch (IOException | InterruptedException e) {
            log.info("sing-box release check failed: {}", e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }
    }

    /**
     * Reads the version out of a Releases API response.
     *
     * @param json the response body
     * @return the tag without its {@code v}, or empty when there is none
     */
    Optional<String> parseTag(String json) {
        try {
            String tag = objectMapper.readTree(json).path("tag_name").asText("");
            String version = UpdateManager.stripVersionPrefix(tag);
            return version.isBlank() ? Optional.empty() : Optional.of(version);
        } catch (Exception e) {
            log.info("Unreadable sing-box release response: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
