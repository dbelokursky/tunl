package com.vlessclient.ui.view;

import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import javafx.scene.text.TextBoundsType;

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

    /**
     * Wraps a copy of the text the way the skin's own Text node wraps it.
     *
     * <p>Including its bounds type. The skin's paragraphs are styled by
     * modena's {@code .text} rule, {@code LOGICAL_VERTICAL_CENTER}, which
     * rounds each line's ascent and descent to whole pixels; this node has no
     * style class and kept the default, {@code LOGICAL}. A 13px line came out
     * 15.73px here and 16px in the skin, so the MCP command's four lines
     * measured 62.9 where the skin needed 64, and the box was a pixel short of
     * its own scroll bar. It was right only when it happened to reuse the
     * skin's shaping from JavaFX's JVM-wide text cache, which is why a test of
     * it passed alone and failed in full runs. Set before the first
     * measurement: a node that has shaped its text keeps those line metrics
     * until its bounds type or font changes.</p>
     */
    private final Text measure = new Text();

    /** Starts the measuring node in the bounds type the skin's text is styled with. */
    public FittedTextArea() {
        measure.setBoundsType(TextBoundsType.LOGICAL_VERTICAL_CENTER);
    }

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
        // Whatever a stylesheet makes of the skin's text, measured the same way.
        if (content.lookup(".text") instanceof Text shown) {
            measure.setBoundsType(shown.getBoundsType());
        }
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
