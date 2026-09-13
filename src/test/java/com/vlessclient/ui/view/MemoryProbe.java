package com.vlessclient.ui.view;

import com.vlessclient.app.ServiceLocator;
import com.vlessclient.app.ThemeCss;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.ServiceReachabilityChecker;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.TrafficHistoryStore;
import com.vlessclient.service.TrafficMonitor;
import com.vlessclient.testing.FxPulses;
import com.vlessclient.testing.UiTest;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.ObservableList;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * Measures what a connected dashboard costs a long-running instance: bytes
 * allocated and CPU per thread, pulses and the animations that keep them
 * coming, GC activity, heap pools and the process footprint, first with the
 * window shown and then with it hidden the way closing it to the tray hides
 * it. Scene pulses stop with the window; toolkit pulses do not, and they are
 * what a hidden window still costs.
 *
 * <p>Not part of the suite:</p>
 *
 * <pre>JAVA_TOOL_OPTIONS="-Xmx512m" mvn test -Dtest=MemoryProbe -Dtunl.memprobe=true \
 *     -Djacoco.skip=true -Dtunl.memprobe.views=logs,dashboard</pre>
 *
 * <p>JVM options have to go through {@code JAVA_TOOL_OPTIONS}: the pom feeds
 * surefire's argLine from a project property, so {@code -DargLine} on the
 * command line never reaches the forked JVM. The rest is tuned with system
 * properties: {@code tunl.memprobe.views} (views to visit in order, the last
 * stays on screen), {@code tunl.memprobe.prefillLog} (core log lines already in
 * the ring buffer, 1000 like a long-running instance), {@code .settle},
 * {@code .seconds} and {@code .hiddenSeconds} (phase lengths),
 * {@code .tickStress} (traffic samples pushed as fast as possible),
 * {@code .label} and {@code .out} (where the report is appended). With
 * {@code -XX:NativeMemoryTracking=summary} the report ends with HotSpot's
 * native memory account.</p>
 *
 * <p>Rendering is Monocle's software pipeline, so the renderer thread's figures
 * say nothing about ES2 on a Mac; the FX thread's do, because CSS, layout and
 * the app's own listeners run there whatever draws the frame.</p>
 */
@EnabledIfSystemProperty(named = "tunl.memprobe", matches = "true")
@UiTest
public class MemoryProbe extends ApplicationTest {

    /** The window size of the instance measured on 2026-09-13. */
    private static final int WINDOW_WIDTH = 820;
    private static final int WINDOW_HEIGHT = 528;

    private static final long SETTLE_SECONDS = Long.getLong("tunl.memprobe.settle", 20);
    private static final long PHASE_SECONDS = Long.getLong("tunl.memprobe.seconds", 60);

    private final AtomicLong scenePulses = new AtomicLong();
    private FxPulses.Counter toolkitPulses;
    private volatile boolean feeding = true;
    private volatile long fxThreadId = -1;
    private Stage stage;
    private MainViewController mainController;

