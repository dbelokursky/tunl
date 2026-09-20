package com.vlessclient.service;

import com.vlessclient.service.outbound.OutboundTags;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** One running core's manual selector, including the configuration it actually loaded. */
final class LiveSelector {

    private static final Logger log = LoggerFactory.getLogger(LiveSelector.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    // Keys that can name the proxy group. replaceReferences walks nested
    // objects, so "detour" also covers a remote rule set's http_client.detour.
    private static final Set<String> REFERENCES = Set.of(
            "tag", "outbound", "detour", "final");
    /** Whether the configuration has a manual selector to switch. */
    private final boolean manual;
    /** The configuration as generated, but for the manual selector's pick. */
    private final JsonNode loaded;
    private final String groupTag;
    private final String config;
    private final URI endpoint;
    private final String secret;

    LiveSelector(String generated) {
        ObjectNode root = (ObjectNode) MAPPER.readTree(generated);
        manual = manualSelector(root) != null;
        loaded = withoutPick(root);
        // sing-box restores a cached selection before considering "default".
        // Give each process its own selector key so an older choice cannot
        // override the user's current selection. Rule-set caches remain shared.
        groupTag = manual ? "proxy-" + UUID.randomUUID() : OutboundTags.PROXY;
        if (manual) {
            replaceReferences(root);
        }
        config = MAPPER.writeValueAsString(root);
        JsonNode api = root.path("experimental").path("clash_api");
        endpoint = URI.create("http://" + api.path("external_controller")
                .asString("127.0.0.1:0") + "/proxies/" + groupTag);
        secret = api.path("secret").asString("");
    }

    String config() {
        return config;
    }

    String groupTag() {
        return groupTag;
    }

    /** Whether a server of a configuration generated now can be picked on this core. */
    boolean accepts(String generated) {
        return manual && matches(generated);
    }

    /**
     * Whether a configuration generated now is the one this core loaded, but
     * for the manual selector's pick, which changes without a restart. Unlike
     * {@link #accepts}, it answers for every group: with the automatic
     * selection there is no selector to switch, yet the core may still run
     * the settings as they are.
     *
     * @param generated a configuration generated from the current settings
     * @return true when a restart would load the same configuration
     */
    boolean matches(String generated) {
        return loaded.equals(withoutPick(MAPPER.readTree(generated)));
    }

    boolean select(String serverTag) {
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build()) {
            String body = MAPPER.writeValueAsString(
                    MAPPER.createObjectNode().put("name", serverTag));
            HttpResponse<String> response = client.send(request()
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 204) {
                return false;
            }
            HttpResponse<String> selected = client.send(request().GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return selected.statusCode() == 200
                    && serverTag.equals(MAPPER.readTree(selected.body()).path("now").asString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.debug("Live selector unavailable: {}", e.toString());
            return false;
        }
    }

    private HttpRequest.Builder request() {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(3));
        if (!secret.isBlank()) {
            request.header("Authorization", "Bearer " + secret);
        }
        return request;
    }

    private static JsonNode withoutPick(JsonNode root) {
        JsonNode copy = root.deepCopy();
        ObjectNode selector = manualSelector(copy);
        if (selector != null) {
            selector.remove("default");
        }
        return copy;
    }

    /** The manual selector among the outbounds, or null with the automatic selection. */
    private static ObjectNode manualSelector(JsonNode root) {
        for (JsonNode outbound : root.path("outbounds")) {
            if (OutboundTags.PROXY.equals(outbound.path("tag").asString())
                    && "selector".equals(outbound.path("type").asString())) {
                return (ObjectNode) outbound;
            }
        }
        return null;
    }

    private void replaceReferences(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            for (String key : REFERENCES) {
                if (OutboundTags.PROXY.equals(object.path(key).asString())) {
                    object.put(key, groupTag);
                }
            }
        }
        if (node.isContainer()) {
            for (JsonNode child : node) {
                replaceReferences(child);
            }
        }
    }
}
