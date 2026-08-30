package com.vlessclient.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

/**
 * Persisted routing configuration: per-country bypass, a user bypass list,
 * and custom routing rules. All three sections compose — each contributes
 * its rules whenever it is non-empty, and "route everything through the
 * VPN" is simply all of them empty. The pre-composition {@code preset}
 * field (route_all / bypass_domestic / custom made the sections mutually
 * exclusive) is still read so {@link
 * com.vlessclient.service.RoutingService} can migrate old files
 * conservatively.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RoutingConfig {

    /**
     * The retired preset from pre-composition routing.json files, captured
     * for migration only: never applied at runtime, never written back.
     */
    private transient String legacyPreset;

    /**
     * ISO-3166 country codes (lowercase) whose geosite / geoip regions stay
     * off the VPN. Empty means no country bypass — all traffic through the
     * tunnel. Normalised through {@link #setBypassCountries(List)}:
     * lowercase, trimmed, deduped, and restricted to real ISO codes so a
     * typo can never become a 404 rule-set URL that stops sing-box from
     * starting. Persisted as {@code bypass_countries}; the pre-multi
     * {@code bypass_country} string is still read (see
     * {@link #setLegacyBypassCountry(String)}).
     */
    private List<String> bypassCountries = new ArrayList<>();

    private static final java.util.Set<String> ISO_COUNTRIES = java.util.Set.of(
            java.util.Locale.getISOCountries());

    @JsonProperty("rules")
    private List<RoutingRule> rules = new ArrayList<>();

    /**
     * User-maintained list of URL/host patterns that should BYPASS the proxy
     * (go direct). One entry per element. Supports:
     * <ul>
     *   <li>{@code example.com} — exact domain match</li>
     *   <li>{@code *.example.com} or {@code .example.com} — domain + all subdomains</li>
     *   <li>{@code *example*} — substring / keyword match</li>
     *   <li>{@code 192.168.0.0/16} — IPv4/IPv6 CIDR</li>
     *   <li>{@code 203.0.113.42} — single IP (becomes /32)</li>
     *   <li>{@code https://foo.com/path} — URL form, scheme and path stripped</li>
     * </ul>
     */
    @JsonProperty("bypass_list")
    private List<String> bypassList = new ArrayList<>();

    // The bypass_lan field used to live here as a user-visible toggle. It
    // was removed in v0.1.7 — LAN bypass is now unconditional because there
    // is no realistic use case for routing local traffic through a remote
    // VPN in this client, and the toggle's "off" state combined with
    // strict_route caused widespread direct-outbound timeouts. Legacy
    // routing.json files with "bypass_lan": false are silently accepted and
    // ignored via @JsonIgnoreProperties(ignoreUnknown = true).

    // geoip_path / geosite_path used to live here as paths to the
    // sing-geoip / sing-geosite .db files downloaded by a "Download
    // Geodata" button. sing-box 1.12+ doesn't read .db files at all —
    // country matching is done through remote rule_set entries in the
    // generated config (see SingBoxConfigGenerator#buildRemoteRuleSet),
    // which sing-box itself fetches as .srs at startup. The fields are
    // gone from the model; legacy routing.json with them is silently
    // accepted via @JsonIgnoreProperties(ignoreUnknown = true).

    public RoutingConfig() {
    }

    public List<String> getBypassList() {
        return bypassList;
    }

    public void setBypassList(List<String> bypassList) {
        this.bypassList = bypassList == null
                ? new ArrayList<>() : new ArrayList<>(bypassList);
    }

    /**
     * Captures the retired {@code preset} field from pre-composition files;
     * {@link com.vlessclient.service.RoutingService} uses it to migrate.
     * Write-only for Jackson, so it is never serialized back.
     */
    @JsonProperty("preset")
    private void setLegacyPreset(String preset) {
        this.legacyPreset = preset;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getLegacyPreset() {
        return legacyPreset;
    }

    /** Clears the migration marker once the config has been migrated. */
    public void clearLegacyPreset() {
        this.legacyPreset = null;
    }

    @JsonProperty("bypass_countries")
    public List<String> getBypassCountries() {
        return bypassCountries;
    }

    /**
     * Sets the bypass countries, normalising each entry to a lowercase ISO
     * code (sing-box's geosite/geoip lookups are case-sensitive and the
     * shipped databases use lowercase keys), dropping blanks and anything
     * that is not a real ISO-3166 code — an invalid code would become a
     * remote rule-set URL that 404s and stops sing-box from starting —
     * and de-duplicating while keeping first-seen order. Empty is a valid
     * result: no country bypass.
     */
    @JsonProperty("bypass_countries")
    public void setBypassCountries(List<String> countries) {
        java.util.LinkedHashSet<String> clean = new java.util.LinkedHashSet<>();
        if (countries != null) {
            for (String c : countries) {
                if (c == null || c.isBlank()) {
                    continue;
                }
                String code = c.trim().toLowerCase(java.util.Locale.ROOT);
                if (ISO_COUNTRIES.contains(code.toUpperCase(java.util.Locale.ROOT))) {
                    clean.add(code);
                }
            }
        }
        this.bypassCountries = new ArrayList<>(clean);
    }

    /**
     * Read-only bridge for the pre-multi {@code bypass_country} string field:
     * legacy routing.json files carry a single code, which folds into the
     * list form. Never written back — serialization emits only
     * {@code bypass_countries}.
     */
    @JsonProperty("bypass_country")
    private void setLegacyBypassCountry(String country) {
        if (country != null && !country.isBlank()) {
            setBypassCountries(List.of(country));
        }
    }

    public List<RoutingRule> getRules() {
        return rules;
    }

    public void setRules(List<RoutingRule> rules) {
        this.rules = rules == null ? new ArrayList<>() : new ArrayList<>(rules);
    }
}
