package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.app.ThemeCss;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.HealthCheckTarget;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.model.Subscription;
import com.vlessclient.platform.InMemorySecretSealer;
import com.vlessclient.platform.SecretSealer;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.CountryResolver;
import com.vlessclient.service.GeoIpDatabase;
import com.vlessclient.service.LatencyTester;
import com.vlessclient.service.ServiceReachabilityChecker;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.SubscriptionService;
import com.vlessclient.service.TrafficHistoryStore;
import com.vlessclient.service.TrafficMonitor;
import com.vlessclient.testing.UiTest;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Label;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
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
@UiTest
public class ScreenshotGenerator extends ApplicationTest {

    /** Retina: GitHub serves the README at CSS width, so render at 2x. */
    private static final double SCALE = 2.0;
    private static final int WINDOW_WIDTH = 1100;
    private static final int WINDOW_HEIGHT = 740;

    private static final Path OUT_DIR = Path.of("docs", "screenshots");

    /** Days the dashboard's history panel plots, and so the fixture fills. */
    private static final int WINDOW_DAYS = 30;

    private MainViewController mainController;
    private Parent windowRoot;

    @Override
    public void start(Stage stage) throws Exception {
        seedSampleServers();
        seedSampleSubscriptions();
        // Before the FXML loads: the dashboard picks the history store up in
        // initialize(), so a store registered afterwards would never be seen.
        seedTrafficHistory();
        // The offline test graph answers "unreachable" and "no country"; a
        // working install answers with latencies and flags, which is what the
        // screenshots document. Both doubles go in before the views load,
        // since the dashboard picks its collaborators up in initialize().
        ServiceLocator.register(ServiceReachabilityChecker.class,
                new ServiceReachabilityChecker(ScreenshotGenerator::sampleProbe));
        ServiceLocator.register(CountryResolver.class,
                new SampleCountryResolver(ServiceLocator.get(GeoIpDatabase.class)));
        // And a keychain that works, which the test graph deliberately lacks,
        // so Settings does not say credentials are kept in files.
        ServiceLocator.register(SecretSealer.class, new InMemorySecretSealer());
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
        scene.getStylesheets().setAll(ThemeCss.of("dark"));
        // After the theme, so it wins: the headless text stack draws the
        // system font's glyphs on top of each other, which is why the
        // committed screenshots read "4.0 MƁ/s". See the file.
        scene.getStylesheets().add(
                getClass().getResource("/css/screenshot-font.css").toExternalForm());
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

        // One sample each is enough now that the readout is two numbers: the
        // run of 48 this used to push existed only to give the retired
        // sparkline a series to draw. Both are non-zero on purpose — zero is
        // the muted resting state, and the screenshot documents a busy link.
        setPrivateProperty(traffic, "downloadSpeed",
                property -> ((LongProperty) property).set(3_620_000L));
        setPrivateProperty(traffic, "uploadSpeed",
                property -> ((LongProperty) property).set(284_000L));
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
            var field = declaredField(target.getClass(), fieldName);
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

    /**
     * The field as declared on {@code type} or a superclass. The test graph
     * registers subclasses (NoNetworkTrafficMonitor extends TrafficMonitor),
     * and getDeclaredField on the subclass does not see inherited fields.
     */
    private static java.lang.reflect.Field declaredField(Class<?> type, String fieldName)
            throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                // keep walking up
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static void setPrivateProperty(Object target, String fieldName,
                                           Consumer<Object> mutate) {
        try {
            var field = declaredField(target.getClass(), fieldName);
            field.setAccessible(true);
            mutate.accept(field.get(target));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "field '" + fieldName + "' moved; update the generator", e);
        }
    }

    /** The sample servers' countries, so the flags render without a geo database. */
    private static final Map<String, String> SAMPLE_COUNTRIES = Map.of(
            "185.107.56.12", "NL",
            "88.198.0.1", "DE",
            "172.104.100.1", "JP",
            "95.216.32.11", "FI",
            "128.199.83.144", "SG");

