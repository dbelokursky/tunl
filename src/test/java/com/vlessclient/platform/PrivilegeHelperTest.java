package com.vlessclient.platform;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-logic tests for the hardened macOS privilege setup: the sudoers rule
 * and privileged shell command must authorize a ROOT-OWNED sing-box copy, not
 * the user-writable binary, and the content check must detect a drifted copy.
 * The privileged runtime (osascript, install, sudo -n) is validated manually.
 *
 * <p>macOS-only: {@code PrivilegeHelper} is a macOS component and its fixed
 * POSIX paths render with backslashes via {@code Path.toString()} on Windows,
 * so the literal-path assertions only hold where the code actually runs.</p>
 */
@EnabledOnOs(OS.MAC)
class PrivilegeHelperTest {

    @TempDir
    Path tempDir;

    @Test
    void sudoersRulePinsTheRootOwnedPathNotAUserPath() {
        String rule = PrivilegeHelper.sudoersRule("alice");

        assertThat(rule).isEqualTo(
                "alice ALL=(root) NOPASSWD: /usr/local/libexec/vless-client/sing-box"
                        + " run -c /usr/local/libexec/vless-client/run/tun-config.json\n");
        // The escalation this fixes: no user-home path may be authorized.
        assertThat(rule).doesNotContain("/Users/").doesNotContain("Library");
    }

    /**
     * The rule must authorize ONE command line, not a binary. Authorizing the
     * bare binary lets any process running as the user pass `run -c own.json`,
     * whose log.output writes any file as root — a local root primitive with no
     * interaction. sudo matches arguments literally, so pinning them is the fix.
     */
    @Test
    void sudoersRulePinsTheArgumentsSoAnArbitraryConfigCannotBePassed() {
        String rule = PrivilegeHelper.sudoersRule("alice");

        String authorized = rule.substring(rule.indexOf("NOPASSWD: ") + "NOPASSWD: ".length()).trim();
        // Not just the binary: the config path is part of what sudo matches.
        assertThat(authorized).isNotEqualTo("/usr/local/libexec/vless-client/sing-box");
        assertThat(authorized).endsWith("run -c " + PrivilegeHelper.elevatedConfig());
        // The pinned config path must be space-free: sudoers splits on spaces,
        // so a path needing escapes would silently widen what matches.
        assertThat(PrivilegeHelper.elevatedConfig().toString()).doesNotContain(" ");
    }

    @Test
    void configureCommandInstallsARootOwnedCopyBeforeWritingTheRule() {
        Path userBinary = Path.of("/Users/alice/Library/Application Support/VlessClient/bin/sing-box");
        Path stagedRule = tempDir.resolve("rule.tmp");

        String cmd = PrivilegeHelper.configureShellCommand(userBinary, stagedRule, "alice");

        // Creates the root-owned dir and installs the binary root:wheel 0755
        // at the elevated path — so the user can no longer swap what runs as root.
        assertThat(cmd).contains("mkdir -p '/usr/local/libexec/vless-client'");
        assertThat(cmd).contains(
                "install -m 0755 -o root -g wheel "
                        + "'/Users/alice/Library/Application Support/VlessClient/bin/sing-box' "
                        + "'/usr/local/libexec/vless-client/sing-box'");
        // The run dir holding the one authorized config: user-owned so the app
        // can rewrite it per connection, 0700 so nobody else can read the
        // credentials in it, inside the root-owned parent.
        assertThat(cmd).contains(
                "install -d -m 0700 -o 'alice' -g staff '/usr/local/libexec/vless-client/run'");
        // Then the rule, validated with visudo, removed on failure.
        assertThat(cmd).contains("install -m 0440 -o root -g wheel");
        assertThat(cmd).contains("'/etc/sudoers.d/vless-client'");
        assertThat(cmd).contains("visudo -c -f '/etc/sudoers.d/vless-client'");
        assertThat(cmd).contains("rm -f '/etc/sudoers.d/vless-client'; exit 1");
    }

    @Test
    void elevatedBinaryIsTheRootOwnedLocation() {
        assertThat(PrivilegeHelper.elevatedBinary())
                .isEqualTo(Path.of("/usr/local/libexec/vless-client/sing-box"));
    }

    @Test
    void elevatedConfigLivesInTheRunDirUnderTheRootOwnedParent() {
        assertThat(PrivilegeHelper.elevatedConfig())
                .isEqualTo(Path.of("/usr/local/libexec/vless-client/run/tun-config.json"));
        // Same parent as the binary, so the privileged setup owns both.
        // Compared as paths, not via AssertJ's startsWith, which resolves
        // against the real filesystem and would need the file to exist.
        assertThat(PrivilegeHelper.elevatedConfig()
                .startsWith(PrivilegeHelper.elevatedBinary().getParent())).isTrue();
    }

    @Test
    void sameContentDetectsAMatchingCopyAndADriftedOne() throws Exception {
        Path a = Files.writeString(tempDir.resolve("a"), "sing-box v1.13.14 bytes");
        Path same = Files.writeString(tempDir.resolve("same"), "sing-box v1.13.14 bytes");
        Path drifted = Files.writeString(tempDir.resolve("drifted"), "sing-box v1.14.0 bytes");

        assertThat(PrivilegeHelper.sameContent(a, same)).isTrue();
        assertThat(PrivilegeHelper.sameContent(a, drifted)).isFalse();
        // A missing root copy (never configured) reads as not-matching.
        assertThat(PrivilegeHelper.sameContent(a, tempDir.resolve("absent"))).isFalse();
    }
}
