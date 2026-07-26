package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller for the main application window. Owns the sidebar navigation,
 * lazily loads and caches each page's FXML view and controller, and registers
 * the global keyboard shortcuts.
 */
public class MainViewController {

    private static final Logger log = LoggerFactory.getLogger(MainViewController.class);

    @FXML private BorderPane rootNode;
    @FXML private StackPane contentArea;
    @FXML private VBox sidebar;

    @FXML private Button btnDashboard;
    @FXML private Button btnServers;
    @FXML private Button btnSubscriptions;
    @FXML private Button btnRouting;
    @FXML private Button btnLogs;
    @FXML private Button btnSettings;

    private final Map<String, Node> viewCache = new HashMap<>();
    private final Map<String, Object> controllerCache = new HashMap<>();
    private Button activeButton;
    private boolean acceleratorsRegistered;

    /**
     * Installs the sidebar icons, shows the Dashboard as the initial page, and
     * registers the keyboard shortcuts once the window's scene is available.
     */
    @FXML
    public void initialize() {
        installSidebarIcons();
        bindSidebarLabels();
        showDashboard();

        if (rootNode != null) {
            rootNode.sceneProperty().addListener((obs, oldScene, newScene) -> {
                if (newScene != null && !acceleratorsRegistered) {
                    registerAccelerators(newScene);
                    acceleratorsRegistered = true;
                }
            });
        }
    }

    /**
     * Replaces the emoji graphics in {@code MainView.fxml} with crisp
     * SVG-based flat icons from {@link Icons}. Done in code (not FXML)
     * because FXML's static-method invocation support is awkward for
     * our wrapping {@code Group → StackPane} structure.
     */
    private void installSidebarIcons() {
        double size = 26;
        if (btnDashboard != null) {
            btnDashboard.setGraphic(Icons.dashboard(size));
        }
        if (btnServers != null) {
            btnServers.setGraphic(Icons.server(size));
        }
        if (btnSubscriptions != null) {
            btnSubscriptions.setGraphic(Icons.link(size));
        }
        if (btnRouting != null) {
            btnRouting.setGraphic(Icons.routing(size));
        }
        if (btnLogs != null) {
            btnLogs.setGraphic(Icons.list(size));
        }
        if (btnSettings != null) {
            btnSettings.setGraphic(Icons.settings(size));
        }
    }

    /**
     * Binds the sidebar labels to the message bundle.
     *
     * <p>The FXML carried English literals, so the navigation stayed in English
     * for a Russian user even though {@code sidebar.*} has been translated all
     * along — the most visible of the keys that were sitting unused. Binding
     * (rather than a one-off {@code setText}) also makes the labels follow a
     * language change without a restart, matching how Settings already works.</p>
     */
    private void bindSidebarLabels() {
        bindText(btnDashboard, "sidebar.dashboard");
        bindText(btnServers, "sidebar.servers");
        bindText(btnSubscriptions, "sidebar.subscriptions");
        bindText(btnRouting, "sidebar.routing");
        bindText(btnLogs, "sidebar.logs");
        bindText(btnSettings, "sidebar.settings");
    }

    private static void bindText(Button button, String key) {
        if (button != null) {
            button.textProperty().bind(I18n.binding(key));
        }
    }

    private void registerAccelerators(Scene scene) {
        Map<KeyCombination, Runnable> accelerators = scene.getAccelerators();

        accelerators.put(KeyCombination.keyCombination("Shortcut+1"), this::showDashboard);
        accelerators.put(KeyCombination.keyCombination("Shortcut+2"), this::showServers);
        accelerators.put(KeyCombination.keyCombination("Shortcut+3"), this::showSubscriptions);
        accelerators.put(KeyCombination.keyCombination("Shortcut+4"), this::showRouting);
        accelerators.put(KeyCombination.keyCombination("Shortcut+5"), this::showLogs);
        accelerators.put(KeyCombination.keyCombination("Shortcut+Comma"), this::showSettings);

        accelerators.put(KeyCombination.keyCombination("Shortcut+N"), this::onShortcutAddServer);
        accelerators.put(KeyCombination.keyCombination("Shortcut+Shift+C"),
                this::onShortcutToggleConnection);
        accelerators.put(KeyCombination.keyCombination("Shortcut+W"), this::onShortcutHideWindow);

        log.info("Registered {} keyboard shortcuts", accelerators.size());
    }

    private void onShortcutAddServer() {
        showServers();
        Object controller = controllerCache.get("ServersView");
        if (controller instanceof ServersViewController serversController) {
            serversController.openAddServerDialog();
        } else {
            log.warn("ServersViewController not available for Cmd+N shortcut");
        }
    }

