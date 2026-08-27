package com.vlessclient.ui.view;

import com.vlessclient.app.UiTestServices;
import java.util.ArrayList;
import java.util.List;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Group;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every page starts at the same edge, because none of them insets itself.
 *
 * <p>MainView's content area already pads each page by 24 on all sides. A page
 * that adds padding of its own is inset twice, and since only some of them did
 * it, switching views moved the content sideways: Settings sat 16px further in
 * than the Servers list next to it in the nav column.</p>
 *
 * <p>The dialog is not a page and is not checked: it opens in its own window
 * with no content area around it, so its insets are its own business.</p>
 */
public class PageGutterTest extends ApplicationTest {

    private static final List<String> PAGES = List.of("DashboardView", "ServersView",
            "SubscriptionsView", "RoutingView", "LogsView", "SettingsView");

    @BeforeAll
    static void setupHeadless() {
        System.setProperty("testfx.robot", "glass");
        System.setProperty("testfx.headless", "true");
        System.setProperty("prism.order", "sw");
        System.setProperty("prism.text", "t2k");
        System.setProperty("java.awt.headless", "true");
        UiTestServices.initialize();
    }

    @Test
    void noPageAddsAGutterOfItsOwn() {
        List<String> offenders = new ArrayList<>();
        for (String page : PAGES) {
            for (Region node : load(page)) {
                // The insets a node ends up with, whether they came from a
                // padding block in the FXML or from a stylesheet rule: asking
                // the laid-out node covers both, where reading the FXML would
                // catch only one.
                Insets insets = node.getInsets();
                if (insets.getLeft() != 0 || insets.getRight() != 0) {
                    offenders.add("%s (%s): left %.1f, right %.1f".formatted(
                            page, node.getClass().getSimpleName(),
                            insets.getLeft(), insets.getRight()));
                }
            }
        }

        assertThat(offenders)
                .withFailMessage("pages that inset themselves on top of the content area's 24, "
                        + "so their content sits further in than every other page's:%n  %s",
                        String.join("\n  ", offenders))
                .isEmpty();
    }

    /**
     * The page root, and the content inside it when the root is a scroll pane
     * — Settings is one, and its gutter was on the box inside, where looking
     * at the root alone would never have found it.
     */
    private List<Region> load(String page) {
        final List<Region> holder = new ArrayList<>();
        interact(() -> {
            try {
                Parent root = new FXMLLoader(getClass().getResource("/fxml/" + page + ".fxml"))
                        .load();
                // Never shown: a scene only needs a root and its stylesheets to
                // apply CSS, and a stage ties the test to the runner's screen.
                Scene scene = new Scene(new Group(), 888, 740);
                scene.getStylesheets().setAll(
                        getClass().getResource("/css/light.css").toExternalForm());
                scene.setRoot(root);
                root.applyCss();
                root.layout();
                holder.add((Region) root);
                if (root instanceof ScrollPane scroll
                        && scroll.getContent() instanceof Region content) {
                    holder.add(content);
                }
            } catch (Exception e) {
                throw new IllegalStateException("could not load " + page, e);
            }
        });
        return holder;
    }
}
