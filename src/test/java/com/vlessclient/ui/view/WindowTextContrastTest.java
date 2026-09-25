package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.app.ThemeCss;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.PersistenceState;
import com.vlessclient.service.ShareLinkParser;
import com.vlessclient.service.TestConfigStores;
import com.vlessclient.testing.Contrast;
import com.vlessclient.testing.UiTest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextField;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every text in the window can be seen, in both themes.
 *
 * <p>Modena colours unstyled text by the background it sits on,
 * {@code ladder(-fx-background, …)}, and the scroll pane every page sits in
 * sets {@code -fx-background: transparent}, which the ladder reads as dark.
 * So a Label, RadioButton or Hyperlink the app gave no class of its own came
 * out white: in the light theme the two choices of "What goes through the
 * VPN" were white on a white card, and nobody could see what they chose
 * between (item 1.3 of the 2026-09-25 review). The banners above the pages,
 * outside that scroll pane, got Modena's #333 on the dark theme's warning
 * tint. The README's screenshots are dark, so none of it showed.</p>
 *
 * <p>Every page is shown, and every state that brings text of its own: the
 * first-run link (followed too, since Modena gives a visited link another
 * colour), a search that matches nothing, and both banners. Each visible
 * label is measured against what is painted behind it, from the computed
 * style ({@link Contrast}).</p>
 */
@UiTest
public class WindowTextContrastTest extends ApplicationTest {

    /**
     * The bar is "can be seen at all": white on white measures 1.0. Several
     * colours still measure under WCAG AA — the server address, the empty
     * state's hint — and raising those is a step of its own; this bar goes up
     * to {@link Contrast#READABLE} with it.
     */
    private static final double VISIBLE = 1.5;

    private static final String LINK = "vless://11111111-2222-3333-4444-555555555555"
            + "@198.51.100.7:443?type=tcp#Netherlands%2001";

    @TempDir
    static Path tempDir;

    private Scene scene;
    private MainViewController window;
    private ConfigStore store;

    @Override
    public void start(Stage stage) throws Exception {
        // A store of its own: the first-run state needs no servers, and the
        // unreadable-entries banner, once raised, stays up for the store's life.
        store = TestConfigStores.at(tempDir.resolve(UUID.randomUUID().toString()));
        ServiceLocator.register(ConfigStore.class, store);
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainView.fxml"));
        scene = new Scene(loader.load(), 1024, 720);
        window = loader.getController();
        stage.setScene(scene);
        stage.show();
    }

    @ParameterizedTest
    @ValueSource(strings = {"light", "dark"})
    void everyTextInTheWindowCanBeSeen(String theme) {
        interact(() -> scene.getStylesheets().setAll(ThemeCss.of(theme)));
        List<String> unseen = new ArrayList<>();

        measure(theme + ", dashboard on the first run", window::showDashboard, unseen);
        Hyperlink addServer = lookup("#addServerLink").queryAs(Hyperlink.class);
        assertThat(addServer.isVisible()).as("the first-run link is on screen").isTrue();
        measure(theme + ", dashboard, the link followed", () -> addServer.setVisited(true),
                unseen);

        ServerConfig server = new ShareLinkParser().parse(LINK);
        interact(() -> store.addServer(server));
        measure(theme + ", dashboard", window::showDashboard, unseen);
        measure(theme + ", servers", window::showServers, unseen);
        measure(theme + ", servers, a search that matches nothing",
                () -> lookup("#searchField").queryAs(TextField.class).setText("no such server"),
                unseen);
        measure(theme + ", subscriptions", window::showSubscriptions, unseen);
        measure(theme + ", routing", window::showRouting, unseen);
        measure(theme + ", logs", window::showLogs, unseen);
        measure(theme + ", settings", window::showSettings, unseen);

        PersistenceState persistence = store.getPersistenceState();
        measure(theme + ", both banners up", () -> {
            persistence.failed("servers.json", () -> { });
            persistence.couldNotRead("routing.json", 2);
        }, unseen);

        assertThat(unseen)
                .as("text drawn in (nearly) the colour of what is behind it, contrast < %s",
                        VISIBLE)
                .isEmpty();
    }

    /** Shows a state, then measures every visible label in the window. */
    private void measure(String state, Runnable show, List<String> unseen) {
        interact(show);
        interact(() -> {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            for (Labeled labeled : visibleLabels(scene.getRoot(), new ArrayList<>())) {
                if (!(labeled.getTextFill() instanceof Color text)) {
                    continue;
                }
                Color behind = backdrop(labeled);
                double ratio = Contrast.ratio(text, behind);
                if (ratio < VISIBLE) {
                    unseen.add(String.format(Locale.ROOT, "%s: %s \"%s\" %s on %s = %.2f",
                            state, describe(labeled), labeled.getText(), text, behind, ratio));
                }
            }
        });
    }

    private static List<Labeled> visibleLabels(Node node, List<Labeled> found) {
        if (!node.isVisible()) {
            return found;
        }
        if (node instanceof Labeled labeled && isWords(labeled.getText())) {
            found.add(labeled);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                visibleLabels(child, found);
            }
        }
        return found;
    }

    /**
     * Text that says something: a letter or a digit in it. A glyph alone — the
     * empty state's emoji, a "+" or "✕" — is an icon, drawn in a colour font
     * or read by its shape, and held to the non-text contrast rule instead.
     */
    private static boolean isWords(String text) {
        return text != null && text.codePoints().anyMatch(Character::isLetterOrDigit);
    }

    /**
     * What the text is read against: the fills behind it, topmost first, down
     * to the first opaque one, the translucent ones blended over it. A button
     * paints its own background; a label shows what its containers paint.
     */
    private static Color backdrop(Node node) {
        List<Color> layers = new ArrayList<>();
        for (Node at = node; at != null; at = at.getParent()) {
            if (!(at instanceof Region region) || region.getBackground() == null) {
                continue;
            }
            List<BackgroundFill> fills = region.getBackground().getFills();
            for (int i = fills.size() - 1; i >= 0; i--) {
                Color fill = Contrast.flat(fills.get(i).getFill());
                if (fill.getOpacity() == 0) {
                    continue;
                }
                layers.add(fill);
                if (fill.getOpacity() >= 0.99) {
                    return blend(layers);
                }
            }
        }
        layers.add(Color.WHITE); // the scene's own fill
        return blend(layers);
    }

    /** Layers topmost first, the last one opaque. */
    private static Color blend(List<Color> layers) {
        Color result = layers.getLast();
        for (int i = layers.size() - 2; i >= 0; i--) {
            Color top = layers.get(i);
            double alpha = top.getOpacity();
            result = Color.color(
                    top.getRed() * alpha + result.getRed() * (1 - alpha),
                    top.getGreen() * alpha + result.getGreen() * (1 - alpha),
                    top.getBlue() * alpha + result.getBlue() * (1 - alpha));
        }
        return result;
    }

    private static String describe(Node node) {
        String id = node.getId() == null ? "" : "#" + node.getId() + " ";
        return id + node.getClass().getSimpleName() + node.getStyleClass();
    }
}
