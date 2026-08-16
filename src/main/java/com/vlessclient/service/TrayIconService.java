package com.vlessclient.service;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.ServerConfig;
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
 * <p>AWT and JavaFX run on separate event threads: AWT updates must be
 * wrapped in {@link EventQueue#invokeLater(Runnable)}, and any JavaFX
 * state touched from tray callbacks must be wrapped in
 * {@link Platform#runLater(Runnable)}.</p>
 */
public class TrayIconService {

    private static final Logger log = LoggerFactory.getLogger(TrayIconService.class);
    private static final int ICON_SIZE = 22;

    private final SingBoxEngine singBoxEngine;
    private final ConfigStore configStore;
    private final SingBoxConfigGenerator configGenerator;
    private final Stage stage;

    private TrayIcon trayIcon;
    private PopupMenu popupMenu;
    private MenuItem toggleConnectItem;
    private MenuItem statusItem;
    private Menu serversMenu;
    private ListChangeListener<ServerConfig> serversListener;
    private javafx.beans.value.ChangeListener<ConnectionState> stateListener;

    /**
     * Creates a tray icon service bound to the given engine, stores and stage.
     *
     * @param singBoxEngine    engine whose connection state drives the icon
     * @param configStore      store providing the selectable server list
     * @param configGenerator  generator used to build a config on tray connect
     * @param stage            main window shown/hidden from the tray
     */
    public TrayIconService(SingBoxEngine singBoxEngine,
                           ConfigStore configStore,
                           SingBoxConfigGenerator configGenerator,
                           Stage stage) {
        this.singBoxEngine = singBoxEngine;
        this.configStore = configStore;
        this.configGenerator = configGenerator;
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
                Image icon = createStatusIcon(currentState());
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
        if (singBoxEngine != null) {
            stateListener = (obs, oldVal, newVal) -> refreshTrayState();
            singBoxEngine.connectionStateProperty().addListener(stateListener);
        }

        // Listen for server list changes.
        if (configStore != null) {
            serversListener = change -> refreshTrayState();
            configStore.getServers().addListener(serversListener);
        }
    }

    /**
     * Removes the tray icon from the system tray and detaches listeners.
     *
     * <p>Runs synchronously on the AWT event thread so the icon really is
     * gone by the time this method returns. This matters for the Quit flow
     * in {@link com.vlessclient.app.VlessClientApp#stop()} — that method
     * immediately calls {@code System.exit}, and any pending-but-unexecuted
     * {@code invokeLater} callback would be dropped, leaving a stale tray
     * icon behind in the menu bar.</p>
     */
    public void uninstall() {
        if (singBoxEngine != null && stateListener != null) {
            singBoxEngine.connectionStateProperty().removeListener(stateListener);
            stateListener = null;
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
        } else {
            try {
                EventQueue.invokeAndWait(removeTask);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.debug("Interrupted while uninstalling tray icon");
            } catch (java.lang.reflect.InvocationTargetException e) {
                log.debug("Error removing tray icon", e.getCause());
            }
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

        statusItem = new MenuItem(statusLabel(currentState()));
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

            trayIcon.setImage(createStatusIcon(state));
            trayIcon.setToolTip("Tunl - " + statusLabel(state));

            if (statusItem != null) {
                statusItem.setLabel(statusLabel(state));
            }
            if (toggleConnectItem != null) {
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

        List<ServerConfig> snapshot = List.copyOf(configStore.getServers());
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
        SingBoxEngine engine = singBoxEngine;
        if (engine == null) {
            log.warn("Tray connect clicked but SingBoxEngine is not available");
            return;
        }
        // Off the FX thread: stop() waits out a SIGTERM grace period and start()
        // can hash a ~40 MB binary and raise a modal admin prompt — neither may
        // run on the FX thread. The old Platform.runLater body did exactly that,
        // freezing the window for up to a minute on a TUN connect.
        Thread.startVirtualThread(() -> toggleConnection(engine));
    }

    private void toggleConnection(SingBoxEngine engine) {
        try {
            if (engine.isRunning()) {
                engine.stop();
                return;
            }
            // Read the FX-owned config on the FX thread; run the start off it.
            ConnectSnapshot snap = FxExecutor.get(() -> {
                ServerConfig active = configStore.getServers().stream()
                        .filter(ServerConfig::isActive).findFirst().orElse(null);
                return new ConnectSnapshot(
                        List.copyOf(configStore.getServers()),
                        active,
                        ServiceLocator.get(AppSettings.class));
            });
            if (snap.active() == null) {
                log.warn("Tray connect clicked but no active server selected");
                showMainWindow();
                return;
            }
            com.vlessclient.model.RoutingConfig routingConfig = null;
            try {
                routingConfig = ServiceLocator.get(RoutingService.class).getConfig();
            } catch (IllegalArgumentException e) {
                log.debug("RoutingService not available; using default route");
            }
            String configJson = configGenerator.generate(
                    snap.candidates(), snap.active(), snap.settings(), routingConfig);
            engine.awaitStopped(java.time.Duration.ofSeconds(15));
            engine.start(configJson, snap.settings().getProxyMode());
        } catch (IOException e) {
            log.error("Failed to start sing-box from tray", e);
        } catch (IllegalStateException e) {
            log.debug("sing-box already running: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error toggling connection from tray", e);
        }
    }

    /** FX-thread snapshot of everything a tray connect needs to build a config. */
    private record ConnectSnapshot(List<ServerConfig> candidates, ServerConfig active,
                                   AppSettings settings) {
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

    private ConnectionState currentState() {
        if (singBoxEngine == null) {
            return ConnectionState.DISCONNECTED;
        }
        ConnectionState state = singBoxEngine.connectionStateProperty().get();
        return state != null ? state : ConnectionState.DISCONNECTED;
    }

    private String statusLabel(ConnectionState state) {
        String key = switch (state) {
            case CONNECTED -> "tray.status.connected";
            case CONNECTING -> "tray.status.connecting";
            case ERROR -> "tray.status.error";
            case DISCONNECTED -> "tray.status.disconnected";
        };
        return I18n.get(key);
    }

    /**
     * Creates a simple colored-circle tray icon reflecting the given state.
     */
    static Image createStatusIcon(ConnectionState state) {
        BufferedImage img = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setComposite(java.awt.AlphaComposite.Clear);
            g.fillRect(0, 0, ICON_SIZE, ICON_SIZE);
            g.setComposite(java.awt.AlphaComposite.SrcOver);

            Color fill = switch (state) {
                case CONNECTED -> new Color(46, 204, 113);
                case CONNECTING -> new Color(243, 156, 18);
                case ERROR -> new Color(231, 76, 60);
                case DISCONNECTED -> new Color(149, 165, 166);
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
