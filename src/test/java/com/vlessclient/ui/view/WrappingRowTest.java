package com.vlessclient.ui.view;

import com.vlessclient.testing.FxToolkitExtension;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WrappingRow} lays its children out as a CENTER_LEFT HBox does while
 * they fit, and on further lines where they do not.
 */
@ExtendWith(FxToolkitExtension.class)
class WrappingRowTest {

    @Test
    void childrenThatFitShareOneLineAtTheirPreferredWidths() {
        onFx(() -> {
            Region a = box(10, 100, 100, 20);
            Region b = box(10, 50, 50, 40);
            WrappingRow row = row(a, b);
            row.resize(400, row.prefHeight(400));
            row.layout();

            assertThat(bounds(a)).isEqualTo(new double[] {0, 10, 100, 20});
            assertThat(bounds(b)).isEqualTo(new double[] {110, 0, 50, 40});
            assertThat(row.prefWidth(-1)).isEqualTo(160);
            assertThat(row.prefHeight(400)).isEqualTo(40);
            return null;
        });
    }

    @Test
    void theChildThatDoesNotFitStartsANewLine() {
        onFx(() -> {
            Region a = box(10, 100, 100, 20);
            Region b = box(10, 80, 80, 30);
            Region c = box(10, 60, 60, 20);
            WrappingRow row = row(a, b, c);
            row.resize(200, row.prefHeight(200));
            row.layout();

            assertThat(bounds(a)).isEqualTo(new double[] {0, 5, 100, 20});
            assertThat(bounds(b)).isEqualTo(new double[] {110, 0, 80, 30});
            assertThat(bounds(c)).isEqualTo(new double[] {0, 36, 60, 20});
            assertThat(row.prefHeight(200)).as("two lines and the gap").isEqualTo(56);
            assertThat(row.minHeight(200)).isEqualTo(56);
            return null;
        });
    }

    @Test
    void aGrowingChildCountsAtItsMinimumAndTakesWhatTheLineLeaves() {
        onFx(() -> {
            Region fixed = box(10, 100, 100, 20);
            Region field = box(40, 300, Double.MAX_VALUE, 20);
            HBox.setHgrow(field, Priority.ALWAYS);
            Region tail = box(10, 50, 50, 20);
            WrappingRow row = row(fixed, field, tail);
            row.resize(250, row.prefHeight(250));
            row.layout();

            assertThat(bounds(fixed)[0]).isEqualTo(0);
            assertThat(bounds(field)).as("grown into the room left")
                    .isEqualTo(new double[] {110, 0, 80, 20});
            assertThat(bounds(tail)[0]).isEqualTo(200);
            return null;
        });
    }

    @Test
    void aChildWiderThanTheRowGetsTheRowsWidthOnALineOfItsOwn() {
        onFx(() -> {
            Region small = box(10, 50, 50, 20);
            Region wide = box(10, 500, 500, 20);
            WrappingRow row = row(small, wide);
            row.resize(200, row.prefHeight(200));
            row.layout();

            assertThat(bounds(wide)).isEqualTo(new double[] {0, 26, 200, 20});
            assertThat(row.minWidth(-1)).as("the widest minimum").isEqualTo(10);
            return null;
        });
    }

    /**
     * The device ID's row: a label, then the ID and its Reset button as one
     * group that grows. The group goes under the label whole where the two
     * do not fit on one line, and the button keeps its width on any line
     * that holds it. Counted at its minimum, its widest child, the group
     * stayed beside the label in what room it left, and the button was cut
     * to that room on a line of the group's own.
     */
    @Test
    void aGrowingRowInsideARowGoesOnToTheNextLineAsOne() {
        onFx(() -> {
            Region label = box(10, 100, 100, 20);
            Region id = box(0, 300, 300, 20);
            HBox.setHgrow(id, Priority.ALWAYS);
            Region reset = box(20, 80, 80, 30);
            WrappingRow group = row(id, reset);
            HBox.setHgrow(group, Priority.ALWAYS);
            WrappingRow row = row(label, group);
            row.resize(180, row.prefHeight(180));
            row.layout();

            assertThat(bounds(group)).as("under the label, the whole row wide")
                    .isEqualTo(new double[] {0, 26, 180, 30});
            assertThat(bounds(id)).isEqualTo(new double[] {0, 5, 90, 20});
            assertThat(bounds(reset)).as("its own width").isEqualTo(new double[] {100, 0, 80, 30});
            assertThat(row.minWidth(-1)).as("the widest minimum, the button's own")
                    .isEqualTo(20);
            return null;
        });
    }

