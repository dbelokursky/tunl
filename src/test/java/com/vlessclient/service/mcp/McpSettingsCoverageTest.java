package com.vlessclient.service.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vlessclient.model.AppSettings;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Holds what MCP reads and writes to what the app persists, the way
 * {@code I18nBundleConsistencyTest} holds the two language bundles together.
 *
 * <p>Each new setting used to need four hand edits for MCP, and some never
 * got them: {@code tun_ipv6_enabled} could be set but not read back, and
 * three settings could be neither. Now a new {@link AppSettings} property
 * fails here until it is either reported by {@code get_settings} or listed
 * below with the reason it is not.</p>
 */
class McpSettingsCoverageTest {

    /** Persisted settings MCP deliberately does not expose, and why. */
    private static final Map<String, String> NOT_EXPOSED = Map.of(
            "config_version", "the file's format version, not a setting",
            "traffic_history_expanded", "whether a dashboard card is folded",
            "health_check_auto_reconnect", "tuned on the reachability card with its targets",
            "health_check_interval_seconds", "tuned on the reachability card with its targets",
            "health_check_delay_seconds", "tuned on the reachability card with its targets",
            "health_check_targets", "tuned on the reachability card with its targets",
            "store_secrets_securely", "moves every credential in or out of the keychain",
            "device_id", "identifies this install to subscription providers",
            "share_local_proxy_in_tun",
            "opens the local proxy to every program; the user's choice, in Settings");

    /** A reported name that is not the key in camel case, kept for agents that read it. */
    private static final Map<String, String> REPORTED_AS = Map.of(
            "health_check_enabled", "healthCheck");

    @Test
    void everyPersistedSettingIsReportedOrKeptBackOnPurpose() {
        Set<String> reported = reportedNames();

        List<String> unaccounted = persistedKeys().stream()
                .filter(key -> !NOT_EXPOSED.containsKey(key))
                .filter(key -> !reported.contains(reportedName(key)))
                .toList();

        assertThat(unaccounted)
                .as("settings get_settings does not report; add them to SettingsInfo, "
                        + "or to NOT_EXPOSED with a reason")
                .isEmpty();
    }

    @Test
    void everySettingAnAgentCanSetItCanReadBack() {
        Set<String> reported = reportedNames();

        assertThat(McpSettings.writableKeys())
                .allSatisfy(key -> assertThat(reported).as(key).contains(reportedName(key)));
    }

    @Test
    void theListsNameOnlySettingsThatExist() {
        Set<String> persisted = Set.copyOf(persistedKeys());

        assertThat(persisted).containsAll(NOT_EXPOSED.keySet());
        assertThat(persisted).containsAll(McpSettings.writableKeys());
        assertThat(McpSettings.writableKeys()).doesNotContainAnyElementsOf(NOT_EXPOSED.keySet());
    }

    private static List<String> persistedKeys() {
        return Arrays.stream(AppSettings.class.getDeclaredFields())
                .map(field -> field.getAnnotation(JsonProperty.class))
                .filter(annotation -> annotation != null)
                .map(JsonProperty::value)
                .toList();
    }

    private static Set<String> reportedNames() {
        return Arrays.stream(SettingsInfo.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
    }

    private static String reportedName(String key) {
        String alias = REPORTED_AS.get(key);
        if (alias != null) {
            return alias;
        }
        StringBuilder camel = new StringBuilder();
        boolean upper = false;
        for (char c : key.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                camel.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return camel.toString();
    }
}
