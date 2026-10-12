package com.vlessclient.ui.view;

import com.vlessclient.app.ThemeCss;
import com.vlessclient.testing.SettledLayout;
import com.vlessclient.testing.UiTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@link FittedTextArea} is as tall as its skin needs to show the text,
 * whatever text the JVM laid out before it.
 *
 * <p>The box measured its text with a {@code Text} of the default bounds type,
 * {@code LOGICAL}, while TextArea's skin lays its paragraphs out as
 * {@code LOGICAL_VERTICAL_CENTER}, which rounds each line's ascent and descent
 * to whole pixels. In the 13px system font a line is 15.73px one way and 16px
 * the other, so four lines of the MCP command came out 62.9px tall where the
 * skin needed 64, the box was a pixel short, and the skin drew a vertical
 * scroll bar beside the command.</p>
 *
 * <p>It did not happen every time, which is why SettingsMcpCommandTest failed
 * in full runs and passed alone. JavaFX keeps one cache of shaped text for the
 * whole JVM, keyed by the string and the font, and a {@code Text} that finds
 * its string there takes the line metrics whoever shaped it first used. So the
 * box was right whenever the skin happened to shape the command first, and a
 * pixel short whenever the box did and the cache, which empties itself each
 * time 64K characters of other text have gone through it, had been emptied
 * before the skin got there. This test makes that order happen on purpose.</p>
 */
@UiTest
public class FittedTextAreaTest extends ApplicationTest {

    /** The width Settings gives the MCP command box at 820x500, where it was a pixel short. */
    private static final double WIDTH = 506;

    /**
     * More than JavaFX's text layout cache holds ({@code prism.cacheLayoutSize},
     * 64K characters), in strings short enough to be cached at all (256 or
     * fewer), so laying them out empties the cache at least once.
     */
    private static final int FILLER_STRINGS = 600;
    private static final int FILLER_LENGTH = 250;

    @Test
    void theBoxShowsTheWholeTextWhenItMeasuredBeforeTheSkinDid() {
        // A command no Text in this JVM has laid out, so nobody's earlier
        // metrics are waiting in the cache for it.
        String command = "claude mcp add --transport http vless-client "
                + "http://127.0.0.1:61234/mcp --header \"Authorization: Bearer "
                + UUID.randomUUID() + UUID.randomUUID() + "\"";
        FittedTextArea[] built = new FittedTextArea[1];
        Scene[] holder = new Scene[1];
        interact(() -> {
            // Built on the FX thread: a control made on the test thread while
            // the toolkit is still starting can deadlock in JavaFX's CSS class
            // initialisers, and once did, on the first test of a fresh JVM.
            FittedTextArea area = new FittedTextArea();
            area.setWrapText(true);
            area.setEditable(false);
            area.getStyleClass().add("form-field");
            area.setText(command);
            VBox column = new VBox(area);
            Scene scene = new Scene(new Group(), WIDTH, 300);
            built[0] = area;
            holder[0] = scene;
            scene.getStylesheets().setAll(ThemeCss.light());
            scene.setRoot(column);
            column.resize(WIDTH, 300);
            // Twice: the first pass creates the skin, the second styles its
            // nodes, so the box measures in the font it will be shown in.
            column.applyCss();
            column.applyCss();
            // The box measures the command first, as it does when its column
            // asks its height before the skin has laid anything out.
            area.prefHeight(WIDTH);
            layOutOtherText();
        });

        SettledLayout.settle(holder[0]);

        List<String> wrong = new ArrayList<>();
        interact(() -> collectWhereTheTextDoesNotFit(built[0], wrong));
        assertThat(wrong)
                .withFailMessage("the box has to show the whole %d-character text with no "
                        + "scroll bar of its own:%n  %s", command.length(),
                        String.join("\n  ", wrong))
                .isEmpty();
    }

    /** What the rest of a test run does to the text layout cache: fills it and empties it. */
    private static void layOutOtherText() {
        for (int i = 0; i < FILLER_STRINGS; i++) {
            String filler = (i + " " + UUID.randomUUID() + " ").repeat(8)
                    .substring(0, FILLER_LENGTH);
            new Text(filler).getLayoutBounds();
        }
    }

    private static void collectWhereTheTextDoesNotFit(FittedTextArea area, List<String> wrong) {
        ScrollPane scroller = area.getChildrenUnmodifiable().stream()
                .filter(ScrollPane.class::isInstance).map(ScrollPane.class::cast)
                .findFirst().orElseThrow();
        Region content = (Region) scroller.getContent();
        Text text = (Text) content.lookup(".text");
        String measured = ("the box is %.1f tall and fits itself to %.1f; the skin's text is "
                + "%.3f tall in a %.1f viewport").formatted(area.getHeight(),
                area.prefHeight(area.getWidth()), text.getLayoutBounds().getHeight(),
                scroller.getViewportBounds().getHeight());
        for (Node child : scroller.getChildrenUnmodifiable()) {
            if (child instanceof ScrollBar bar && bar.getOrientation() == Orientation.VERTICAL
                    && isShowing(bar)) {
                wrong.add("a vertical scroll bar shows: " + measured);
            }
        }
        double needed = text.getLayoutBounds().getHeight()
                + content.snappedTopInset() + content.snappedBottomInset();
        if (needed > scroller.getViewportBounds().getHeight() + 0.5) {
            wrong.add("the text needs %.3f with its padding: %s".formatted(needed, measured));
        }
    }

    /** Visible all the way up and laid out with a size: a skin keeps the bar it hides. */
    private static boolean isShowing(ScrollBar bar) {
        for (Node node = bar; node != null; node = node.getParent()) {
            if (!node.isVisible()) {
                return false;
            }
        }
        Bounds box = bar.getBoundsInParent();
        return box.getWidth() > 0 && box.getHeight() > 0;
    }
}
