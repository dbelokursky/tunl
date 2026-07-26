package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.LatencyTester;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.TrafficMonitor;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * Regenerates the README screenshots in {@code docs/screenshots}.
 *
 * <p>Not part of the suite: it writes into the working tree, so it is gated on
 * a property and runs only when asked.</p>
 *
 * <pre>mvn test -Dtest=ScreenshotGenerator -Dtunl.screenshots=true -Djacoco.skip=true</pre>
 *
 * <p>Screenshots rot faster than prose, and a hand-cropped PNG cannot be
 * refreshed by whoever changes the layout next. This exists so the answer to
 * "the README shows the old UI" is one command rather than an afternoon.</p>
 */
@EnabledIfSystemProperty(named = "tunl.screenshots", matches = "true")
public class ScreenshotGenerator extends ApplicationTest {

    /** Retina: GitHub serves the README at CSS width, so render at 2x. */
    private static final double SCALE = 2.0;
    private static final int WINDOW_WIDTH = 1100;
    private static final int WINDOW_HEIGHT = 740;

    private static final Path OUT_DIR = Path.of("docs", "screenshots");

    private MainViewController mainController;
    private Parent windowRoot;

    @BeforeAll
    static void setupHeadless() {
        System.setProperty("testfx.robot", "glass");
        System.setProperty("testfx.headless", "true");
        System.setProperty("prism.order", "sw");
        System.setProperty("prism.text", "t2k");
        System.setProperty("java.awt.headless", "true");
        ServiceLocator.initialize();
    }

    @Override
    public void start(Stage stage) throws Exception {
        seedSampleServers();
        // The runner has no sing-box, so ServiceLocator leaves the engine
        // unregistered and the dashboard renders its "not installed" branch.
        // Registering an engine that is never started gives the same view a
        // machine with a working install shows.
        ServiceLocator.register(SingBoxEngine.class,
                new SingBoxEngine(Path.of("/usr/local/bin/sing-box")));

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainView.fxml"));
        Parent root = loader.load();
        mainController = loader.getController();
        windowRoot = root;
        Scene scene = new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT);
        // The app dresses the scene at runtime through ThemeManager; without
        // this the screenshots come out in stock Modena, which is not a UI the
        // project ships.
        scene.getStylesheets().setAll(
                getClass().getResource("/css/dark.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    /**
     * Puts the app into the state a working install is in, by driving the real
     * state machine rather than overwriting labels: the connection state and
     * the traffic counters are set at their source, and every pixel downstream
     * is then produced by the app's own logic. A screenshot assembled by
     * poking text into labels would show a UI the code cannot actually reach.
     */
    private void showConnectedState() {
        // The dashboard only learns the active server inside its connect flow,
        // so a state flip alone leaves the status line naming no server. Set it
        // to what the store already says is active — the same value connecting
        // for real would have produced.
        ConfigStore store = ServiceLocator.get(ConfigStore.class);
        store.getServers().stream().filter(ServerConfig::isActive).findFirst()
                .ifPresent(active -> setPrivateField(dashboardController(), "activeServer",
                        field -> field.accept(active)));

        setPrivateProperty(ServiceLocator.get(SingBoxEngine.class), "connectionState",
                wrapper -> ((ReadOnlyObjectWrapper<ConnectionState>) wrapper)
                        .set(ConnectionState.CONNECTED));

        TrafficMonitor traffic = ServiceLocator.get(TrafficMonitor.class);
        setPrivateProperty(traffic, "totalDownload",
                property -> ((LongProperty) property).set(4_187_593_000L));
        setPrivateProperty(traffic, "totalUpload",
                property -> ((LongProperty) property).set(311_842_000L));

        // A run of samples, not one value: the sparkline draws the series it
        // has been fed, so a single number would render as a flat line.
        for (int i = 0; i < 48; i++) {
            double phase = i / 6.0;
            long down = (long) (2_600_000 + 1_900_000 * Math.sin(phase)
                    + 420_000 * Math.sin(phase * 3.1));
            long up = (long) (240_000 + 170_000 * Math.sin(phase * 1.4 + 1.0));
            setPrivateProperty(traffic, "downloadSpeed",
                    property -> ((LongProperty) property).set(Math.max(0, down)));
            setPrivateProperty(traffic, "uploadSpeed",
                    property -> ((LongProperty) property).set(Math.max(0, up)));
        }
    }

    /** Reaches the dashboard's controller through MainView's cache. */
    private Object dashboardController() {
        try {
            var field = MainViewController.class.getDeclaredField("controllerCache");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var cache = (java.util.Map<String, Object>) field.get(mainController);
            return java.util.Objects.requireNonNull(cache.get("DashboardView"),
                    "dashboard not loaded yet");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("controller cache moved; update the generator", e);
        }
    }

    /** Assigns a private field, given a setter that receives the new value. */
    private static void setPrivateField(Object target, String fieldName,
                                        Consumer<Consumer<Object>> supply) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            supply.accept(value -> {
                try {
                    field.set(target, value);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            });
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "field '" + fieldName + "' moved; update the generator", e);
        }
    }

