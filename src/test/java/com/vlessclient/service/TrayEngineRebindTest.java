package com.vlessclient.service;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tray after the in-app sing-box install swaps the engine.
 *
 * <p>The icon and the connect/disconnect label are driven by the engine's
 * connection state. The engine object is replaced when the core is installed
 * from inside the app, and the tray used to hold the original: from that moment
 * until the next launch its icon stopped following the tunnel, with nothing
 * logged and nothing to notice.</p>
 *
 * <p>Two halves, and both are needed. Resolving the engine through a supplier
 * fixes the reads. It does not fix the listener, which is bound to one property
 * instance and has to be moved across.</p>
 */
class TrayEngineRebindTest {

    private static SingBoxEngine engine(String name) {
        return new SingBoxEngine(Path.of("target", name));
    }

    /** Headless: the constructor only assigns fields, install() is never called. */
    private static TrayIconService tray(AtomicReference<SingBoxEngine> current) {
        return new TrayIconService(current::get, null, null, null, null);
    }

    @Test
    @DisplayName("the listener moves to the engine the supplier now returns")
    void rebindFollowsTheNewEngine() {
        SingBoxEngine first = engine("first");
        SingBoxEngine second = engine("second");
        AtomicReference<SingBoxEngine> current = new AtomicReference<>(first);
        TrayIconService tray = tray(current);

        tray.rebindEngineListener();
        assertThat(tray.listeningTo()).isSameAs(first);

        current.set(second);
        tray.rebindEngineListener();

        assertThat(tray.listeningTo())
                .as("a property listener stays on the instance it was added to, "
                        + "so re-resolving the engine is not enough by itself")
                .isSameAs(second);
    }

    @Test
    @DisplayName("rebinding to the same engine does not stack listeners")
    void rebindingTwiceIsIdempotent() {
        SingBoxEngine only = engine("only");
        TrayIconService tray = tray(new AtomicReference<>(only));

        tray.rebindEngineListener();
        SingBoxEngine after = tray.listeningTo();
        tray.rebindEngineListener();

        assertThat(tray.listeningTo()).isSameAs(after).isSameAs(only);
    }

    @Test
    @DisplayName("a supplier with no engine yet is not an error")
    void noEngineYetIsTolerated() {
        AtomicReference<SingBoxEngine> current = new AtomicReference<>(null);
        TrayIconService tray = tray(current);

        tray.rebindEngineListener();
        assertThat(tray.listeningTo()).isNull();

        // The install case: the tray exists before any core does.
        SingBoxEngine installed = engine("installed");
        current.set(installed);
        tray.rebindEngineListener();

        assertThat(tray.listeningTo()).isSameAs(installed);
    }
}
