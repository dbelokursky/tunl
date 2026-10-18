package com.vlessclient.ui.view;

import com.vlessclient.testing.FxToolkitExtension;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
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
