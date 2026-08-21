package com.vlessclient.service;

import com.vlessclient.model.TunnelHealth;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

/**
 * The application-wide, observable answer to "does this tunnel actually carry
 * traffic?".
 *
 * <p>The reachability loop that produces the answer lives in the Dashboard
 * ({@code HealthCheckCoordinator}), but the menu-bar icon needs it too, and
 * the tray has no access to view controllers. This holder is the seam: the
 * loop publishes here, anyone interested observes here. Registered in
 * {@link com.vlessclient.app.ServiceLocator} so both sides find the same
 * instance.</p>
 *
 * <p>Mirrors {@link SingBoxEngine}'s state property: a read-only property
 * outward, mutation funnelled onto the FX thread.</p>
 */
public class TunnelHealthState {

    private final ReadOnlyObjectWrapper<TunnelHealth> health =
            new ReadOnlyObjectWrapper<>(TunnelHealth.UNMONITORED);

    /** The current verdict; never null. */
    public TunnelHealth get() {
        return health.get();
    }

    /** The verdict as an observable property, for binding and listeners. */
    public ReadOnlyObjectProperty<TunnelHealth> healthProperty() {
        return health.getReadOnlyProperty();
    }

    /**
     * Publishes a new verdict. Safe to call from any thread: the write is
     * marshalled onto the FX thread, since the listeners on the other side
     * touch scene graph and AWT state. A {@code null} verdict is ignored —
     * callers say {@link TunnelHealth#UNMONITORED} to mean "nothing to say".
     */
    public void set(TunnelHealth next) {
        if (next == null) {
            return;
        }
        if (Platform.isFxApplicationThread()) {
            health.set(next);
            return;
        }
        try {
            Platform.runLater(() -> health.set(next));
        } catch (IllegalStateException toolkitNotRunning) {
            // No FX thread to protect (a plain unit test, or a headless tool):
            // the caller is then the only thread touching this property.
            health.set(next);
        }
    }
}
