package com.vlessclient.service;

import com.vlessclient.app.I18n;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TunnelHealth;
import com.vlessclient.model.TunnelStatus;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Menu;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * macOS menu bar (system tray) integration using AWT SystemTray.
 *
 * <p>Provides a tray icon with status indication and quick actions:
 * show window, connect/disconnect, server selection, and quit.</p>
 *
 * <p>The icon reflects {@link TunnelStatus}, not the raw process state: a
 * core that started but whose traffic reaches nothing must not look the same
 * as a working tunnel. That means listening to two sources — the engine's
 * {@link ConnectionState} and the reachability verdict in
 * {@link TunnelHealthState} — and letting {@link TunnelStatus#of} combine
 * them.</p>
 *
 * <p>AWT and JavaFX run on separate event threads: AWT updates must be
 * wrapped in {@link EventQueue#invokeLater(Runnable)}, and any JavaFX
 * state touched from tray callbacks must be wrapped in
 * {@link Platform#runLater(Runnable)}.</p>
 */
public class TrayIconService {

    private static final Logger log = LoggerFactory.getLogger(TrayIconService.class);
    private static final int ICON_SIZE = 22;

    /**
     * How long {@link #uninstall()} waits for the AWT event thread. Long enough
     * that a merely busy EDT still gets to remove the icon, short enough that a
     * wedged one costs the user no visible pause on the way out.
     */
    private static final long UNINSTALL_TIMEOUT_MS = 1_000L;

    /**
     * Resolved on every use rather than captured once. The in-app sing-box
     * install replaces the engine object, and a captured one would leave the
     * icon and the connect label following an engine that never had a binary
     * for the rest of the run. Same shape {@code HealthCheckCoordinator} uses.
     */
    private final Supplier<SingBoxEngine> engineSupplier;

    /** The engine {@link #stateListener} is currently attached to, if any. */
    private SingBoxEngine listeningTo;

    /**
     * The server list as AWT sees it.
     *
     * <p>{@code configStore.getServers()} is a JavaFX-owned {@code
     * ObservableList}; copying it from the AWT event thread races every
     * FX-side mutation. Blocking AWT on the FX thread to copy it safely would
     * be worse — {@link #uninstall()} blocks the other way, waiting for AWT —
     * so the copy is taken on the FX thread, where the listener below already
     * runs, and AWT only ever reads this field.</p>
     */
    private volatile List<ServerConfig> serverSnapshot = List.of();
    private final ConfigStore configStore;
    private final ConnectionService connectionService;
    private final TunnelHealthState healthState;
    private final Stage stage;

    private TrayIcon trayIcon;
    private PopupMenu popupMenu;
    private MenuItem toggleConnectItem;
    private MenuItem statusItem;
    private Menu serversMenu;
    private ListChangeListener<ServerConfig> serversListener;
    private javafx.beans.value.ChangeListener<ConnectionState> stateListener;
    private javafx.beans.value.ChangeListener<TunnelHealth> healthListener;

    /**
     * Creates a tray icon service bound to the given engine, stores and stage.
     *
     * @param engineSupplier    supplies the engine whose state drives the icon
     * @param configStore       store providing the selectable server list
     * @param connectionService owner of the connect/disconnect flow
     * @param healthState       reachability verdict refining a running tunnel,
     *                          may be null (the icon then reports process
     *                          state alone)
     * @param stage             main window shown/hidden from the tray
     */
    public TrayIconService(Supplier<SingBoxEngine> engineSupplier,
                           ConfigStore configStore,
                           ConnectionService connectionService,
                           TunnelHealthState healthState,
                           Stage stage) {
        this.engineSupplier = engineSupplier;
        this.configStore = configStore;
        this.connectionService = connectionService;
        this.healthState = healthState;
        this.stage = stage;
    }

    /**
     * Creates the tray icon and installs it on the system tray.
     * Does nothing (logs a warning) if the system tray is not supported.
     */
    public void install() {
        if (!SystemTray.isSupported()) {
            log.warn("System tray is not supported on this platform; "
                    + "tray icon will not be installed");
            return;
        }

        // Ensure AWT toolkit is initialized before creating any AWT components.
        Toolkit.getDefaultToolkit();

        EventQueue.invokeLater(() -> {
            try {
                popupMenu = buildPopupMenu();
                Image icon = createStatusIcon(currentStatus());
                trayIcon = new TrayIcon(icon, "Tunl", popupMenu);
                trayIcon.setImageAutoSize(true);
                trayIcon.addActionListener(e -> showMainWindow());

                SystemTray.getSystemTray().add(trayIcon);
                log.info("Tray icon installed");

                refreshTrayState();
            } catch (AWTException e) {
                log.error("Failed to install tray icon", e);
                trayIcon = null;
                popupMenu = null;
            }
        });

        // Listen for state changes and forward to AWT thread.
        attachEngineListener();

        // Listen for reachability verdicts: a tunnel that stops carrying
        // traffic changes nothing about the process, so this is the only
        // signal that can take the icon out of green.
        if (healthState != null) {
            healthListener = (obs, oldVal, newVal) -> refreshTrayState();
            healthState.healthProperty().addListener(healthListener);
        }

        // Listen for server list changes.
        if (configStore != null) {
            // Fires on the FX thread, which is the only place the list may be
            // read; snapshot there and hand AWT the copy.
            serversListener = change -> {
                serverSnapshot = List.copyOf(configStore.getServers());
                refreshTrayState();
            };
            configStore.getServers().addListener(serversListener);
            serverSnapshot = FxExecutor.get(() -> List.copyOf(configStore.getServers()));
        }
    }


    /**
     * Removes the tray icon from the system tray and detaches listeners.
     *
     * <p>Waits for the AWT event thread to run the removal, so the icon really
     * is gone by the time this method returns. This matters for the Quit flow
     * in {@link com.vlessclient.app.VlessClientApp#stop()} — that method
     * immediately calls {@code System.exit}, and any pending-but-unexecuted
     * {@code invokeLater} callback would be dropped, leaving a stale tray
     * icon behind in the menu bar.</p>
     *
     * <p>The wait is bounded, and deliberately not {@code invokeAndWait}. On
     * macOS the caller is the FX application thread, which <em>is</em> the
     * process main thread; by the time {@code stop()} runs, JavaFX has already
     * torn down the AppKit run loop that thread was pumping. An EDT task that
     * needs the main thread — AWT reaches for it via
     * {@code performSelectorOnMainThread:waitUntilDone:YES}, for instance from
     * {@code CInputMethod.getNativeLocale} — can then never complete, and a
     * caller blocked in {@code invokeAndWait} never wakes: both threads wait on
     * each other and the app hangs with no way out short of SIGKILL. A missing
     * tray icon for the last instant of the process is a far cheaper outcome
     * than that, so the wait gives up and shutdown carries on.</p>
     */
    public void uninstall() {
        detachEngineListener();
        if (healthState != null && healthListener != null) {
            healthState.healthProperty().removeListener(healthListener);
            healthListener = null;
        }
        if (configStore != null && serversListener != null) {
            configStore.getServers().removeListener(serversListener);
            serversListener = null;
        }

        Runnable removeTask = () -> {
            if (trayIcon != null) {
                try {
                    SystemTray.getSystemTray().remove(trayIcon);
                    log.info("Tray icon uninstalled");
                } catch (Exception e) {
                    log.debug("Error removing tray icon", e);
                }
                trayIcon = null;
                popupMenu = null;
                toggleConnectItem = null;
                statusItem = null;
                serversMenu = null;
            }
        };

        if (EventQueue.isDispatchThread()) {
            removeTask.run();
            return;
        }

        CountDownLatch removed = new CountDownLatch(1);
        EventQueue.invokeLater(() -> {
            try {
                removeTask.run();
            } finally {
                removed.countDown();
            }
        });
        try {
            if (!removed.await(UNINSTALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                log.warn("AWT did not remove the tray icon within {} ms; "
                        + "continuing shutdown without it", UNINSTALL_TIMEOUT_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Interrupted while uninstalling tray icon");
        }
    }

    private PopupMenu buildPopupMenu() {
        PopupMenu menu = new PopupMenu();

        MenuItem showItem = new MenuItem(I18n.get("tray.show"));
        showItem.addActionListener(e -> showMainWindow());
        menu.add(showItem);

        menu.addSeparator();

        toggleConnectItem = new MenuItem(I18n.get("tray.connect"));
        toggleConnectItem.addActionListener(e -> onToggleConnect());
        menu.add(toggleConnectItem);

        statusItem = new MenuItem(statusLabel(currentStatus()));
        statusItem.setEnabled(false);
        menu.add(statusItem);

        menu.addSeparator();

        serversMenu = new Menu(I18n.get("tray.servers.select"));
        menu.add(serversMenu);

        menu.addSeparator();

        MenuItem quitItem = new MenuItem(I18n.get("tray.quit"));
        quitItem.addActionListener(e -> onQuit());
        menu.add(quitItem);

        return menu;
    }

    /**
     * Refreshes icon, connect/disconnect item label, status label and server submenu
     * based on the current SingBoxEngine state and configured server list. Safe to
     * call from any thread.
     */
    private void refreshTrayState() {
        EventQueue.invokeLater(() -> {
            if (trayIcon == null) {
                return;
            }
            ConnectionState state = currentState();
            TunnelStatus status = TunnelStatus.of(state, currentHealth());

            trayIcon.setImage(createStatusIcon(status));
            trayIcon.setToolTip("Tunl - " + statusLabel(status));

            if (statusItem != null) {
                statusItem.setLabel(statusLabel(status));
            }
            if (toggleConnectItem != null) {
                // Keyed off the process state, not the status: a tunnel that
                // carries no traffic is still one the user disconnects.
                boolean connected = state == ConnectionState.CONNECTED
                        || state == ConnectionState.CONNECTING;
                toggleConnectItem.setLabel(
                        connected ? I18n.get("tray.disconnect") : I18n.get("tray.connect"));
            }
            rebuildServersMenu();
        });
    }

    private void rebuildServersMenu() {
        if (serversMenu == null) {
            return;
        }
        serversMenu.removeAll();

        if (configStore == null) {
            MenuItem none = new MenuItem(I18n.get("tray.servers.none"));
            none.setEnabled(false);
            serversMenu.add(none);
            return;
        }

        List<ServerConfig> snapshot = serverSnapshot;
        if (snapshot.isEmpty()) {
            MenuItem none = new MenuItem(I18n.get("tray.servers.none"));
            none.setEnabled(false);
            serversMenu.add(none);
            return;
        }

        for (ServerConfig server : snapshot) {
            String name = server.getName() != null && !server.getName().isBlank()
                    ? server.getName()
                    : server.getAddress();
            String label = (server.isActive() ? "✓ " : "    ") + name;
            final String serverId = server.getId();
            MenuItem item = new MenuItem(label);
            item.addActionListener(e -> Platform.runLater(() -> selectActiveServer(serverId)));
            serversMenu.add(item);
        }
    }

    private void selectActiveServer(String serverId) {
        if (configStore == null) {
            return;
        }
        // Same single owner of the "exactly one active" invariant as the server
        // list, and one save instead of one per changed entry.
        configStore.setActiveServer(serverId);
        refreshTrayState();
    }

    private void onToggleConnect() {
        // Off the FX thread: a stop waits out a SIGTERM grace period and a start
        // can hash a ~40 MB binary and raise a modal admin prompt. Running that
        // inline (as this menu item once did) froze the window for up to a
        // minute on a TUN connect; ConnectionService now refuses to run on the
        // FX thread at all, so the mistake cannot come back quietly.
        Thread.startVirtualThread(this::toggleConnection);
    }

    private void toggleConnection() {
        try {
            if (connectionService.isRunning()) {
                connectionService.disconnect();
                return;
            }
            ConnectionService.ConnectAttempt attempt = connectionService.connect();
            switch (attempt.outcome()) {
                case NO_ACTIVE_SERVER -> {
                    log.warn("Tray connect clicked but no active server selected");
                    showMainWindow();
                }
                case NO_ENGINE -> {
                    log.warn("Tray connect clicked but SingBoxEngine is not available");
                }
                case ALREADY_RUNNING -> log.debug("sing-box already running");
                default -> log.info("Connected from tray to {}", attempt.server().getName());
            }
        } catch (IOException e) {
            log.error("Failed to start sing-box from tray", e);
        } catch (Exception e) {
            log.error("Unexpected error toggling connection from tray", e);
        }
    }

    private void showMainWindow() {
        Platform.runLater(() -> {
            if (stage == null) {
                return;
            }
            if (!stage.isShowing()) {
                stage.show();
            }
            if (stage.isIconified()) {
                stage.setIconified(false);
            }
            stage.toFront();
            stage.requestFocus();
        });
    }

    private void onQuit() {
        Platform.runLater(Platform::exit);
    }

    private SingBoxEngine engine() {
        return engineSupplier == null ? null : engineSupplier.get();
    }

    private ConnectionState currentState() {
        SingBoxEngine engine = engine();
        if (engine == null) {
            return ConnectionState.DISCONNECTED;
        }
        ConnectionState state = engine.connectionStateProperty().get();
        return state != null ? state : ConnectionState.DISCONNECTED;
    }

    private void attachEngineListener() {
        SingBoxEngine engine = engine();
        if (engine == null) {
            return;
        }
        stateListener = (obs, oldVal, newVal) -> refreshTrayState();
        engine.connectionStateProperty().addListener(stateListener);
        listeningTo = engine;
    }

    private void detachEngineListener() {
        if (listeningTo != null && stateListener != null) {
            listeningTo.connectionStateProperty().removeListener(stateListener);
        }
        stateListener = null;
        listeningTo = null;
    }

    /** Test seam: the engine the state listener is attached to, or null. */
    SingBoxEngine listeningTo() {
        return listeningTo;
    }

    /**
     * Moves the state listener onto whatever engine the supplier now returns.
     *
     * <p>Called after the in-app install registers a fresh engine. A listener
     * is bound to one property instance, so re-resolving the engine is not
     * enough on its own — without this the icon stops following the tunnel
     * from the moment the core is installed until the app is restarted.</p>
     */
    public void rebindEngineListener() {
        if (engine() == listeningTo) {
            return;
        }
        detachEngineListener();
        attachEngineListener();
        refreshTrayState();
    }

    private TunnelHealth currentHealth() {
        if (healthState == null) {
            return TunnelHealth.UNMONITORED;
        }
        TunnelHealth health = healthState.get();
        return health != null ? health : TunnelHealth.UNMONITORED;
    }

    /** The status shown in the menu bar: process state refined by health. */
    private TunnelStatus currentStatus() {
        return TunnelStatus.of(currentState(), currentHealth());
    }

    private String statusLabel(TunnelStatus status) {
        String key = switch (status) {
            case CONNECTED -> "tray.status.connected";
            case CONNECTING -> "tray.status.connecting";
            case VERIFYING -> "tray.status.verifying";
            case DEGRADED -> "tray.status.degraded";
            case NO_TRAFFIC -> "tray.status.no.traffic";
            case UNVERIFIED -> "tray.status.unverified";
            case ERROR -> "tray.status.error";
            case DISCONNECTED -> "tray.status.disconnected";
        };
        return I18n.get(key);
    }

    /**
     * Creates a simple colored-circle tray icon reflecting the given status.
     */
    static Image createStatusIcon(TunnelStatus status) {
        BufferedImage img = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setComposite(java.awt.AlphaComposite.Clear);
            g.fillRect(0, 0, ICON_SIZE, ICON_SIZE);
            g.setComposite(java.awt.AlphaComposite.SrcOver);

            Color fill = switch (status.tone()) {
                case OK -> new Color(46, 204, 113);
                case PENDING -> new Color(243, 156, 18);
                case BAD -> new Color(231, 76, 60);
                case IDLE -> new Color(149, 165, 166);
            };
            g.setColor(fill);
            int pad = 3;
            g.fillOval(pad, pad, ICON_SIZE - pad * 2, ICON_SIZE - pad * 2);
            g.setColor(new Color(0, 0, 0, 90));
            g.drawOval(pad, pad, ICON_SIZE - pad * 2 - 1, ICON_SIZE - pad * 2 - 1);
        } finally {
            g.dispose();
        }
        return img;
    }
}
