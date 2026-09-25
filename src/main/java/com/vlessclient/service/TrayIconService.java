package com.vlessclient.service;

import com.vlessclient.app.I18n;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.TunnelHealth;
import com.vlessclient.model.TunnelStatus;
import com.vlessclient.service.outbound.OutboundTags;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringProperty;
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
    /** The tag of the server the core picked itself; FX-owned, like the list. */
    private final ReadOnlyStringProperty corePick;
    /** {@link #corePick} as AWT sees it, copied on the FX thread. */
    private volatile String corePickSnapshot;
    private javafx.beans.value.ChangeListener<String> corePickListener;
    private final Stage stage;
    private final FailureNotices failureNotices;

    private TrayIcon trayIcon;
    /** Puts up a system notification: a title and a body. */
    private volatile BiConsumer<String, String> notifier = this::displayError;
    private PopupMenu popupMenu;
    private MenuItem toggleConnectItem;
    private MenuItem statusItem;
    private Menu serversMenu;
    private ListChangeListener<ServerConfig> serversListener;
    /** Where work for the AWT thread is queued; replaced in tests. */
    private Consumer<Runnable> awtInvoker = EventQueue::invokeLater;
    /** A refresh is queued and has not started, so another request can join it. */
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    /** The servers submenu as last built, so an unchanged list rebuilds nothing. */
    private ServerMenu shownServerMenu;
    private javafx.beans.value.ChangeListener<ConnectionState> stateListener;
    private javafx.beans.value.ChangeListener<TunnelHealth> healthListener;
    private MenuItem showItem;
    private MenuItem quitItem;
    /** The language the menu was last labeled in; a switch labels it again. */
    private Locale labeledIn;
    /**
     * Queues a refresh when the UI's language changes. Held here and handed to
     * the locale property through a weak listener, so the property does not
     * keep this service alive.
     */
    private final javafx.beans.value.ChangeListener<Locale> localeListener =
            (obs, was, is) -> requestRefresh();

    /**
     * Creates a tray icon service bound to the given engine, stores and stage.
     *
     * @param engineSupplier    supplies the engine whose state drives the icon
     * @param configStore       store providing the selectable server list
     * @param connectionService owner of the connect/disconnect flow
     * @param healthState       reachability verdict refining a running tunnel,
     *                          may be null (the icon then reports process
     *                          state alone)
     * @param corePick          the tag of the server the core picked itself
     *                          in the Fastest mode, marked in the servers
     *                          submenu as the one in use now; may be null
     * @param stage             main window shown/hidden from the tray
     */
    public TrayIconService(Supplier<SingBoxEngine> engineSupplier,
                           ConfigStore configStore,
                           ConnectionService connectionService,
                           TunnelHealthState healthState,
                           ReadOnlyStringProperty corePick,
                           Stage stage) {
        this.engineSupplier = engineSupplier;
        this.configStore = configStore;
        this.connectionService = connectionService;
        this.healthState = healthState;
        this.corePick = corePick;
        this.stage = stage;
        // Automatic retries stay within the user's request; a connect,
        // reconnect or disconnect starts another.
        this.failureNotices = new FailureNotices(connectionService == null
                ? () -> 0L
                : () -> connectionService.getRecoveryService().currentRequest());
        I18n.localeProperty().addListener(
                new javafx.beans.value.WeakChangeListener<>(localeListener));
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

        followCorePick();

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
     * Follows the server the core picked itself, which the servers submenu
     * marks. The property changes on the FX thread; AWT reads the copy.
     * Package-private for a test: install() needs a system tray.
     */
    void followCorePick() {
        if (corePick == null || corePickListener != null) {
            return;
        }
        corePickListener = (obs, oldTag, newTag) -> {
            corePickSnapshot = newTag;
            refreshTrayState();
        };
        corePick.addListener(corePickListener);
        corePickSnapshot = corePick.get();
    }

    /** The core's pick as the servers submenu last read it; for a test. */
    String corePick() {
        return corePickSnapshot;
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
        if (corePick != null && corePickListener != null) {
            corePick.removeListener(corePickListener);
            corePickListener = null;
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

        showItem = new MenuItem(I18n.get("tray.show"));
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

        quitItem = new MenuItem(I18n.get("tray.quit"));
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
        requestRefresh();
    }

    /**
     * Queues a refresh of the icon, the labels and the servers submenu on the
     * AWT thread, or joins the one already queued: it reads the state when it
     * runs, so it shows every change requested before that. Each change of the
     * server list used to queue a refresh of its own, and a subscription
     * refresh of 300 servers queued 300 rebuilds of a 300-item native menu,
     * about 7 seconds on macOS, where the AWT thread is the main thread.
     */
    void requestRefresh() {
        if (refreshQueued.compareAndSet(false, true)) {
            awtInvoker.accept(() -> {
                refreshQueued.set(false);
                applyTrayState();
            });
        }
    }

    /** Test seam: replaces the AWT event queue. */
    void setAwtInvoker(Consumer<Runnable> invoker) {
        this.awtInvoker = invoker;
    }

    /** Test seam: replaces the system notification a failed connect puts up. */
    void setNotifier(BiConsumer<String, String> notifier) {
        this.notifier = notifier;
    }

    private void applyTrayState() {
        if (trayIcon == null) {
            return;
        }
        ConnectionState state = currentState();
        TunnelStatus status = TunnelStatus.of(state, currentHealth());

        trayIcon.setImage(createStatusIcon(status));
        // While the user wants a tunnel that carries nothing, traffic goes
        // out direct; the status alone ("Connecting", "Error") did not say it.
        String label = AppHttpClients.isTunnelWantedButNotCarrying()
                ? statusLabel(status) + " " + I18n.get("tray.status.unprotected")
                : statusLabel(status);
        trayIcon.setToolTip("Tunl - " + label);

        if (statusItem != null) {
            statusItem.setLabel(label);
        }
        if (toggleConnectItem != null) {
            // Keyed off the process state, not the status: a tunnel that
            // carries no traffic is still one the user disconnects.
            boolean connected = state == ConnectionState.CONNECTED
                    || state == ConnectionState.CONNECTING;
            toggleConnectItem.setLabel(
                    connected ? I18n.get("tray.disconnect") : I18n.get("tray.connect"));
        }
        relabelIfLanguageChanged();
        rebuildServersMenu();
    }

    /**
     * Labels the fixed items again when the UI's language changed since they
     * were labeled, and has the servers submenu built again for its "no
     * servers" and "N more" items. They were labeled once, when the menu was
     * built, and stayed in the old language until a restart.
     */
    private void relabelIfLanguageChanged() {
        Locale locale = I18n.getLocale();
        if (locale.equals(labeledIn)) {
            return;
        }
        if (showItem != null) {
            showItem.setLabel(I18n.get("tray.show"));
        }
        if (serversMenu != null) {
            serversMenu.setLabel(I18n.get("tray.servers.select"));
        }
        if (quitItem != null) {
            quitItem.setLabel(I18n.get("tray.quit"));
        }
        shownServerMenu = null;
        labeledIn = locale;
    }

    /** Most servers the tray's submenu lists; the others are counted. */
    static final int MAX_MENU_SERVERS = 25;

    /**
     * A server as the tray lists it.
     *
     * @param id     the server's id
     * @param label  its name, or its address when it has none
     * @param active whether it is the selected one
     * @param now    whether the core picked it itself and routes through it now
     */
    record MenuServer(String id, String label, boolean active, boolean now) {
    }

    /**
     * What the servers submenu shows.
     *
     * @param items the servers listed
     * @param more  how many are left out
     */
    record ServerMenu(List<MenuServer> items, int more) {
    }

    /**
     * The submenu for a server list: the selected server and the one the core
     * picked itself wherever they are, then the first of the rest up to
     * {@link #MAX_MENU_SERVERS}, in list order, and the others counted. A
     * native menu of hundreds of items from a big subscription could not be
     * read anyway.
     *
     * @param servers     the server list
     * @param corePickTag the tag of the server the core picked itself, or null
     */
    static ServerMenu serverMenu(List<ServerConfig> servers, String corePickTag) {
        ServerConfig selected = servers.stream().filter(ServerConfig::isActive)
                .findFirst().orElse(null);
        ServerConfig current = corePickTag == null ? null : servers.stream()
                .filter(server -> corePickTag.equals(OutboundTags.server(server)))
                .findFirst().orElse(null);
        int room = MAX_MENU_SERVERS - (int) Stream.of(selected, current)
                .filter(Objects::nonNull).distinct().count();
        List<MenuServer> items = new ArrayList<>();
        int others = 0;
        for (ServerConfig server : servers) {
            boolean kept = server == selected || server == current;
            if (kept || others < room) {
                items.add(menuServer(server, server == current));
                others += kept ? 0 : 1;
            }
        }
        return new ServerMenu(List.copyOf(items), servers.size() - items.size());
    }

    private static MenuServer menuServer(ServerConfig server, boolean now) {
        String label = server.getName() != null && !server.getName().isBlank()
                ? server.getName()
                : server.getAddress();
        return new MenuServer(server.getId(), label, server.isActive(), now);
    }

    /**
     * An item's text: a tick on the selected server, and the server the core
     * routes through now named as such.
     */
    static String itemLabel(MenuServer server) {
        return (server.active() ? "✓ " : "    ")
                + (server.now() ? I18n.get("tray.servers.now", server.label()) : server.label());
    }

    private void rebuildServersMenu() {
        if (serversMenu == null) {
            return;
        }
        List<ServerConfig> current = configStore == null ? List.of() : serverSnapshot;
        if (!current.isEmpty()
                && serverMenu(current, corePickSnapshot).equals(shownServerMenu)) {
            // Most refreshes change the icon or a label, not the servers; a
            // subscription refresh re-applies every server, changed or not.
            return;
        }
        shownServerMenu = null;
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

        ServerMenu menu = serverMenu(snapshot, corePickSnapshot);
        for (MenuServer server : menu.items()) {
            MenuItem item = new MenuItem(itemLabel(server));
            item.addActionListener(e -> Platform.runLater(() -> selectActiveServer(server.id())));
            serversMenu.add(item);
        }
        if (menu.more() > 0) {
            MenuItem more = new MenuItem(I18n.get("tray.servers.more", menu.more()));
            more.addActionListener(e -> showMainWindow());
            serversMenu.add(more);
        }
        shownServerMenu = menu;
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

    /** Connects or disconnects, off the FX thread; package-private for a test. */
    void toggleConnection() {
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
                    notifier.accept(I18n.get("error.singbox.not.found"),
                            I18n.get("dashboard.error.singbox.body"));
                    showMainWindow();
                }
                case ALREADY_RUNNING -> log.debug("sing-box already running");
                default -> log.info("Connected from tray to {}", attempt.server().getName());
            }
        } catch (IOException e) {
            // Said, not only logged: the tray is where a user with the window
            // hidden connects, and a failed start left the icon grey and
            // nothing else, while the Dashboard puts up the same reason.
            log.error("Failed to start sing-box from tray", e);
            notifier.accept(I18n.get("dashboard.error.start.title"), reasonOf(e));
        } catch (Exception e) {
            log.error("Unexpected error toggling connection from tray", e);
            notifier.accept(I18n.get("dashboard.error.start.title"),
                    I18n.get("error.connection.failed", reasonOf(e)));
        }
    }

    private static String reasonOf(Exception e) {
        return e.getMessage() != null && !e.getMessage().isBlank()
                ? e.getMessage() : e.toString();
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
        stateListener = (obs, oldVal, newVal) -> {
            refreshTrayState();
            // Once per failure streak: recovery restarting a core that fails at
            // every start used to notify again at every backoff step.
            if (failureNotices.onState(newVal)) {
                notifyTunnelFailed();
            }
        };
        engine.connectionStateProperty().addListener(stateListener);
        listeningTo = engine;
    }

    /**
     * A system notification for a core that died. With the window hidden, a
     * change of icon colour was all the user got — and a tunnel that stops
     * is exactly the moment the app must not be quiet. Package-private so a
     * test can count the notices.
     */
    void notifyTunnelFailed() {
        TrayIcon icon = trayIcon;
        SingBoxEngine engine = engine();
        if (icon == null || engine == null) {
            return;
        }
        String detail = engine.errorMessageProperty().get();
        String body = detail == null || detail.isBlank()
                ? I18n.get("tray.notify.failed.body") : detail;
        try {
            icon.displayMessage(I18n.get("tray.notify.failed.title"), body,
                    TrayIcon.MessageType.ERROR);
        } catch (RuntimeException e) {
            log.debug("Could not show the tray notification: {}", e.toString());
        }
    }

    private void displayError(String title, String body) {
        TrayIcon icon = trayIcon;
        if (icon == null) {
            return;
        }
        try {
            icon.displayMessage(title, body, TrayIcon.MessageType.ERROR);
        } catch (RuntimeException e) {
            log.debug("Could not show the tray notification: {}", e.toString());
        }
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
