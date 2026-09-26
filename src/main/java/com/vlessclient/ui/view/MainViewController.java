package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.PersistenceState;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
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
    @FXML private HBox persistenceBanner;
    @FXML private Label persistenceMessage;
    @FXML private Button retrySavingButton;
    @FXML private HBox unreadableBanner;
    @FXML private Label unreadableMessage;
    @FXML private HBox fileNoticeBanner;
    @FXML private Label fileNoticeMessage;
    @FXML private Button dismissFileNoticeButton;
    private PersistenceState persistence;

    @FXML private Button btnDashboard;
    @FXML private Button btnServers;
    @FXML private Button btnSubscriptions;
    @FXML private Button btnRouting;
    @FXML private Button btnLogs;
    @FXML private Button btnSettings;

    private final Map<String, Node> viewCache = new HashMap<>();
    private final Map<String, Object> controllerCache = new HashMap<>();
    private Button activeButton;
    /** The page on screen, for Shortcut+F to find its search field in. */
    private Node currentView;
    private boolean acceleratorsRegistered;

    /**
     * Installs the sidebar icons, shows the Dashboard as the initial page, and
     * registers the keyboard shortcuts once the window's scene is available.
     */
    @FXML
    public void initialize() {
        installSidebarIcons();
        bindSidebarLabels();
        bindPersistenceBanner();
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

    private void bindPersistenceBanner() {
        persistenceMessage.textProperty().bind(I18n.binding("persistence.unsaved"));
        retrySavingButton.textProperty().bind(I18n.binding("persistence.retry"));
        dismissFileNoticeButton.textProperty().bind(I18n.binding("persistence.dismiss"));
        ServiceLocator.find(ConfigStore.class).ifPresent(store -> {
            persistence = store.getPersistenceState();
            persistenceBanner.visibleProperty().bind(persistence.unsavedProperty());
            persistenceBanner.managedProperty().bind(persistenceBanner.visibleProperty());
            unreadableMessage.textProperty().bind(Bindings.createStringBinding(
                    () -> I18n.get("persistence.unreadable",
                            persistence.unreadableFilesProperty().get()),
                    I18n.localeProperty(), persistence.unreadableFilesProperty()));
            unreadableBanner.visibleProperty().bind(persistence.hasUnreadableProperty());
            unreadableBanner.managedProperty().bind(unreadableBanner.visibleProperty());
            fileNoticeMessage.textProperty().bind(Bindings.createStringBinding(
                    () -> fileNotice(persistence.heldFilesProperty().get(),
                            persistence.setAsideFilesProperty().get()),
                    I18n.localeProperty(), persistence.heldFilesProperty(),
                    persistence.setAsideFilesProperty()));
            fileNoticeBanner.visibleProperty().bind(Bindings.createBooleanBinding(
                    () -> !persistence.heldFilesProperty().get().isEmpty()
                            || !persistence.setAsideFilesProperty().get().isEmpty(),
                    persistence.heldFilesProperty(), persistence.setAsideFilesProperty()));
            fileNoticeBanner.managedProperty().bind(fileNoticeBanner.visibleProperty());
            // Only the set-aside notices go away: a held file stays held, and
            // unsaved, until the app is started again.
            dismissFileNoticeButton.visibleProperty().bind(Bindings.createBooleanBinding(
                    () -> !persistence.setAsideFilesProperty().get().isEmpty(),
                    persistence.setAsideFilesProperty()));
            dismissFileNoticeButton.managedProperty().bind(
                    dismissFileNoticeButton.visibleProperty());
        });
    }

    /**
     * What the file banner says: a sentence for each file left as it is
     * because it could not be opened, then one for each damaged file set
     * aside, with where it is now.
     */
    static String fileNotice(Map<String, String> held, Map<String, String> setAside) {
        List<String> sentences = new ArrayList<>();
        held.keySet().forEach(file -> sentences.add(I18n.get("persistence.held", file)));
        setAside.forEach((file, where) ->
                sentences.add(I18n.get("persistence.set.aside", file, where)));
        return String.join("\n", sentences);
    }

    @FXML
    private void onDismissFileNotice() {
        if (persistence != null) {
            persistence.dismissSetAside();
        }
    }

    @FXML
    private void onRetrySaving() {
        if (persistence == null) {
            return;
        }
        retrySavingButton.setDisable(true);
        Thread.startVirtualThread(() -> {
            try {
                persistence.retry();
            } finally {
                Platform.runLater(() -> retrySavingButton.setDisable(false));
            }
        });
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
        accelerators.put(KeyCombination.keyCombination("Shortcut+F"), this::focusSearchField);
        accelerators.put(KeyCombination.keyCombination("Shortcut+Shift+C"),
                this::onShortcutToggleConnection);
        accelerators.put(KeyCombination.keyCombination("Shortcut+W"), this::onShortcutHideWindow);

        log.info("Registered {} keyboard shortcuts", accelerators.size());
    }

    /**
     * Shortcut+F: the search field of the page on screen, its text selected so
     * typing replaces it. The Servers and Logs pages each have one, and only
     * the pointer reached it.
     */
    private void focusSearchField() {
        if (currentView != null
                && currentView.lookup("#searchField") instanceof TextField search) {
            search.requestFocus();
            search.selectAll();
        }
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
                // Every view is mounted in the shared scroll wrapper; see
                // ContentScrollPane for the sizing policy it enforces.
                contentArea.getChildren().setAll(new ContentScrollPane(view));
                currentView = view;
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
            if (controller instanceof ServersViewController servers) {
                servers.setOnAddSubscription(this::addSubscription);
            }
            return view;
        } catch (IOException e) {
            log.error("Failed to load FXML: {}", fxmlPath, e);
            return null;
        }
    }

    /**
     * Opens the Subscriptions page's form holding {@code url}: a subscription
     * URL pasted on the Servers page, which fetches none.
     */
    private void addSubscription(String url) {
        showSubscriptions();
        if (controllerCache.get("SubscriptionsView")
                instanceof SubscriptionsViewController subscriptions) {
            subscriptions.openAddSubscriptionDialog(url);
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