    @Override
    public void start(Stage stage) throws Exception {
        this.stage = stage;
        fxThreadId = Thread.currentThread().threadId();

        // The measured instance: health probes every 15 s, history left open.
        AppSettings settings = ServiceLocator.get(AppSettings.class);
        settings.setHealthCheckIntervalSeconds(15);
        settings.setHealthCheckEnabled(true);
        settings.setTrafficHistoryExpanded(true);

        seedServers();
        seedTrafficHistory();
        ServiceLocator.register(ServiceReachabilityChecker.class,
                new ServiceReachabilityChecker((target, port) ->
                        new ServiceReachabilityChecker.ProbeResult(
                                target.getName(), target.getUrl(), true, 120, "HTTP 204")));
        SingBoxEngine engine = new SingBoxEngine(Path.of("/usr/local/bin/sing-box"));
        ServiceLocator.register(SingBoxEngine.class, engine);
        // A long-running instance has a full ring buffer: every new line then
        // also drops the oldest one, which is the case the logs view pays for.
        int prefill = Integer.getInteger("tunl.memprobe.prefillLog", 1000);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < prefill; i++) {
            lines.add(coreLogLine(i));
        }
        engine.getLogLines().setAll(lines);

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainView.fxml"));
        Parent root = loader.load();
        mainController = loader.getController();
        Scene scene = new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT);
        scene.getStylesheets().setAll(ThemeCss.of("light"));
        scene.addPostLayoutPulseListener(scenePulses::incrementAndGet);
        toolkitPulses = FxPulses.countPulses();
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void measure() throws Exception {
        StringBuilder report = new StringBuilder();
        report.append(String.format(Locale.ROOT, "%n##### MemoryProbe [%s] java %s%n  args: %s%n",
                System.getProperty("tunl.memprobe.label", "unlabelled"), Runtime.version(),
                ManagementFactory.getRuntimeMXBean().getInputArguments()));

        interact(() -> mainController.showDashboard());
        interact(this::connect);
        startFeeders();
        String views = showViews();
        long stressTicks = Long.getLong("tunl.memprobe.tickStress", 0);
        if (stressTicks > 0) {
            report.append(tickStress(stressTicks));
        }
        sleep(SETTLE_SECONDS * 1000);
        boolean historyOpen = lookup("#trafficHistoryPanel").tryQuery()
                .map(javafx.scene.Node::isVisible).orElse(false);
        report.append("  views: ").append(views)
                .append("; history panel visible: ").append(historyOpen).append('\n');

        report.append(measurePhase("visible, connected", PHASE_SECONDS));

        interact(() -> stage.hide());
        sleep(SETTLE_SECONDS * 1000);
        report.append(measurePhase("hidden, connected",
                Long.getLong("tunl.memprobe.hiddenSeconds", PHASE_SECONDS)));

        System.gc();
        sleep(1000);
        System.gc();
        sleep(1000);
        report.append("== after System.gc() while hidden\n  ")
                .append(memoryLine()).append("\n  ").append(footprint()).append('\n')
                .append(nativeMemory());

        feeding = false;
        String text = report.toString();
        System.out.println(text);
        Path out = Path.of(System.getProperty("tunl.memprobe.out", "target/memprobe.txt"));
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, text, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // ---------------------------------------------------------------- load

    /** Flips the engine to CONNECTED at its source, as ScreenshotGenerator does. */
    @SuppressWarnings("unchecked")
    private void connect() {
        ConfigStore store = ServiceLocator.get(ConfigStore.class);
        ServerConfig active = store.getServers().get(0);
        setField(dashboardController(), "activeServer", active);
        withField(ServiceLocator.get(SingBoxEngine.class), "connectionState",
                wrapper -> ((ReadOnlyObjectWrapper<ConnectionState>) wrapper)
                        .set(ConnectionState.CONNECTED));
    }

    /**
     * One {@code /traffic} line a second, as the core streams it, and a core
     * log line every 450 ms — the rate measured on the live instance (66 lines
     * in 30 s with 73 open connections).
     */
    private void startFeeders() throws ReflectiveOperationException {
        TrafficMonitor traffic = ServiceLocator.get(TrafficMonitor.class);
        Method processLine = TrafficMonitor.class.getDeclaredMethod(
                "processTrafficLine", String.class);
        processLine.setAccessible(true);
        Thread.ofPlatform().daemon().name("memprobe-traffic").start(() -> {
            long tick = 0;
            while (feeding) {
                long down = 40_000 + (tick * 7_919) % 900_000;
                long up = 6_000 + (tick * 104_729) % 90_000;
                try {
                    processLine.invoke(traffic, "{\"up\":" + up + ",\"down\":" + down + "}");
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
                tick++;
            }
        });

        ObservableList<String> logLines = ServiceLocator.get(SingBoxEngine.class).getLogLines();
        Thread.ofPlatform().daemon().name("memprobe-log").start(() -> {
            long n = 1_000_000;
            while (feeding) {
                String line = coreLogLine(n);
                Platform.runLater(() -> {
                    logLines.add(line);
                    while (logLines.size() > 1000) {
                        logLines.removeFirst();
                    }
                });
                n++;
                try {
                    Thread.sleep(450);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }

    /** Visits the views named in {@code tunl.memprobe.views}; the last one stays on screen. */
    private String showViews() {
        String views = System.getProperty("tunl.memprobe.views", "dashboard");
        for (String view : views.split(",")) {
            interact(() -> {
                switch (view.strip()) {
                    case "servers" -> mainController.showServers();
                    case "subscriptions" -> mainController.showSubscriptions();
                    case "routing" -> mainController.showRouting();
                    case "logs" -> mainController.showLogs();
                    case "settings" -> mainController.showSettings();
                    default -> mainController.showDashboard();
                }
            });
            sleep(1500);
        }
        return views;
    }

    /**
     * Pushes traffic samples as fast as the FX thread takes them, in ten equal
     * batches, and reports the FX thread's allocation per sample for each. A
     * figure that climbs from batch to batch means the per-sample path gets
     * more expensive with every sample it has already handled.
     */
    private String tickStress(long ticks) throws Exception {
        TrafficMonitor traffic = ServiceLocator.get(TrafficMonitor.class);
        Method processLine = TrafficMonitor.class.getDeclaredMethod(
                "processTrafficLine", String.class);
        processLine.setAccessible(true);
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        StringBuilder out = new StringBuilder(
                "== traffic sample stress: FX-thread KB per sample, by batch\n  ");
        long batch = Math.max(1, ticks / 10);
        for (int b = 0; b < 10; b++) {
            org.testfx.util.WaitForAsyncUtils.waitForFxEvents();
            long before = threads.getThreadAllocatedBytes(fxThreadId);
            long started = System.nanoTime();
            for (long i = 0; i < batch; i++) {
                processLine.invoke(traffic,
                        "{\"up\":" + (1_000 + i) + ",\"down\":" + (5_000 + i) + "}");
                if (i % 200 == 199) {
                    org.testfx.util.WaitForAsyncUtils.waitForFxEvents();
                }
            }
            org.testfx.util.WaitForAsyncUtils.waitForFxEvents();
            long after = threads.getThreadAllocatedBytes(fxThreadId);
            out.append(String.format(Locale.ROOT, "%.2fKB/%.0fms  ",
                    (after - before) / 1024.0 / batch, (System.nanoTime() - started) / 1e6));
        }
        return out.append('\n').toString();
    }

    /** The three shapes that make up an info-level core log: DNS, inbound, outbound. */
    private static String coreLogLine(long n) {
        String stamp = String.format(Locale.ROOT, "+0200 2026-09-13 17:%02d:%02d INFO [%d %dms] ",
                (n / 60) % 60, n % 60, 3_911_534_071L + n, n % 400);
        return switch ((int) (n % 3)) {
            case 0 -> stamp + "dns: exchanged A www.example" + (n % 97) + ".com. 300 IN A 104.18."
                    + (n % 250) + "." + (n % 199);
            case 1 -> stamp + "inbound/tun[tun-in]: inbound connection from 172.19.0.1:"
                    + (50_000 + n % 15_000);
            default -> stamp + "outbound/vless[srv-08c49d80-a91c-4a75-b4d8-e446b7b714cd]: "
                    + "outbound connection to 172.64.155." + (n % 250) + ":443";
        };
    }

    private void seedServers() {
        ConfigStore store = ServiceLocator.get(ConfigStore.class);
        store.getServers().clear();
        store.addServer(server("Amsterdam 01", "185.107.56.12", 443));
        store.addServer(server("Frankfurt 02", "88.198.0.1", 8443));
    }

    private static ServerConfig server(String name, String address, int port) {
        ServerConfig config = new ServerConfig();
        config.setName(name);
        config.setAddress(address);
        config.setPort(port);
        config.setProtocol(Protocol.VLESS);
        return config;
    }

    /** A month of days for two servers, so the open panel has thirty bars to paint. */
    private void seedTrafficHistory() throws Exception {
        Path dir = Files.createTempDirectory("memprobe-history");
        WalkingClock clock = new WalkingClock(Instant.now().minus(Duration.ofDays(29)));
        TrafficHistoryStore history = new TrafficHistoryStore(dir, clock);
        List<ServerConfig> servers = ServiceLocator.get(ConfigStore.class).getServers();
        for (int day = 0; day < 30; day++) {
            if (day > 0) {
                clock.advance(Duration.ofDays(1));
            }
            history.record(servers.get(0), (day + 3) * 7_000_000L, (day + 3) * 90_000_000L);
            history.record(servers.get(1), (day + 1) * 2_000_000L, (day + 1) * 24_000_000L);
        }
        history.flush();
        ServiceLocator.register(TrafficHistoryStore.class, history);
    }

    // ----------------------------------------------------------- measuring

    private record Sample(long nanos, long fxAlloc, long rendererAlloc, long totalAlloc,
                          long fxCpu, long rendererCpu, long processCpu,
                          Map<String, long[]> gc, long pulses, long toolkitPulses) {
    }

    private Sample sample() {
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        var os = (com.sun.management.OperatingSystemMXBean)
                ManagementFactory.getOperatingSystemMXBean();
        long renderer = rendererThreadId();
        Map<String, long[]> gc = new java.util.TreeMap<>();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            gc.put(bean.getName(), new long[] {bean.getCollectionCount(), bean.getCollectionTime()});
        }
        return new Sample(System.nanoTime(),
                threads.getThreadAllocatedBytes(fxThreadId),
                renderer < 0 ? 0 : threads.getThreadAllocatedBytes(renderer),
                threads.getTotalThreadAllocatedBytes(),
                threads.getThreadCpuTime(fxThreadId),
                renderer < 0 ? 0 : threads.getThreadCpuTime(renderer),
                os.getProcessCpuTime(), gc, scenePulses.get(), toolkitPulses.pulses());
    }

    private String measurePhase(String name, long phaseSeconds) {
        Sample a = sample();
        sleep(phaseSeconds * 1000);
        Sample b = sample();
        double seconds = (b.nanos() - a.nanos()) / 1e9;
        double fx = mb(b.fxAlloc() - a.fxAlloc()) / seconds;
        double renderer = mb(b.rendererAlloc() - a.rendererAlloc()) / seconds;
        double total = mb(b.totalAlloc() - a.totalAlloc()) / seconds;
        StringBuilder gc = new StringBuilder();
        b.gc().forEach((collector, now) -> {
            long[] then = a.gc().getOrDefault(collector, new long[] {0, 0});
            gc.append(String.format(Locale.ROOT, " [%s: %d, %d ms]",
                    collector, now[0] - then[0], now[1] - then[1]));
        });
        return String.format(Locale.ROOT,
                "== %s (%.0f s)%n"
                        + "  alloc MB/s   fx=%.3f renderer=%.3f other=%.3f total=%.3f%n"
                        + "  cpu %%        fx=%.2f renderer=%.2f process=%.2f%n"
                        + "  pulses/s     scene=%.1f toolkit=%.1f; animations running: %d%n"
                        + "  gc:%s%n"
                        + "  %s%n  %s%n",
                name, seconds, fx, renderer, total - fx - renderer, total,
                percent(b.fxCpu() - a.fxCpu(), b.nanos() - a.nanos()),
                percent(b.rendererCpu() - a.rendererCpu(), b.nanos() - a.nanos()),
                percent(b.processCpu() - a.processCpu(), b.nanos() - a.nanos()),
                (b.pulses() - a.pulses()) / seconds,
                (b.toolkitPulses() - a.toolkitPulses()) / seconds,
                FxPulses.runningAnimations(), gc, memoryLine(), footprint());
    }

    private static long rendererThreadId() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("QuantumRenderer"))
                .mapToLong(Thread::threadId).findFirst().orElse(-1);
    }

    private static String memoryLine() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        StringBuilder line = new StringBuilder(String.format(Locale.ROOT,
                "heap used=%.1fM committed=%.1fM max=%.0fM",
                mb(heap.getUsed()), mb(heap.getCommitted()), mb(heap.getMax())));
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage usage = pool.getUsage();
            line.append(String.format(Locale.ROOT, "%n    %-34s used=%7.1fM committed=%7.1fM",
                    pool.getName(), mb(usage.getUsed()), mb(usage.getCommitted())));
        }
        return line.toString();
    }

    /** The categories macOS charges the process for, largest first. */
    private static String footprint() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            return "footprint: n/a off macOS";
        }
        try {
            Process process = new ProcessBuilder("footprint", "-p",
                    Long.toString(ProcessHandle.current().pid()))
                    .redirectErrorStream(true).start();
            List<String> kept = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int categories = 0;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("Footprint:")) {
                        kept.add(line.strip());
                    } else if (categories < 8 && line.matches("\\s*[0-9.]+ [KMG]?B\\s.*")) {
                        kept.add(line.strip());
                        categories++;
                    }
                }
            }
            process.waitFor();
            return String.join("\n    ", kept);
        } catch (Exception e) {
            return "footprint failed: " + e;
        }
    }

    /**
     * HotSpot's own account of its native memory, when the JVM was started with
     * {@code -XX:NativeMemoryTracking=summary}: the committed size per category.
     */
    private static String nativeMemory() {
        if (ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .noneMatch(arg -> arg.startsWith("-XX:NativeMemoryTracking"))) {
            return "";
        }
        Path jcmd = Path.of(ProcessHandle.current().info().command().orElse("java"))
                .resolveSibling("jcmd");
        try {
            Process process = new ProcessBuilder(jcmd.toString(),
                    Long.toString(ProcessHandle.current().pid()),
                    "VM.native_memory", "summary", "scale=MB")
                    .redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder("== native memory tracking (committed)\n");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.strip();
                    if (trimmed.startsWith("Total:") || trimmed.startsWith("-")) {
                        out.append("  ").append(trimmed.replaceAll("\\s+", " ")).append('\n');
                    }
                }
            }
            process.waitFor();
            return out.toString();
        } catch (Exception e) {
            return "native memory tracking failed: " + e + "\n";
        }
    }

    private static double mb(long bytes) {
        return bytes / (1024.0 * 1024.0);
    }

    private static double percent(long cpuNanos, long wallNanos) {
        return wallNanos <= 0 ? 0 : 100.0 * cpuNanos / wallNanos;
    }

    // ------------------------------------------------------------- plumbing

    private Object dashboardController() {
        return ((Map<?, ?>) readField(mainController, "controllerCache")).get("DashboardView");
    }

    private static Object readField(Object target, String name) {
        try {
            Field field = declaredField(target.getClass(), name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("field '" + name + "' moved; update the probe", e);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = declaredField(target.getClass(), name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("field '" + name + "' moved; update the probe", e);
        }
    }

    private static void withField(Object target, String name, Consumer<Object> use) {
        use.accept(readField(target, name));
    }

    private static Field declaredField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                // keep walking up
            }
        }
        throw new NoSuchFieldException(name);
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
}
