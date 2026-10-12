package com.vlessclient.ui.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.ThemeCss;
import com.vlessclient.testing.UiTest;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javafx.css.CssMetaData;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * What base.css declares is what JavaFX draws.
 *
 * <p>JavaFX says nothing about a declaration it cannot use. It keeps the
 * first family of a {@code -fx-font-family} list and drops the rest, so
 * {@code "Menlo", "Monaco", "Courier New", monospace} was "Menlo" alone: on
 * Windows and Linux, which have no Menlo, the log, the bypass list and the
 * install command came out in the proportional System font and the log's
 * columns no longer lined up. And {@code -fx-text-transform} and
 * {@code -fx-letter-spacing} are not JavaFX properties at all; the headers
 * they were meant to set in tracked capitals never were.</p>
 */
@UiTest
public class StylesheetDeclarationsTest extends ApplicationTest {

    private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern RULE = Pattern.compile("([^{}]+)\\{([^{}]*)}");
    private static final Pattern DECLARATION = Pattern.compile("(-fx-[a-z-]+)\\s*:([^;]*);");

    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
    }

    @Test
    void everyFontFamilyIsASingleFamily() throws IOException {
        List<String> lists = new ArrayList<>();
        for (Map.Entry<String, String> rule : rules("/css/base.css").entrySet()) {
            Matcher declaration = DECLARATION.matcher(rule.getValue());
            while (declaration.find()) {
                if (declaration.group(1).equals("-fx-font-family")
                        && declaration.group(2).contains(",")) {
                    lists.add(rule.getKey() + " {" + declaration.group(0).strip() + "}");
                }
            }
        }

        assertThat(lists)
                .withFailMessage("JavaFX keeps the first family of a list and drops the rest, "
                        + "so these fall back to System wherever it is missing:%n  %s",
                        String.join("\n  ", lists))
                .isEmpty();
    }

    @Test
    void everyPropertyIsOneJavaFxDefines() throws Exception {
        Set<String> known = javaFxProperties();
        Set<String> unknown = new TreeSet<>();
        for (Map.Entry<String, String> rule : rules("/css/base.css").entrySet()) {
            Matcher declaration = DECLARATION.matcher(rule.getValue());
            while (declaration.find()) {
                if (!known.contains(declaration.group(1))) {
                    unknown.add(rule.getKey() + " {" + declaration.group(1) + "}");
                }
            }
        }

        assertThat(known).as("the properties JavaFX's own classes declare")
                .contains("-fx-font-family", "-fx-background-color", "-fx-padding",
                        "-fx-text-base-color")
                .doesNotContain("-fx-text-transform", "-fx-letter-spacing");
        assertThat(unknown)
                .withFailMessage("properties JavaFX does not define, which it ignores "
                        + "without a word:%n  %s", String.join("\n  ", unknown))
                .isEmpty();
    }

    /**
     * The log, its highlighted lines, the bypass list and the install command
     * come out in a monospaced face on whatever OS this runs on.
     */
    @Test
    void theCodeLikeTextIsMonospacedHere() {
        List<String> proportional = new ArrayList<>();
        interact(() -> {
            // A log line as LogsViewController draws it: a TextFlow of Text
            // nodes carrying only their segment's class, in the list's cell.
            Label logCell = styled(new Label("INFO[0000] started"), "list-cell");
            Text logText = styled(new Text("INFO[0000] started"), "log-ts");
            Label highlightedCell = styled(new Label(), "list-cell");
            highlightedCell.setGraphic(styled(new TextFlow(logText), "log-line-flow"));
            TextArea bypass = styled(new TextArea("10.0.0.0/8"), "code-area");
            Label command = styled(new Label("brew install sing-box"), "banner-command");
            Parent root = new VBox(
                    styled(new VBox(logCell, highlightedCell), "log-list-view"),
                    bypass,
                    styled(new HBox(command), "singbox-missing-banner"));
            Scene scene = new Scene(root, 400, 300);
            scene.getStylesheets().setAll(ThemeCss.light());
            stage.setScene(scene);
            stage.show();
            root.applyCss();
            check(".log-list-view .list-cell", logCell.getFont(), proportional);
            check(".log-line-flow Text", logText.getFont(), proportional);
            check(".code-area", bypass.getFont(), proportional);
            check(".banner-command", command.getFont(), proportional);
        });

        assertThat(proportional)
                .withFailMessage("drawn in a proportional face:%n  %s",
                        String.join("\n  ", proportional))
                .isEmpty();
    }

    /**
     * A Mac keeps Menlo, the face the monospace rules were written for, under
     * the {@code .os-mac} class MainViewController gives the window's root
     * there: JavaFX draws its logical monospace in Courier New on a Mac. Each
     * monospace rule needs its {@code .os-mac} line, or one added alone comes
     * out in Courier New on a Mac.
     */
    @Test
    void aMacKeepsMenloForEveryMonospaceRule() throws IOException {
        Set<String> monospace = new TreeSet<>();
        for (String selector : selectorsSetting("/css/base.css", "monospace")) {
            monospace.add(".os-mac " + selector);
        }
        Set<String> menlo = selectorsSetting("/css/base.css", "\"Menlo\"");

        assertThat(monospace).as("the rules set in monospace").isNotEmpty();
        assertThat(menlo).as("the rules set in Menlo on a Mac").isEqualTo(monospace);
    }

    private static <T extends Node> T styled(T node, String styleClass) {
        node.getStyleClass().add(styleClass);
        return node;
    }

    private static void check(String rule, Font font, List<String> proportional) {
        double narrow = widthOf("iiiiiiiiii", font);
        double wide = widthOf("WWWWWWWWWW", font);
        if (Math.abs(narrow - wide) > 0.5) {
            proportional.add("%s: %s, ten i %.1f wide and ten W %.1f".formatted(
                    rule, font.getName(), narrow, wide));
        }
    }

    private static double widthOf(String text, Font font) {
        Text probe = new Text(text);
        probe.setFont(font);
        return probe.getLayoutBounds().getWidth();
    }

    private static Set<String> selectorsSetting(String stylesheet, String family)
            throws IOException {
        Set<String> selectors = new TreeSet<>();
        for (Map.Entry<String, String> rule : rules(stylesheet).entrySet()) {
            Matcher declaration = DECLARATION.matcher(rule.getValue());
            while (declaration.find()) {
                if (declaration.group(1).equals("-fx-font-family")
                        && declaration.group(2).strip().equals(family)) {
                    for (String selector : rule.getKey().split(",")) {
                        selectors.add(selector.strip().replaceAll("\\s+", " "));
                    }
                }
            }
        }
        return selectors;
    }

    /** Selector to declarations, in the file's order, comments left out. */
    private static Map<String, String> rules(String stylesheet) throws IOException {
        String css;
        try (InputStream in = StylesheetDeclarationsTest.class.getResourceAsStream(stylesheet)) {
            assertThat(in).as(stylesheet).isNotNull();
            css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, String> rules = new TreeMap<>();
        Matcher rule = RULE.matcher(COMMENT.matcher(css).replaceAll(""));
        while (rule.find()) {
            rules.merge(rule.group(1).strip(), rule.group(2), String::concat);
        }
        return rules;
    }

    /**
     * Every CSS property a public class of javafx.graphics or javafx.controls
     * declares, the sub-properties of shorthands such as {@code -fx-font} and
     * {@code -fx-background} included, skins and all; and every name Modena
     * declares, since base.css redefines some of Modena's looked-up colours
     * ({@code -fx-background}, {@code -fx-text-base-color}).
     */
    @SuppressWarnings("unchecked")
    private static Set<String> javaFxProperties() throws Exception {
        Set<String> names = new TreeSet<>();
        for (Class<?> anchor : List.of(Node.class, Control.class)) {
            Path jar = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                for (ZipEntry entry : Collections.list(zip.entries())) {
                    String name = entry.getName();
                    if (name.endsWith("/modena.css")) {
                        try (InputStream in = zip.getInputStream(entry)) {
                            Matcher declared = DECLARATION.matcher(
                                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
                            while (declared.find()) {
                                names.add(declared.group(1));
                            }
                        }
                    }
                    if (!name.startsWith("javafx/") || !name.endsWith(".class")
                            || name.contains("$")) {
                        continue;
                    }
                    Method declared;
                    try {
                        Class<?> type = Class.forName(name.substring(0, name.length() - 6)
                                .replace('/', '.'), false, anchor.getClassLoader());
                        declared = type.getMethod("getClassCssMetaData");
                    } catch (ReflectiveOperationException | LinkageError e) {
                        continue;
                    }
                    if (Modifier.isStatic(declared.getModifiers())) {
                        for (CssMetaData<?, ?> meta
                                : (List<CssMetaData<?, ?>>) declared.invoke(null)) {
                            collect(meta, names);
                        }
                    }
                }
            }
        }
        return names;
    }

    private static void collect(CssMetaData<?, ?> meta, Set<String> names) {
        names.add(meta.getProperty());
        if (meta.getSubProperties() != null) {
            for (CssMetaData<?, ?> sub : meta.getSubProperties()) {
                collect(sub, names);
            }
        }
    }
}