    /** A reachable answer with a plausible latency, as a healthy tunnel gives. */
    private static ServiceReachabilityChecker.ProbeResult sampleProbe(
            HealthCheckTarget target, int httpProxyPort) {
        long millis = "X".equals(target.getName()) ? 513 : 320;
        return new ServiceReachabilityChecker.ProbeResult(
                target.getName(), target.getUrl(), true, millis, "HTTP 204");
    }

    /** Knows the sample servers' countries without a database or a network. */
    private static final class SampleCountryResolver extends CountryResolver {

        SampleCountryResolver(GeoIpDatabase database) {
            super(database);
        }

        @Override
        public Optional<String> countryOf(ServerConfig server) {
            if (server == null || server.getAddress() == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(SAMPLE_COUNTRIES.get(server.getAddress()));
        }

        @Override
        public void resolveAsync(ServerConfig server, Consumer<String> onResolved) {
            countryOf(server).ifPresent(onResolved);
        }

        @Override
        public void warmUp() {
            // Nothing to download.
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

    /**
     * Fills the history with a month of plausible days, through the store's own
     * {@code record} API rather than by writing its JSON: what the screenshot
     * shows is then what the code produces, including the per-server split and
     * the month total the panel computes for itself.
     *
     * <p>The clock is walked forward a day at a time because a day bucket is
     * chosen from {@code LocalDate.now(clock)} -- that is the only seam a
     * caller has for landing samples on a date other than today.</p>
     */
    private void seedTrafficHistory() {
        Path dataDir = Path.of(System.getProperty("vless.data.dir", "target/test-data-dir"));
        WalkingClock clock = new WalkingClock(
                Instant.now().minus(Duration.ofDays(WINDOW_DAYS - 1L)));
        TrafficHistoryStore history = new TrafficHistoryStore(dataDir, clock);
        history.reset();

        List<ServerConfig> servers = ServiceLocator.get(ConfigStore.class).getServers();
        ServerConfig busiest = servers.get(0);
        ServerConfig second = servers.get(1);
        // A fixed shape, not random: the screenshot has to look the same on
        // every regeneration, or every run shows up as a diff in the repo.
        int[] shape = {31, 44, 12, 58, 26, 8, 38, 52, 16, 34, 64, 28, 6, 40, 22,
                       48, 14, 32, 54, 20, 36, 10, 44, 28, 60, 18, 30, 46, 24, 42};
        for (int i = 0; i < shape.length; i++) {
            // Advance between days, never after the last one: the panel reads
            // its window from this same clock, so leaving it on tomorrow
            // shifts every bar one place and ends the row on an empty day.
            if (i > 0) {
                clock.advance(Duration.ofDays(1));
            }
            history.record(busiest, shape[i] * 7_000_000L, shape[i] * 90_000_000L);
            history.record(second, shape[i] * 2_000_000L, shape[i] * 24_000_000L);
        }
        history.flush();
        ServiceLocator.register(TrafficHistoryStore.class, history);
    }

    /** A clock the fixture walks forward, so samples land on past days. */
    private static final class WalkingClock extends Clock {
        private Instant now;

        WalkingClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.systemDefault();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /**
     * Two subscriptions with what a provider reports in
     * {@code subscription-userinfo}, added straight to the service's list: the
     * real add() fetches the URL, and the screenshot runner has no network.
     */
    private void seedSampleSubscriptions() {
        SubscriptionService subscriptions = ServiceLocator.get(SubscriptionService.class);
        subscriptions.getSubscriptions().clear();
        subscriptions.getSubscriptions().addAll(
                subscription("Nordic Nodes", "https://sub.example.net/link/aBcD1234",
                        3, 211_140_000_000L, 18_400_000_000L, 500_000_000_000L, 47),
                subscription("Backup Provider", "https://vpn.example.org/api/v1/client/sub",
                        2, 4_180_000_000L, 610_000_000L, 100_000_000_000L, 12));
    }

    private static Subscription subscription(String name, String url, int servers,
                                             long down, long up, long total, int daysLeft) {
        Subscription sub = new Subscription();
        sub.setName(name);
        sub.setUrl(url);
        // Millis here, seconds in setExpiresAt: the model stores the two in
        // different units, and a seconds value read as millis renders as 1970.
        sub.setLastRefreshedAt(Instant.now().minus(Duration.ofHours(6)).toEpochMilli());
        sub.setDownloadBytes(down);
        sub.setUploadBytes(up);
        sub.setTotalBytes(total);
        sub.setExpiresAt(Instant.now().plus(Duration.ofDays(daysLeft)).getEpochSecond());
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < servers; i++) {
            ids.add("seeded-" + name + "-" + i);
        }
        sub.setServerIds(ids);
        return sub;
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

        // The history panel is collapsed by default, which is what the hero
        // shot above documents; open it through the control a user opens it
        // with, so the second shot cannot show a state the app cannot reach.
        interact(() -> {
            Label sessionTotal = lookup("#sessionTotalLabel").query();
            sessionTotal.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED,
                    0, 0, 0, 0, MouseButton.PRIMARY, 1,
                    false, false, false, false, true, false, false, false, false, false, null));
        });
        capture("traffic-history", controller -> controller.showDashboard());

        capture("servers", controller -> controller.showServers());
        capture("subscriptions", controller -> controller.showSubscriptions());
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
        java.awt.image.BufferedImage framed = inWindowChrome(buffered);
        ImageIO.write(framed, "png", target);
        System.out.println("WROTE " + target.getAbsolutePath()
                + " (" + framed.getWidth() + "x" + framed.getHeight() + ")");
    }

