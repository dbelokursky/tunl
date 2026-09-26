package com.vlessclient.ui.view;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.TraversalDirection;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/**
 * Keyboard routes to what only a mouse reached: a list row's context menu,
 * and the way out of a text area.
 */
final class KeyboardReach {

    private KeyboardReach() {
    }

    /**
     * Opens the context menu of the list's focused row from the keyboard.
     *
     * <p>A row's menu is set on its cell, and a menu the keyboard asks for is
     * asked of the focus owner, which is the list. The list has no menu of
     * its own, so "Edit", "Duplicate" and "Copy link" were reachable only
     * with a right-click. The request that the menu key and Shift+F10 raise
     * on Windows and Linux is answered here with the focused row's menu. Both
     * keys are read too, since macOS raises no request for either.</p>
     *
     * @param list a list whose cells carry their row's context menu
     */
    static void openRowMenus(ListView<?> list) {
        list.addEventHandler(KeyEvent.KEY_PRESSED, key -> {
            boolean shiftF10 = key.getCode() == KeyCode.F10 && key.isShiftDown()
                    && !key.isShortcutDown() && !key.isAltDown();
            if ((key.getCode() == KeyCode.CONTEXT_MENU || shiftF10) && showFocusedRowMenu(list)) {
                key.consume();
            }
        });
        list.addEventHandler(ContextMenuEvent.CONTEXT_MENU_REQUESTED, request -> {
            if (request.isKeyboardTrigger() && showFocusedRowMenu(list)) {
                request.consume();
            }
        });
    }

    /**
     * Lets Tab and Shift+Tab leave a text area, as they leave every other
     * field.
     *
     * <p>A TextArea takes Tab for a tab character, and a read-only one
     * swallows it, so only Ctrl+Tab moved on, which nobody tries: a keyboard
     * user who tabbed into the bypass list or the MCP command stayed there.
     * None of the app's text areas wants a tab: the bypass list is a domain
     * a line, and the others take pasted text or cannot be edited.</p>
     *
     * @param area the text area
     */
    static void tabLeaves(TextArea area) {
        area.addEventFilter(KeyEvent.KEY_PRESSED, key -> {
            if (key.getCode() == KeyCode.TAB && !key.isControlDown() && !key.isAltDown()
                    && !key.isMetaDown()) {
                key.consume();
                area.requestFocusTraversal(key.isShiftDown()
                        ? TraversalDirection.PREVIOUS : TraversalDirection.NEXT);
            }
        });
    }

    /**
     * Shows the focused row's menu where the keyboard's own request would put
     * it, a quarter of the way in and half way down the row. A row scrolled
     * out of sight is scrolled to first: it has no cell until then.
     */
    private static boolean showFocusedRowMenu(ListView<?> list) {
        int focused = list.getFocusModel().getFocusedIndex();
        if (focused < 0) {
            return false;
        }
        ListCell<?> cell = cellShowing(list, focused);
        if (cell == null) {
            list.scrollTo(focused);
            list.layout();
            cell = cellShowing(list, focused);
        }
        ContextMenu menu = cell == null ? null : cell.getContextMenu();
        if (menu == null) {
            return false;
        }
        if (!menu.isShowing()) {
            Bounds row = cell.localToScreen(cell.getBoundsInLocal());
            menu.show(cell, row.getMinX() + row.getWidth() / 4,
                    row.getMinY() + row.getHeight() / 2);
        }
        return true;
    }

    /** The visible cell showing row {@code index}, not the list's hidden measuring cell. */
    private static ListCell<?> cellShowing(ListView<?> list, int index) {
        for (Node node : list.lookupAll(".list-cell")) {
            if (node instanceof ListCell<?> cell && cell.getIndex() == index && !cell.isEmpty()
                    && cell.isVisible() && cell.getParent() != null
                    && cell.getParent().isVisible()) {
                return cell;
            }
        }
        return null;
    }
}
