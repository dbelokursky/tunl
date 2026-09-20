package com.vlessclient.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The texts call the core one thing: «ядро VPN», "the VPN core".
 *
 * <p>It had four names: "sing-box", «ядро VPN», «ядро» and «Процесс», so a
 * refusal said "sing-box не поддерживает…" beside a notice that said «Ядро VPN
 * неожиданно завершилось». The program's own name stays where the program
 * itself is meant: installing it, its file and its version.</p>
 */
class BundleCoreNameTest {

    /** Texts about the program itself, which name it. */
    private static final Set<String> THE_PROGRAM = Set.of(
            "error.singbox.not.found",
            "settings.singbox.version",
            "installer.downloading",
            "installer.skip",
            "installer.error.body",
            "dashboard.singbox.missing.title",
            "dashboard.error.singbox.body");

    /** «ядро» in any case, not followed by «VPN». */
    private static final Pattern BARE_YADRO = Pattern.compile(
            "(?iu)(?<!\\p{L})ядр(о|а|у|ом|е)(?!\\p{L})(?!\\s+VPN)");

    /** "core" not preceded by "VPN". */
    private static final Pattern BARE_CORE = Pattern.compile("(?i)(?<!VPN )\\bcore\\b");

    @Test
    void onlyTheProgramsOwnTextsNameIt() {
        assertThat(naming("/i18n/messages_en.properties", "sing-box"))
                .as("English texts calling the core sing-box").isEmpty();
        assertThat(naming("/i18n/messages_ru.properties", "sing-box"))
                .as("Russian texts calling the core sing-box").isEmpty();
    }

    @Test
    void theCoreIsTheVpnCore() {
        assertThat(matching("/i18n/messages_en.properties", BARE_CORE))
                .as("English texts saying core without VPN").isEmpty();
        assertThat(matching("/i18n/messages_ru.properties", BARE_YADRO))
                .as("Russian texts saying «ядро» without VPN").isEmpty();
    }

    private static Map<String, String> naming(String bundle, String name) {
        Map<String, String> found = new TreeMap<>();
        load(bundle).forEach((key, value) -> {
            if (!THE_PROGRAM.contains(key.toString()) && value.toString().contains(name)) {
                found.put(key.toString(), value.toString());
            }
        });
        return found;
    }

    private static Map<String, String> matching(String bundle, Pattern pattern) {
        Map<String, String> found = new TreeMap<>();
        load(bundle).forEach((key, value) -> {
            if (pattern.matcher(value.toString()).find()) {
                found.put(key.toString(), value.toString());
            }
        });
        return found;
    }

    private static Properties load(String bundle) {
        Properties properties = new Properties();
        try (InputStream in = BundleCoreNameTest.class.getResourceAsStream(bundle)) {
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return properties;
    }
}