    /**
     * Wraps a snapshot in macOS window chrome: a title bar with the three
     * traffic lights, rounded corners and a hairline edge.
     *
     * <p>Monocle renders into an offscreen buffer and there is no real window
     * to capture, so the scene comes out as a bare rectangle -- which reads as
     * a mockup rather than as an application. The chrome is drawn here instead
     * of being faked inside the scene, so nothing in it can be mistaken for a
     * control the app actually has.</p>
     *
     * <p>Corners are transparent, which is why these are PNGs with alpha: the
     * README renders on both the light and the dark GitHub theme.</p>
     */
    private static java.awt.image.BufferedImage inWindowChrome(
            java.awt.image.BufferedImage content) {
        int bar = (int) (28 * SCALE);
        int corner = (int) (10 * SCALE);
        int dot = (int) (12 * SCALE);
        int gap = (int) (8 * SCALE);
        int inset = (int) (20 * SCALE);

        int width = content.getWidth();
        int height = content.getHeight() + bar;
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(
                width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);

        java.awt.geom.RoundRectangle2D window = new java.awt.geom.RoundRectangle2D.Double(
                0, 0, width, height, corner * 2.0, corner * 2.0);
        g.setClip(window);

        // Chrome one step lighter than the app's own window background, the
        // same relationship a real title bar has to the view under it.
        g.setColor(new java.awt.Color(0x2A2A2D));
        g.fillRect(0, 0, width, bar);
        g.drawImage(content, 0, bar, null);

        g.setColor(new java.awt.Color(0x38383C));
        g.fillRect(0, bar - 1, width, 1);

        int[] lights = {0xFF5F57, 0xFEBC2E, 0x28C840};
        for (int i = 0; i < lights.length; i++) {
            g.setColor(new java.awt.Color(lights[i]));
            g.fillOval(inset + i * (dot + gap), (bar - dot) / 2, dot, dot);
        }

        g.setClip(null);
        g.setColor(new java.awt.Color(0x3A3A3D));
        g.setStroke(new java.awt.BasicStroke(1f));
        g.draw(window);
        g.dispose();
        return out;
    }
}
