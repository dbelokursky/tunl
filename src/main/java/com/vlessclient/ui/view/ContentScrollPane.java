package com.vlessclient.ui.view;

import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;

/**
 * The scroll wrapper every content view is mounted in, so the whole page stays
 * reachable when the window is smaller than the content (otherwise buttons like
 * Re-check are clipped off the bottom and can't be clicked at all).
 *
 * <p>{@code fitToHeight} stretches the page to the viewport so views with a
 * {@code VBox.vgrow} child (the Logs list) can fill a tall window. On its own
 * though it stretches <em>both</em> ways: ScrollPane hands the page exactly the
 * viewport height even when the page needs more, and no scrollbar appears
 * because, as far as the skin is concerned, the content fits. The page then
 * passes the shortage down to its cards, and since a JavaFX {@code Region} does
 * not clip its children, a squashed card does not scroll or hide -- it paints
 * over the section below it.
 *
 * <p>Pinning the page's minimum height to its preferred height is what keeps
 * that one-directional: the page can still grow into a tall viewport, but it
 * can never be shrunk under its own content, so a short window scrolls.
 */
public final class ContentScrollPane extends ScrollPane {

    /**
     * Mounts a content view in the wrapper.
     *
     * @param view the page to scroll; sizing is only pinned if it is a Region
     */
    public ContentScrollPane(Node view) {
        super(view);
        setFitToWidth(true);
        setFitToHeight(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setVbarPolicy(ScrollBarPolicy.AS_NEEDED);
        getStyleClass().add("content-scroll");

        if (view instanceof Region page) {
            page.setMinHeight(Region.USE_PREF_SIZE);
        }
    }
}
