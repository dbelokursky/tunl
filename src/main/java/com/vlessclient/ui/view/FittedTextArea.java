package com.vlessclient.ui.view;

import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;

/**
 * A wrapping {@link TextArea} as tall as its text at the width it is laid out
 * at, so it never needs a vertical scroll bar of its own.
 *
 * <p>A stock TextArea takes its height from {@code prefRowCount} and never from
 * its text, so a box sized in rows fits its text at the one width somebody
 * counted them for. It does not get those rows exactly either: {@code SkinBase}
 * leaves the control's own padding and border out of its preferred height, so
 * a padded field gives them up from the rows, and the skin re-reads its line
 * height when the stylesheet's font arrives but keeps the row height it already
 * derived from the old one. The MCP command in Settings runs to four lines at
 * the smallest window, and it had a box with room for two.</p>
 *
 * <p>How tall wrapped text is depends on the width it is laid out at, and a
 * horizontal content bias is how a layout pane asks that: a VBox asks a
 * wrapping Label for its height at the width it is about to give it. This
 * answers the same question, so the height is right in the layout pass that
 * sets the width. Setting a height from a width listener instead lands while
 * the parent is mid-layout, where the request to lay the parent out again is
 * dropped.</p>
 */
public class FittedTextArea extends TextArea {

    /** Wraps a copy of the text the way the skin's own Text node wraps it. */
    private final Text measure = new Text();

    @Override
    public Orientation getContentBias() {
        return isWrapText() ? Orientation.HORIZONTAL : super.getContentBias();
    }

    @Override
    protected double computePrefHeight(double width) {
        double fitted = fittedHeight(width);
        return fitted >= 0 ? fitted : super.computePrefHeight(width);
    }

    /** Not below its text either: squeezed, the box would scroll again. */
    @Override
    protected double computeMinHeight(double width) {
        double fitted = fittedHeight(width);
        return fitted >= 0 ? fitted : super.computeMinHeight(width);
    }

    /**
     * The height that shows every line of the text at {@code width}, or -1
     * when there is nothing to fit it to: the text does not wrap, or the skin
     * that lays the text out does not exist yet.
     */
    private double fittedHeight(double width) {
        ScrollPane scroller = skinScrollPane();
        if (!isWrapText() || scroller == null
                || !(scroller.getContent() instanceof Region content)) {
            return -1;
        }
        measure.setFont(getFont());
        measure.setText(getText());
        // Asked without a width, answer for the preferred width: that is the
        // width a layout pane passes a biased child when it has none itself.
        double laidOutAt = width < 0 ? prefWidth(-1) : width;
        // The skin wraps the text inside three sets of insets: this control's,
        // its scroll pane's and the content region's. With no scroll bar
        // showing, which is the point, nothing else takes any width.
        measure.setWrappingWidth(Math.max(0, laidOutAt
                - snappedLeftInset() - snappedRightInset()
                - scroller.snappedLeftInset() - scroller.snappedRightInset()
                - content.snappedLeftInset() - content.snappedRightInset()));
        return snapSizeY(measure.getLayoutBounds().getHeight()
                + snappedTopInset() + snappedBottomInset()
                + scroller.snappedTopInset() + scroller.snappedBottomInset()
                + content.snappedTopInset() + content.snappedBottomInset());
    }

    private ScrollPane skinScrollPane() {
        for (Node child : getChildrenUnmodifiable()) {
            if (child instanceof ScrollPane pane) {
                return pane;
            }
        }
        return null;
    }
}
