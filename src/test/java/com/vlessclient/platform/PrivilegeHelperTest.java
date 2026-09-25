package com.vlessclient.platform;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /**
     * The administrator dialog said that "osascript" wanted to make changes;
     * it now says why Tunl asks. The prompt is an AppleScript string, so a
     * quote or a backslash in a translation must not end it early.
     */
    @Test
    void theAdministratorDialogSaysWhyItAsks() {
        assertThat(PrivilegeHelper.adminScript("echo ok", "Tunl needs a password"))
                .isEqualTo("do shell script \"echo ok\" with prompt \"Tunl needs a password\""
                        + " with administrator privileges");
        assertThat(PrivilegeHelper.adminScript("echo ok", "Say \"TUN\" \\ then"))
                .contains("with prompt \"Say \\\"TUN\\\" \\\\ then\"");
        assertThat(PrivilegeHelper.adminScript("echo ok", " "))
                .isEqualTo("do shell script \"echo ok\" with administrator privileges");
    }

    /**
     * AppleScript's error -128 is the dialog dismissed, in the system's
     * language: the connect is cancelled, not failed, and no every-connect
     * prompt follows it.
     */
    @Test
    void aDismissedDialogIsTheUserCancellingNotAFailure() {
        assertThat(PrivilegeHelper.failure(1, "0:245: execution error: User canceled. (-128)"))
                .isInstanceOf(ElevationDeclinedException.class);
        assertThat(PrivilegeHelper.failure(1,
                "0:12: execution error: Пользователь отменил. (-128)"))
                .isInstanceOf(ElevationDeclinedException.class);
        assertThat(PrivilegeHelper.failure(1, "visudo: parse error"))
                .isNotInstanceOf(ElevationDeclinedException.class)
                .hasMessageContaining("visudo: parse error");
    }

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

        String cmd = PrivilegeHelper.configureShellCommand(userBinary, "alice", false);

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
        // The rule is written into a root-owned mktemp, validated, then
        // installed — never installed from a caller-supplied path.
        assertThat(cmd).contains("STAGE=\"$(mktemp)\"");
        assertThat(cmd).contains("visudo -c -f \"$STAGE\"");
        assertThat(cmd).contains(
                "install -m 0440 -o root -g wheel \"$STAGE\" '/etc/sudoers.d/vless-client'");
        assertThat(cmd).contains("rm -f '/etc/sudoers.d/vless-client' \"$STAGE\"");
        assertThat(cmd).contains("exit 1");
    }

    /**
     * The TOCTOU fix: root generates the rule itself, so its content is fixed
     * before validation. The staged rule is validated (visudo -c) and only then
     * installed — an attacker has no user-writable file to swap in between.
     */
    @Test
    void configureCommandValidatesTheStagedRuleBeforeInstallingIt() {
        String cmd = PrivilegeHelper.configureShellCommand(
                Path.of("/Users/alice/bin/sing-box"), "alice", false);

        // The exact rule is echoed into the temp; no user path is trusted.
        assertThat(cmd).contains("echo 'alice ALL=(root) NOPASSWD: "
                + "/usr/local/libexec/vless-client/sing-box run -c "
                + "/usr/local/libexec/vless-client/run/tun-config.json' > \"$STAGE\"");
        // Validation strictly precedes the install into sudoers.d.
        assertThat(cmd.indexOf("visudo -c -f \"$STAGE\""))
                .as("the staged rule is validated before it is installed")
                .isLessThan(cmd.indexOf("install -m 0440 -o root -g wheel \"$STAGE\""));
    }

    /**
     * The shell-level {@code echo} of the single-quoted rule must reproduce
     * {@link PrivilegeHelper#sudoersRule} byte for byte — a quoting slip would
     * write a different (possibly wider) rule. Runs the echo through /bin/sh.
     */
    @Test
    void echoedRuleReproducesSudoersRuleThroughTheShell() throws Exception {
        String literal = "'alice ALL=(root) NOPASSWD: "
                + "/usr/local/libexec/vless-client/sing-box run -c "
                + "/usr/local/libexec/vless-client/run/tun-config.json'";
        Process p = new ProcessBuilder("/bin/sh", "-c", "echo " + literal)
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();

        assertThat(out).isEqualTo(PrivilegeHelper.sudoersRule("alice"));
    }

    /**
     * Readiness must be decided from the NOPASSWD entry itself. Asking
     * {@code sudo -l <command>} cannot work: an admin account carries a blanket
     * {@code (ALL) ALL}, so every command reports as permitted (with a
     * password) and the check passes even with no rule installed at all.
     * Sample output below is the real format from a live macOS box.
     */
    @Test
    void pinnedRuleIsRecognizedInRealSudoOutput() {
        String listing = """
                Matching Defaults entries for dima on Dmitrijs-MacBook-Pro:
                    env_reset, env_keep+=BLOCKSIZE

                User dima may run the following commands on Dmitrijs-MacBook-Pro:
                    (ALL) ALL
                    (root) NOPASSWD: /usr/local/libexec/vless-client/sing-box run -c \
                /usr/local/libexec/vless-client/run/tun-config.json""";

        assertThat(PrivilegeHelper.hasPinnedRule(listing)).isTrue();
    }

    /**
     * The upgrade path that would otherwise never happen: an install carrying
     * the old wide rule must read as NOT configured, so the privileged setup
     * re-runs and replaces it. Reporting it as configured would leave the
     * escalation in place forever on every existing install.
     */
    @Test
    void preHardeningWideRuleReadsAsNotConfigured() {
        String listing = """
                User dima may run the following commands on host:
                    (ALL) ALL
                    (root) NOPASSWD: /usr/local/libexec/vless-client/sing-box""";

        assertThat(PrivilegeHelper.hasPinnedRule(listing)).isFalse();
    }

    @Test
    void blanketAdminAccessAloneIsNotEnough() {
        // No entry for our binary at all — an admin's (ALL) ALL must not count.
        String listing = """
                User dima may run the following commands on host:
                    (ALL) ALL""";

        assertThat(PrivilegeHelper.hasPinnedRule(listing)).isFalse();
        assertThat(PrivilegeHelper.hasPinnedRule("")).isFalse();
        assertThat(PrivilegeHelper.hasPinnedRule(null)).isFalse();
    }

    @Test
    void aRuleForADifferentConfigPathDoesNotCount() {
        String listing = """
                User dima may run the following commands on host:
                    (root) NOPASSWD: /usr/local/libexec/vless-client/sing-box run -c /tmp/evil.json""";

        assertThat(PrivilegeHelper.hasPinnedRule(listing)).isFalse();
    }

    /**
     * The startup offer must fire only for the wide rule left by an older
     * build — not for "no rule yet" (nothing to heal; the first TUN connect
     * installs it) and not for the pinned rule (already safe). Prompting for a
     * password at launch in either of those cases would be unexplained.
     */
    /**
     * Where the launcher is used the rule names it with no arguments at all
     * ({@code ""}): the config travels on stdin, so there is no path for the
     * caller to pick, and nothing on the command line for the launcher to read.
     */
    @Test
    void launcherRuleAuthorizesTheLauncherWithNoArguments() {
        assertThat(PrivilegeHelper.launcherRule("alice")).isEqualTo(
                "alice ALL=(root) NOPASSWD: /usr/local/libexec/vless-client/tun-launch \"\"\n");
    }

    @Test
    void echoedLauncherRuleReproducesTheRuleThroughTheShell() throws Exception {
        String cmd = PrivilegeHelper.configureShellCommand(
                Path.of("/Users/alice/bin/sing-box"), "alice", true);
        String literal = cmd.substring(cmd.indexOf("echo 'alice") + "echo ".length(),
                cmd.indexOf(" > \"$STAGE\""));

        assertThat(shell("echo " + literal)).isEqualTo(PrivilegeHelper.launcherRule("alice"));
    }

    /**
     * Root writes the launcher from text carried in the command itself — no
     * file the user could swap between the prompt and the install — into its
     * own mktemp file, and installs it root:wheel 0755 before the rule that
     * authorizes it, with the root-only state directory beside it.
     */
    @Test
    void configureCommandWithTheLauncherInstallsItBeforeTheRule() throws Exception {
        String cmd = PrivilegeHelper.configureShellCommand(
                Path.of("/Users/alice/bin/sing-box"), "alice", true);

        Matcher encoded = Pattern.compile("echo '([A-Za-z0-9+/=]+)' \\| /usr/bin/base64 -D"
                + " > \"\\$LAUNCH\"").matcher(cmd);
        assertThat(encoded.find()).as(cmd).isTrue();
        assertThat(shell("echo '" + encoded.group(1) + "' | /usr/bin/base64 -D"))
                .isEqualTo(PrivilegeHelper.launcherScript(Path.of("/usr/local/libexec/vless-client")));
        assertThat(cmd).contains("LAUNCH=\"$(mktemp)\"");
        assertThat(cmd).contains("install -m 0755 -o root -g wheel \"$LAUNCH\" "
                + "'/usr/local/libexec/vless-client/tun-launch'");
        assertThat(cmd).contains(
                "install -d -m 0700 -o root -g wheel '/usr/local/libexec/vless-client/state'");
        assertThat(cmd.indexOf("'/usr/local/libexec/vless-client/tun-launch'"))
                .as("the launcher is in place before the rule that runs it")
                .isLessThan(cmd.indexOf("install -m 0440 -o root -g wheel \"$STAGE\""));
        assertThat(cmd).contains("echo 'alice ALL=(root) NOPASSWD: "
                + "/usr/local/libexec/vless-client/tun-launch \"\"' > \"$STAGE\"");
        assertThat(cmd).doesNotContain("run -c");
        // A failure anywhere leaves no rule and no root temp behind.
        assertThat(cmd).contains(
                "rm -f '/etc/sudoers.d/vless-client' \"$STAGE\" \"$LAUNCH\" 2>/dev/null; exit 1;");
    }

    @Test
    void launcherScriptNamesItsBaseAndRefusesOneItCannotQuote() {
        String script = PrivilegeHelper.launcherScript(Path.of("/opt/tunl"));

        assertThat(script).startsWith("#!/bin/sh\n").contains("base='/opt/tunl'\n")
                .doesNotContain("@BASE@");
        assertThatThrownBy(() -> PrivilegeHelper.launcherScript(Path.of("/opt/it's")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The listing's spelling of an empty argument list is sudo's business: the
     * rule reads as the launcher's whichever of the three ways it comes back,
     * since the launcher ignores its arguments anyway.
     */
    @Test
    void launcherRuleIsRecognizedHoweverTheListingSpellsItsEmptyArguments() {
        for (String command : new String[] {
                "/usr/local/libexec/vless-client/tun-launch \"\"",
                "/usr/local/libexec/vless-client/tun-launch \\\"\\\"",
                "/usr/local/libexec/vless-client/tun-launch"}) {
            String listing = """
                    User dima may run the following commands on host:
                        (ALL) ALL
                        (root) NOPASSWD: %s""".formatted(command);

            assertThat(PrivilegeHelper.hasLauncherRule(listing)).as(command).isTrue();
        }
    }

    @Test
    void thePinnedRuleIsNotTheLaunchersAndNeitherIsAnotherCommand() {
        String pinned = """
                User dima may run the following commands on host:
                    (root) NOPASSWD: /usr/local/libexec/vless-client/sing-box run -c \
                /usr/local/libexec/vless-client/run/tun-config.json""";
        String other = """
                User dima may run the following commands on host:
                    (root) NOPASSWD: /usr/local/libexec/vless-client/tun-launch-evil""";

        assertThat(PrivilegeHelper.hasLauncherRule(pinned)).isFalse();
        assertThat(PrivilegeHelper.hasLauncherRule(other)).isFalse();
        assertThat(PrivilegeHelper.hasLauncherRule("")).isFalse();
        assertThat(PrivilegeHelper.hasLauncherRule(null)).isFalse();
    }

    /**
     * The startup offer: the wide rule always, the pinned rule only where the
     * launcher can replace it (on macOS 13 and 14 the pinned rule is as far as
     * it goes), and never the launcher's own rule or no rule at all.
     */
    @Test
    void anOlderRuleIsOfferedForReplacementWhereSomethingNarrowerCanReplaceIt() {
        String wide = """
                User dima may run the following commands on host:
                    (root) NOPASSWD: /usr/local/libexec/vless-client/sing-box""";
        String pinned = """
                User dima may run the following commands on host:
                    (root) NOPASSWD: /usr/local/libexec/vless-client/sing-box run -c \
                /usr/local/libexec/vless-client/run/tun-config.json""";
        String launcher = """
                User dima may run the following commands on host:
                    (root) NOPASSWD: /usr/local/libexec/vless-client/tun-launch \"\"""";
        String none = """
                User dima may run the following commands on host:
                    (ALL) ALL""";

        assertThat(PrivilegeHelper.hasRuleToReplace(wide, true)).isTrue();
        assertThat(PrivilegeHelper.hasRuleToReplace(wide, false)).isTrue();
        assertThat(PrivilegeHelper.hasRuleToReplace(pinned, true)).isTrue();
        assertThat(PrivilegeHelper.hasRuleToReplace(pinned, false)).isFalse();
        assertThat(PrivilegeHelper.hasRuleToReplace(launcher, true)).isFalse();
        assertThat(PrivilegeHelper.hasRuleToReplace(none, true)).isFalse();
    }

    private static String shell(String command) throws Exception {
        Process p = new ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return out;
    }

    @Test
    void legacyWideRuleIsDistinguishedFromNoRuleAndFromThePinnedRule() {
        String wide = """
                User dima may run the following commands on host:
                    (ALL) ALL
                    (root) NOPASSWD: /usr/local/libexec/vless-client/sing-box""";
        String pinned = """
                User dima may run the following commands on host:
                    (root) NOPASSWD: /usr/local/libexec/vless-client/sing-box run -c \
                /usr/local/libexec/vless-client/run/tun-config.json""";
        String none = """
                User dima may run the following commands on host:
                    (ALL) ALL""";

        assertThat(PrivilegeHelper.hasLegacyWideRule(wide)).isTrue();
        assertThat(PrivilegeHelper.hasLegacyWideRule(pinned)).isFalse();
        assertThat(PrivilegeHelper.hasLegacyWideRule(none)).isFalse();
        assertThat(PrivilegeHelper.hasLegacyWideRule("")).isFalse();
        assertThat(PrivilegeHelper.hasLegacyWideRule(null)).isFalse();
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