    private static void setPrivateProperty(Object target, String fieldName,
                                           Consumer<Object> mutate) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            mutate.accept(field.get(target));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "field '" + fieldName + "' moved; update the generator", e);
        }
    }

    /**
     * A believable but obviously-sample list: real-looking public addresses so
     * the country lookup resolves and the flags render, no credentials, and
     * names that read as a subscription rather than as someone's live config.
     */
    private void seedSampleServers() {
        ConfigStore store = ServiceLocator.get(ConfigStore.class);
        store.getServers().clear();
        store.addServer(server("Amsterdam 01", "185.107.56.12", 443, Protocol.VLESS));
        store.addServer(server("Frankfurt 02", "88.198.0.1", 8443, Protocol.TROJAN));
        store.addServer(server("Tokyo 07", "172.104.100.1", 2087, Protocol.VMESS));
        store.addServer(server("Helsinki WG", "95.216.32.11", 51820, Protocol.WIREGUARD));
        store.addServer(server("Singapore 03", "128.199.83.144", 8388, Protocol.SHADOWSOCKS));

        List<ServerConfig> all = store.getServers();
        LatencyTester tester = ServiceLocator.get(LatencyTester.class);
        record Sample(int index, long millis) { }
        for (Sample sample : List.of(new Sample(0, 38), new Sample(1, 52),
                new Sample(2, 214), new Sample(4, 176))) {
            recordLatency(tester, all.get(sample.index()).getId(), sample.millis());
        }
    }

    /** Seeds a measurement without a network round-trip. */
    private static void recordLatency(LatencyTester tester, String serverId, long millis) {
        try {
            var field = LatencyTester.class.getDeclaredField("lastResults");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var results = (java.util.Map<String, LatencyTester.Result>) field.get(tester);
            results.put(serverId, new LatencyTester.Result(millis, true));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("latency cache moved; update the generator", e);
        }
    }

    private static ServerConfig server(String name, String address, int port, Protocol proto) {
        ServerConfig config = new ServerConfig();
        config.setName(name);
        config.setAddress(address);
        config.setPort(port);
        config.setProtocol(proto);
        return config;
    }

    @Test
    void writeScreenshots() throws Exception {
        Files.createDirectories(OUT_DIR);
        interact(() -> mainController.showDashboard());
        sleep(400);
        interact(() -> {
            hideNode("#singBoxMissingBanner");
            showConnectedState();
        });
        capture("dashboard", controller -> controller.showDashboard());
        capture("servers", controller -> controller.showServers());
        capture("routing", controller -> controller.showRouting());
        capture("settings", controller -> controller.showSettings());
    }

    /**
     * Hides a node that only appears on a machine without sing-box installed.
     * The screenshots document the app as a user with a working install sees
     * it, not the state of the headless runner that rendered them.
     */
    private void hideNode(String selector) {
        javafx.scene.Node node = lookup(selector).query();
        node.setVisible(false);
        node.setManaged(false);
    }

    private void capture(String name, Consumer<MainViewController> show) throws Exception {
        interact(() -> show.accept(mainController));
        // Country lookups and layout passes both land a beat later.
        sleep(1200);

        WritableImage[] holder = new WritableImage[1];
        interact(() -> {
            SnapshotParameters params = new SnapshotParameters();
            params.setTransform(new Scale(SCALE, SCALE));
            holder[0] = windowRoot.snapshot(params, null);
        });
        write(holder[0], OUT_DIR.resolve(name + ".png").toFile());
    }

    /** javafx.swing is not on the module path, so copy the pixels by hand. */
    private static void write(WritableImage image, File target) throws Exception {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        java.awt.image.BufferedImage buffered = new java.awt.image.BufferedImage(
                width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = image.getPixelReader();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                buffered.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        ImageIO.write(buffered, "png", target);
        System.out.println("WROTE " + target.getAbsolutePath()
                + " (" + width + "x" + height + ")");
    }
}
