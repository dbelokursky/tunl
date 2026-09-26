package com.vlessclient.app;

import java.awt.Desktop;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Links in Tunl's own scheme, {@code tunl://}, however they reach the app.
 *
 * <p>A provider's page offers an "Add to Tunl" button as
 * {@code tunl://install-config?url=<subscription>}, the form other clients'
 * one-tap links take. The link arrives three ways:</p>
 * <ul>
 *   <li>as the argument Windows and Linux start the app with;</li>
 *   <li>from a second launch, when the app already runs
 *       ({@link SingleInstance});</li>
 *   <li>on macOS as an Apple event, which AWT receives because the launcher
 *       starts AWT before JavaFX, and keeps until a handler is set.</li>
 * </ul>
 *
 * <p>Links that come before the window exists wait for it. The window opens
 * them in the Subscriptions page's form: nothing is added unless the user
 * adds it, since any page can open a link.</p>
 */
public final class DeepLinks {

    private static final Logger log = LoggerFactory.getLogger(DeepLinks.class);

    /** Tunl's URL scheme. */
    public static final String SCHEME = "tunl";

    /** A link longer than this is not one a page would send. */
    static final int MAX_LENGTH = 8000;

    private static final List<String> WAITING = new ArrayList<>();
    private static Consumer<String> opener;

    private DeepLinks() {
    }

    /**
     * Whether {@code text} is a link in Tunl's scheme, of a length a page
     * would send and without control characters.
     *
     * @param text the text
     * @return true for a {@code tunl:} link
     */
    public static boolean isLink(String text) {
        return text != null && text.length() <= MAX_LENGTH
                && text.regionMatches(true, 0, SCHEME + ":", 0, SCHEME.length() + 1)
                && text.chars().noneMatch(Character::isISOControl);
    }

    /**
     * The first argument in Tunl's scheme: the link Windows or Linux started
     * the app with.
     *
     * @param args the arguments the app was started with
     * @return the link, or empty when there is none
     */
    static Optional<String> fromArgs(String[] args) {
        if (args == null) {
            return Optional.empty();
        }
        for (String arg : args) {
            if (isLink(arg)) {
                return Optional.of(arg);
            }
        }
        return Optional.empty();
    }

    /**
     * Hands a link to the window, or keeps it until there is one. Any thread.
     *
     * @param link the link
     */
    static void receive(String link) {
        if (!isLink(link)) {
            log.debug("Ignoring a link outside Tunl's scheme");
            return;
        }
        Consumer<String> target;
        synchronized (DeepLinks.class) {
            if (opener == null) {
                WAITING.add(link);
                return;
            }
            target = opener;
        }
        target.accept(link);
    }

    /**
     * Opens every link from now on with {@code newOpener}, and those that
     * came before it.
     *
     * @param newOpener what opens a link; called on the thread the link came on
     */
    static void openWith(Consumer<String> newOpener) {
        List<String> earlier;
        synchronized (DeepLinks.class) {
            opener = newOpener;
            earlier = List.copyOf(WAITING);
            WAITING.clear();
        }
        earlier.forEach(newOpener);
    }

    /** Test seam: forgets the opener and the links waiting for one. */
    static synchronized void reset() {
        opener = null;
        WAITING.clear();
    }

    /**
     * Takes the links macOS sends as Apple events: one clicked while the app
     * runs, and the one that started it. A no-op where AWT has no such
     * events, as on Windows and Linux, which pass the link as an argument.
     */
    static void listenForAppleEvents() {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_URI)) {
                Desktop.getDesktop().setOpenURIHandler(
                        event -> receive(event.getURI().toString()));
            }
        } catch (RuntimeException | LinkageError e) {
            log.debug("No Apple events for links here: {}", e.getMessage());
        }
    }
}