    private void onShortcutToggleConnection() {
        // Ensure Dashboard is loaded so we have a controller reference
        ensureViewLoaded("DashboardView", "/fxml/DashboardView.fxml");
        Object controller = controllerCache.get("DashboardView");
        if (controller instanceof DashboardViewController dashboardController) {
            dashboardController.toggleConnection();
        } else {
            log.warn("DashboardViewController not available for Cmd+Shift+C shortcut");
        }
    }

    /**
     * Triggers auto-connect on the Dashboard when the user enabled
     * "Auto-connect on startup" in Settings. Called once by
     * {@link com.vlessclient.app.VlessClientApp} after the main window opens.
     */
    public void triggerAutoConnect() {
        ensureViewLoaded("DashboardView", "/fxml/DashboardView.fxml");
        Object controller = controllerCache.get("DashboardView");
        if (controller instanceof DashboardViewController dashboardController) {
            dashboardController.autoConnectIfEnabled();
        } else {
            log.warn("DashboardViewController not available for auto-connect");
        }
    }

    private void onShortcutHideWindow() {
        if (rootNode != null && rootNode.getScene() != null
                && rootNode.getScene().getWindow() instanceof Stage stage) {
            stage.hide();
        }
    }

    @FXML
    private void onDashboardClicked() {
        showDashboard();
    }

    @FXML
    private void onServersClicked() {
        showServers();
    }

    @FXML
    private void onSubscriptionsClicked() {
        showSubscriptions();
    }

    @FXML
    private void onRoutingClicked() {
        showRouting();
    }

    @FXML
    private void onLogsClicked() {
        showLogs();
    }

    @FXML
    private void onSettingsClicked() {
        showSettings();
    }

    public void showDashboard() {
        switchView("DashboardView", "/fxml/DashboardView.fxml", btnDashboard);
    }

    public void showServers() {
        switchView("ServersView", "/fxml/ServersView.fxml", btnServers);
    }

    public void showSubscriptions() {
        switchView("SubscriptionsView", "/fxml/SubscriptionsView.fxml", btnSubscriptions);
    }

    public void showRouting() {
        switchView("RoutingView", "/fxml/RoutingView.fxml", btnRouting);
    }

    public void showLogs() {
        switchView("LogsView", "/fxml/LogsView.fxml", btnLogs);
    }

    public void showSettings() {
        switchView("SettingsView", "/fxml/SettingsView.fxml", btnSettings);
    }

    private void switchView(String viewName, String fxmlPath, Button navButton) {
        try {
            Node view = ensureViewLoaded(viewName, fxmlPath);
            if (view != null) {
                // Wrap every loaded view in a ScrollPane so the whole page is
                // reachable when the window is smaller than the content
                // (otherwise buttons like Test Latency are clipped off the
                // bottom and can't be clicked at all). fitToHeight stretches
                // the page to the viewport when the window is taller than the
                // content, so views with a VBox.vgrow child (the Logs list)
                // can actually fill it; scrolling still kicks in once the
                // viewport is smaller than the page's min height.
                javafx.scene.control.ScrollPane wrapper =
                        new javafx.scene.control.ScrollPane(view);
                wrapper.setFitToWidth(true);
                wrapper.setFitToHeight(true);
                wrapper.setHbarPolicy(
                        javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
                wrapper.setVbarPolicy(
                        javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED);
                wrapper.getStyleClass().add("content-scroll");
                contentArea.getChildren().setAll(wrapper);
                setActiveButton(navButton);
                // Cached views are re-shown without re-initializing; give the
                // controller a chance to refresh state that went stale.
                Object controller = controllerCache.get(viewName);
                if (controller instanceof ViewShownAware shown) {
                    shown.onViewShown();
                }
            }
        } catch (Exception e) {
            log.error("Failed to switch to view: {}", viewName, e);
        }
    }

    private Node ensureViewLoaded(String viewName, String fxmlPath) {
        Node cached = viewCache.get(viewName);
        if (cached != null) {
            return cached;
        }
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath));
            Node view = loader.load();
            viewCache.put(viewName, view);
            Object controller = loader.getController();
            if (controller != null) {
                controllerCache.put(viewName, controller);
            }
            return view;
        } catch (IOException e) {
            log.error("Failed to load FXML: {}", fxmlPath, e);
            return null;
        }
    }

    private void setActiveButton(Button button) {
        if (activeButton != null) {
            activeButton.getStyleClass().remove("nav-button-active");
        }
        activeButton = button;
        if (activeButton != null) {
            if (!activeButton.getStyleClass().contains("nav-button-active")) {
                activeButton.getStyleClass().add("nav-button-active");
            }
        }
    }
}