    /**
     * Windows' display scalings of 125%, 150%, 175%, 225%, 300% and 350%
     * snap a width to a multiple of 0.8, 0.666..., 0.571..., 0.444...,
     * 0.333... or 0.285..., which a double cannot hold exactly. A row inside a
     * row is given exactly its preferred width, and its children's widths
     * added up again came to an ulp more than that width, so it put its last
     * child on a second line with room to spare: the Logs toolbar's Clear at
     * 125%, and the Servers header's Add at 150% in Russian, at the usual
     * text size. A scene with no window always snaps at 1.0, so each scale
     * gets a stage of its own, never shown.
     */
    @Test
    void aRowInsideARowStaysOnOneLineAtEveryDisplayScale() {
        List<String> wrapped = onFx(() -> {
            List<String> found = new ArrayList<>();
            Random random = new Random(125);
            for (double scale : new double[] {1.25, 1.5, 1.75, 2.25, 3.0, 3.5}) {
                Stage stage = new Stage();
                stage.setRenderScaleX(scale);
                stage.setRenderScaleY(scale);
                // The Logs toolbar's check box and icons at 125%.
                nestedRowOnOneLine(stage, new double[] {91.2, 34.4, 34.4, 34.4}, found);
                for (int i = 0; i < 40; i++) {
                    double[] widths = new double[2 + random.nextInt(4)];
                    for (int j = 0; j < widths.length; j++) {
                        widths[j] = 20 + random.nextInt(1500) / 10.0;
                    }
                    nestedRowOnOneLine(stage, widths, found);
                }
            }
            return found;
        });

        assertThat(wrapped).as("rows inside a row that left one line with room to spare")
                .isEmpty();
    }

    /**
     * Lays out, at {@code stage}'s scale, a row holding a box, a field that
     * grows and a row of {@code widths}, the way the Logs toolbar holds its
     * combo, its search field and its buttons, with room for all of it.
     */
    private static void nestedRowOnOneLine(Stage stage, double[] widths, List<String> wrapped) {
        Region[] boxes = new Region[widths.length];
        for (int i = 0; i < widths.length; i++) {
            boxes[i] = box(widths[i], widths[i], widths[i], 30);
        }
        WrappingRow inner = row(boxes);
        Region field = box(40, 150, Double.MAX_VALUE, 30);
        HBox.setHgrow(field, Priority.ALWAYS);
        WrappingRow outer = row(box(99.2, 99.2, 99.2, 30), field, inner);
        stage.setScene(new Scene(outer, 1400, 200));
        outer.applyCss();
        outer.layout();
        for (Region box : boxes) {
            if (box.getLayoutY() != boxes[0].getLayoutY() || inner.getLayoutY() != 0) {
                wrapped.add("%s at %.2f: a row %.17g wide holding %.17g".formatted(
                        Arrays.toString(widths), stage.getRenderScaleX(),
                        inner.getWidth(), inner.prefWidth(-1)));
                return;
            }
        }
    }

    @Test
    void itsPaddingInsetsEveryLine() {
        onFx(() -> {
            Region a = box(10, 100, 100, 20);
            Region b = box(10, 100, 100, 20);
            WrappingRow row = row(a, b);
            row.setPadding(new Insets(4, 8, 4, 8));
            row.resize(150, row.prefHeight(150));
            row.layout();

            assertThat(bounds(a)).isEqualTo(new double[] {8, 4, 100, 20});
            assertThat(bounds(b)).isEqualTo(new double[] {8, 30, 100, 20});
            assertThat(row.prefHeight(150)).isEqualTo(4 + 20 + 6 + 20 + 4);
            assertThat(row.minWidth(-1)).isEqualTo(8 + 10 + 8);
            return null;
        });
    }

    private static WrappingRow row(Region... children) {
        WrappingRow row = new WrappingRow();
        row.setHgap(10);
        row.setVgap(6);
        row.getChildren().setAll(children);
        return row;
    }

    private static Region box(double min, double pref, double max, double height) {
        Region box = new Region();
        box.setMinSize(min, height);
        box.setPrefSize(pref, height);
        box.setMaxSize(max, height);
        return box;
    }

    /** x, y, width and height within the row. */
    private static double[] bounds(Region child) {
        Bounds b = child.getBoundsInParent();
        return new double[] {b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight()};
    }

    private static <T> T onFx(Supplier<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        try {
            return result.get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof AssertionError assertion) {
                throw assertion;
            }
            throw new IllegalStateException(e.getCause());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
