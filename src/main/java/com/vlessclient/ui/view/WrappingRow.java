package com.vlessclient.ui.view;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;

/**
 * A row of controls that goes on to a further line rather than past the page.
 *
 * <p>While its children fit on one line it lays them out as an {@link HBox}
 * with {@code alignment="CENTER_LEFT"} does: each at its preferred width,
 * centred on the line, and a child with {@code HBox.hgrow} taking the room the
 * line leaves. The next child that does not fit starts a new line, so the row
 * never needs more width than its widest child. A child with
 * {@code HBox.hgrow} counts at its minimum width when the lines are drawn up,
 * the width an HBox would shrink it to, and a child wider than the row is
 * given the row's width.</p>
 *
 * <p>Every page is mounted in {@link ContentScrollPane}, which has no
 * horizontal scroll bar, so a row that was wider than the page was cut off
 * with nothing to scroll to. At the OS's larger text sizes, which TextScale
 * applies, the Logs toolbar lost its Clear button that way, the Servers
 * header ellipsized Backup and Import to "..." and "И...", and the Settings
 * row of the device ID, which keeps its label and its Reset button whole,
 * made the whole page wider than the window and hid every combo's arrow.</p>
 */
public class WrappingRow extends Pane {

    /**
     * How far a line may run past the row's width and still be one line: far
     * below any device pixel, and far above what adding up a few widths
     * snapped to a fractional scale gets wrong.
     */
    private static final double FIT_TOLERANCE = 1e-6;

    private double hgap;
    private double vgap;

    /** An empty row, for FXML. */
    public WrappingRow() {
        // Children come from the FXML.
    }

    /**
     * The space between two children on a line.
     *
     * @return the gap, in pixels
     */
    public final double getHgap() {
        return hgap;
    }

    /**
     * Sets the space between two children on a line.
     *
     * @param hgap the gap, in pixels
     */
    public final void setHgap(double hgap) {
        this.hgap = hgap;
        requestLayout();
    }

    /**
     * The space between two lines.
     *
     * @return the gap, in pixels
     */
    public final double getVgap() {
        return vgap;
    }

    /**
     * Sets the space between two lines.
     *
     * @param vgap the gap, in pixels
     */
    public final void setVgap(double vgap) {
        this.vgap = vgap;
        requestLayout();
    }

    /** How tall the row is depends on how many lines its width makes. */
    @Override
    public Orientation getContentBias() {
        return Orientation.HORIZONTAL;
    }

    @Override
    protected double computeMinWidth(double height) {
        double widest = 0;
        for (Node child : getManagedChildren()) {
            widest = Math.max(widest, child.minWidth(-1));
        }
        return snappedLeftInset() + snapSizeX(widest) + snappedRightInset();
    }

    @Override
    protected double computePrefWidth(double height) {
        List<Node> children = getManagedChildren();
        double sum = 0;
        for (Node child : children) {
            sum += snapSizeX(preferredWidth(child));
        }
        return snappedLeftInset() + sum + snapSpaceX(hgap) * Math.max(0, children.size() - 1)
                + snappedRightInset();
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected double computePrefHeight(double width) {
        double outer = width < 0 ? computePrefWidth(-1) : width;
        List<Line> lines = lines(outer - snappedLeftInset() - snappedRightInset());
        double height = 0;
        for (Line line : lines) {
            height += line.height;
        }
        return snappedTopInset() + height + snapSpaceY(vgap) * Math.max(0, lines.size() - 1)
                + snappedBottomInset();
    }

    @Override
    protected void layoutChildren() {
        double gap = snapSpaceX(hgap);
        double y = snappedTopInset();
        for (Line line : lines(getWidth() - snappedLeftInset() - snappedRightInset())) {
            double x = snappedLeftInset();
            for (int i = 0; i < line.children.size(); i++) {
                double width = line.widths.get(i);
                layoutInArea(line.children.get(i), x, y, width, line.height, 0, Insets.EMPTY,
                        true, true, HPos.LEFT, VPos.CENTER);
                x += width + gap;
            }
            y += line.height + snapSpaceY(vgap);
        }
    }

    /** The children, broken into the lines {@code width} makes of them. */
    private List<Line> lines(double width) {
        double gap = snapSpaceX(hgap);
        List<Line> lines = new ArrayList<>();
        Line line = new Line();
        for (Node child : getManagedChildren()) {
            double basis = Math.min(snapSizeX(basis(child)), Math.max(0, width));
            // Not an exact comparison. At Windows' 125% or 150% display
            // scaling a snapped width is a multiple of 0.8 or 0.666..., which
            // a double cannot hold, and the sum here could come out an ulp
            // above the same widths added up for the preferred width. A row
            // inside a row, given exactly that width, then put its last child
            // on a line of its own: the Logs toolbar's Clear at 125%.
            if (!line.children.isEmpty() && line.used + gap + basis > width + FIT_TOLERANCE) {
                line.finish(width);
                lines.add(line);
                line = new Line();
            }
            line.add(child, basis, gap);
        }
        if (!line.children.isEmpty()) {
            line.finish(width);
            lines.add(line);
        }
        return lines;
    }

    /** What a child takes up on a line: its minimum if it grows, else its preferred width. */
    private static double basis(Node child) {
        return grows(child) ? child.minWidth(-1) : preferredWidth(child);
    }

    private static double preferredWidth(Node child) {
        return Math.max(child.minWidth(-1), Math.min(child.prefWidth(-1), child.maxWidth(-1)));
    }

    private static boolean grows(Node child) {
        Priority grow = HBox.getHgrow(child);
        return grow == Priority.ALWAYS || grow == Priority.SOMETIMES;
    }

    /** One line of the row: its children, the width each gets, and its height. */
    private final class Line {

        private final List<Node> children = new ArrayList<>();
        private final List<Double> widths = new ArrayList<>();
        private double used;
        private double height;

        void add(Node child, double width, double gap) {
            used += (children.isEmpty() ? 0 : gap) + width;
            children.add(child);
            widths.add(width);
        }

        /**
         * Shares what the line leaves among the children that grow, evenly
         * and each up to its maximum width, as an HBox shares it, and takes
         * the height of the tallest child at the width it ends up with.
         */
        void finish(double width) {
            double free = width - used;
            List<Integer> growing = new ArrayList<>();
            for (int i = 0; i < children.size(); i++) {
                if (grows(children.get(i))) {
                    growing.add(i);
                }
            }
            // Unsnapped: layoutInArea snaps each size and position, and a
            // share rounded up here could take a line a pixel past its width.
            while (free > 0.5 && !growing.isEmpty()) {
                double share = free / growing.size();
                for (Iterator<Integer> it = growing.iterator(); it.hasNext(); ) {
                    int i = it.next();
                    double room = children.get(i).maxWidth(-1) - widths.get(i);
                    double take = Math.max(0, Math.min(room, share));
                    widths.set(i, widths.get(i) + take);
                    free -= take;
                    if (take < share) {
                        it.remove();
                    }
                }
            }
            for (int i = 0; i < children.size(); i++) {
                Node child = children.get(i);
                double at = widths.get(i);
                double tall = Math.max(child.minHeight(at),
                        Math.min(child.prefHeight(at), child.maxHeight(at)));
                height = Math.max(height, snapSizeY(tall));
            }
        }
    }
}
