package com.vlessclient.app;

import com.vlessclient.testing.Await;
import com.vlessclient.testing.FxToolkitExtension;
import com.vlessclient.testing.ThreadDump;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Rebuilding the UI test graph must leave no HTTP client of the old one open.
 *
 * <p>Each client keeps a selector thread, a virtual one on this JDK, until it
 * is closed or collected. The UI suite rebuilds the graph for every class, and
 * every rebuild left about eleven of them open: the services the doubles
 * replaced were never shut down. On Windows runners such a thread holds a
 * carrier while it waits for I/O, so after a hundred graphs the scheduler was
 * at its 256 carriers, every one taken, and six UI tests timed out together
 * waiting for work handed to a virtual thread that never ran.</p>
 */
@ExtendWith(FxToolkitExtension.class)
class UiTestServicesTest {

    private static final Pattern SELECTOR =
            Pattern.compile("\"HttpClient-\\d+-SelectorManager\"");

    /**
     * Every service of every graph, kept reachable as the UI keeps them (a
     * controller of an earlier test's window, a listener on a static
     * property): otherwise a collection ends their selector threads and hides
     * whether they were ever closed.
     */
    private final List<Object> stillReachable = new ArrayList<>();

    @AfterEach
    void releaseTheGraph() {
        UiTestServices.keepReplaced = null;
        ServiceLocator.shutdown();
        stillReachable.clear();
    }

    @Test
    void rebuildingTheGraphLeavesNoHttpClientOfTheOldOneOpen() {
        UiTestServices.keepReplaced = stillReachable::add;
        UiTestServices.initialize();
        long oneGraph = selectorThreads();

        for (int i = 0; i < 10; i++) {
            stillReachable.addAll(registered());
            UiTestServices.initialize();
        }

        // Closing a client ends its selector thread moments later, not at once.
        Await.until("the replaced graphs' HTTP clients to close",
                () -> selectorThreads() <= oneGraph + 2, Duration.ofSeconds(15));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> registered() {
        try {
            java.lang.reflect.Field services = ServiceLocator.class.getDeclaredField("services");
            services.setAccessible(true);
            return new ArrayList<>(((Map<Class<?>, Object>) services.get(null)).values());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long selectorThreads() {
        Matcher selectors = SELECTOR.matcher(ThreadDump.ofAllThreads());
        long count = 0;
        while (selectors.find()) {
            count++;
        }
        return count;
    }
}
