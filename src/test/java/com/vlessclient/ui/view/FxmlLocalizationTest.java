package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import com.vlessclient.app.UiTestServices;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputControl;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * Every {@code text="..."} in an FXML file is a placeholder the controller is
 * expected to replace through {@code I18n}; the bundle test cannot see a
 * placeholder that never references a key. Rendering each view in Russian
 * and looking for the English placeholders still on screen catches both
 * kinds of miss at once: a literal with no fx:id, and an fx:id nothing
 * binds. The view titles, the whole MCP block and the routing hints all
 * shipped that way.
 */
public class FxmlLocalizationTest extends ApplicationTest {

    private static final Pattern ATTRIBUTE =
            Pattern.compile("\\b(?:text|promptText)=\"([^\"]*)\"");
    private static final Pattern LETTERS = Pattern.compile("[A-Za-z]{2,}");

    /**
     * A sample value a prompt shows rather than a word: no spaces, and a
     * digit, dot, slash or dash somewhere ({@code /ws}, {@code utun99},
     * {@code https://1.1.1.1/dns-query}, a UUID mask).
     */
    private static final Pattern SAMPLE_VALUE = Pattern.compile("^\\S*[0-9./\\-]\\S*$");

    /**
     * Literals that are the same in every language: an acronym, a shell
     * command, a uTLS fingerprint name shown as a sample. Anything whose
     * Russian translation is spelled exactly like the English (TLS, Reality)
     * is accepted through the bundle itself.
     */
    private static final Set<String> NEUTRAL = Set.of("ALPN", "brew install sing-box", "chrome");

    private static final List<String> VIEWS = List.of(
            "/fxml/MainView.fxml", "/fxml/DashboardView.fxml", "/fxml/ServersView.fxml",
            "/fxml/SubscriptionsView.fxml", "/fxml/RoutingView.fxml", "/fxml/LogsView.fxml",
            "/fxml/SettingsView.fxml", "/fxml/ServerFormView.fxml");

    private Stage stage;

    @BeforeAll
    static void setupHeadless() {
        System.setProperty("testfx.robot", "glass");
        System.setProperty("testfx.headless", "true");
        System.setProperty("prism.order", "sw");
        System.setProperty("prism.text", "t2k");
        System.setProperty("java.awt.headless", "true");
        UiTestServices.initialize();
    }

    @AfterAll
    static void restoreLocale() {
        I18n.setLocale(Locale.ENGLISH);
    }

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        stage.show();
    }

    @Test
    void everyFxmlPlaceholderIsReplacedWhenTheLocaleIsRussian() throws IOException {
        Set<String> russianValues = bundleValues("/i18n/messages_ru.properties");
        List<String> leaks = new ArrayList<>();
        for (String view : VIEWS) {
            List<String> placeholders = placeholdersIn(view);
            Set<String> rendered = renderedTexts(view);
            for (String placeholder : placeholders) {
                if (rendered.contains(placeholder) && !translatesToItself(placeholder, russianValues)) {
                    leaks.add(view.substring(view.lastIndexOf('/') + 1) + ": \"" + placeholder + "\"");
                }
            }
        }
        assertThat(leaks)
                .as("FXML placeholders still on screen in Russian; bind them through I18n")
                .isEmpty();
    }

    /** "TLS" is "TLS" in Russian too; the bundle, not a list here, says so. */
    private static boolean translatesToItself(String placeholder, Set<String> russianValues) {
        String bare = placeholder.endsWith(" *")
                ? placeholder.substring(0, placeholder.length() - 2) : placeholder;
        return russianValues.contains(bare);
    }

    private static Set<String> bundleValues(String resource) throws IOException {
        java.util.Properties bundle = new java.util.Properties();
        try (InputStream in = FxmlLocalizationTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            bundle.load(in);
        }
        Set<String> values = new java.util.HashSet<>();
        bundle.values().forEach(value -> values.add(value.toString()));
        return values;
    }

    private static List<String> placeholdersIn(String view) throws IOException {
        String fxml;
        try (InputStream in = FxmlLocalizationTest.class.getResourceAsStream(view)) {
            assertThat(in).as(view).isNotNull();
            fxml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> placeholders = new ArrayList<>();
        Matcher matcher = ATTRIBUTE.matcher(fxml);
        while (matcher.find()) {
            String literal = matcher.group(1).replace("&amp;", "&");
            if (LETTERS.matcher(literal).find() && !NEUTRAL.contains(literal)
                    && !SAMPLE_VALUE.matcher(literal).matches()) {
                placeholders.add(literal);
            }
        }
        return placeholders;
    }

    private Set<String> renderedTexts(String view) {
        Set<String> texts = new java.util.HashSet<>();
        interact(() -> {
            I18n.setLocale(Locale.of("ru"));
            try {
                Parent root = new FXMLLoader(getClass().getResource(view)).load();
                stage.setScene(new Scene(root, 900, 700));
                for (Node node : root.lookupAll("*")) {
                    if (node instanceof Labeled labeled && labeled.getText() != null) {
                        texts.add(labeled.getText());
                    }
                    if (node instanceof TextInputControl input && input.getPromptText() != null) {
                        texts.add(input.getPromptText());
                    }
                    if (node instanceof ComboBox<?> combo && combo.getPromptText() != null) {
                        texts.add(combo.getPromptText());
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("could not load " + view, e);
            }
        });
        return texts;
    }
}
