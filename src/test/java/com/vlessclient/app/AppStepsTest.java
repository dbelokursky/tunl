package com.vlessclient.app;

import com.vlessclient.platform.Autostart;
import com.vlessclient.platform.PlatformPaths;
import com.vlessclient.service.TrayIconService;
import com.vlessclient.testing.Await;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The app's startup and shutdown: the order of their steps, and what a step
 * that fails does to the rest.
 *
 * <p>Both lived inline in {@link VlessClientApp}, which no test ran, and
 * the order had been broken there before: the core a killed run left behind
 * has to be ended before the service graph exists (#285).</p>
 */
class AppStepsTest {

    private static final String EARLIER_SESSION_CONFIGS = "earlier session configs";

    private final List<String> ran = new ArrayList<>();

    @Test
    void aStepThatFailsIsLoggedAndTheRestRun() {
        AppSteps.run("startup", List.of(
                AppSteps.step("first", () -> ran.add("first")),
                AppSteps.step("broken", () -> {
                    throw new IllegalStateException("networksetup said something new");
                }),
                AppSteps.step("last", () -> ran.add("last"))));

        assertThat(ran).as("a cleanup that failed must not keep the app from starting")
                .containsExactly("first", "last");
    }

    /** What the first touch of AWT throws on a host without a display. */
    @Test
    void aStepThatCannotLoadAClassIsLoggedAndTheRestRun() {
        AppSteps.run("startup", List.of(
                AppSteps.step("dock icon", () -> {
                    throw new ExceptionInInitializerError("no display");
                }),
                AppSteps.step("last", () -> ran.add("last"))));

        assertThat(ran).containsExactly("last");
    }

    @Test
    void aRequiredStepThatFailsEndsTheRun() {
        List<AppSteps.Step> steps = List.of(
                AppSteps.required("services", () -> {
                    throw new IllegalStateException("no data directory");
                }),
                AppSteps.step("after", () -> ran.add("after")));

        assertThatThrownBy(() -> AppSteps.run("startup", steps))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("no data directory");
        assertThat(ran).as("nothing after the service graph can work without it").isEmpty();
    }

    @Test
    void theStartupEndsALeftoverCoreBeforeTheServicesAndClearsItsProxyAfter() {
        List<String> names = names(new VlessClientApp().startupSteps());

        assertThat(names.indexOf("leftover core"))
                .as("before the graph, whose MCP server would race that core for its ports")
                .isNotNegative()
                .isLessThan(names.indexOf("services"));
        assertThat(names.indexOf("stale system proxy"))
                .as("after the graph, whose engine clears it")
                .isGreaterThan(names.indexOf("services"));
        assertThat(new VlessClientApp().startupSteps())
                .as("the only step the app cannot start without")
                .filteredOn(AppSteps.Step::required)
                .extracting(AppSteps.Step::name)
                .containsExactly("services");
    }

    @Test
    void theShutdownFreesTheMcpPortBeforeTheTrayAndStopsTheServicesLast() {
        List<String> names = names(new VlessClientApp().shutdownSteps());

        assertThat(names.indexOf("MCP server"))
                .as("before the tray's AWT teardown, which can use its whole timeout")
                .isNotNegative()
                .isLessThan(names.indexOf("tray icon"));
        assertThat(names).last().isEqualTo("services");
        assertThat(names).as("a launch is no longer answered while the app quits")
                .first().isEqualTo("single instance");
        assertThat(new VlessClientApp().shutdownSteps())
                .as("nothing may keep the app from quitting")
                .noneMatch(AppSteps.Step::required);
    }

    /**
     * The login item step ran in init(), before the window: on Windows two
     * reg.exe processes, at login of all times, and up to their whole timeout
     * when reg hangs. The tunl links step after it was moved off for the same
     * reason; nothing at startup waits for either.
     */
    @Test
    void theLoginItemIsRefreshedWithoutHoldingTheWindowBack() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch refreshed = new CountDownLatch(1);
        Autostart previous = ServiceLocator.find(Autostart.class).orElse(null);
        ServiceLocator.register(Autostart.class, new Autostart() {
            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public void setEnabled(boolean enabled) {
            }

            @Override
            public void refresh() {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                refreshed.countDown();
            }
        });
        try {
            AppSteps.Step loginItem = new VlessClientApp().startupSteps().stream()
                    .filter(step -> step.name().equals("login item"))
                    .findFirst().orElseThrow();

            loginItem.action().run();

            assertThat(refreshed.getCount())
                    .as("the step returned while the refresh was still waiting on reg")
                    .isEqualTo(1);
            release.countDown();
            assertThat(refreshed.await(10, TimeUnit.SECONDS))
                    .as("the refresh still ran").isTrue();
        } finally {
            release.countDown();
            if (previous != null) {
                ServiceLocator.register(Autostart.class, previous);
            } else {
                ServiceLocator.remove(Autostart.class);
            }
        }
    }

    /**
     * The plain-text session configurations an earlier release left behind
     * are removed at startup, before the graph, whose keychain read can wait
     * on the user, and without holding the window back while the removal
     * lists a temp dir that on Windows can hold tens of thousands of files.
     */
    @Test
    void earlierReleasesSessionConfigsAreRemovedWithoutHoldingTheStartBack(@TempDir Path dataDir)
            throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch removed = new CountDownLatch(1);
        AtomicReference<Thread> removedOn = new AtomicReference<>();
        VlessClientApp app = new VlessClientApp(() -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            removedOn.set(Thread.currentThread());
            removed.countDown();
        });
        List<String> names = names(app.startupSteps());
        assertThat(names.indexOf(EARLIER_SESSION_CONFIGS)).isNotNegative()
                .isLessThan(names.indexOf("services"));
        SingleInstance held = SingleInstance.acquire(dataDir).orElseThrow();
        try {
            step(app, EARLIER_SESSION_CONFIGS).action().run();

            assertThat(removed.getCount())
                    .as("the step returned while the removal was still listing").isEqualTo(1);
            release.countDown();
            assertThat(removed.await(10, TimeUnit.SECONDS)).as("the removal still ran").isTrue();
            assertThat(removedOn.get()).isNotSameAs(Thread.currentThread());
        } finally {
            release.countDown();
            held.close();
        }
    }

    /**
     * Only the copy that holds the data dir removes them. Without the lock
     * another copy may be running, of an earlier release perhaps, and still
     * writing where the removal looks.
     */
    @Test
    void aCopyWithoutTheLockLeavesEarlierReleasesSessionConfigsAlone()
            throws InterruptedException {
        assertThat(SingleInstance.current()).as("this test's copy holds no data dir").isEmpty();
        CountDownLatch removed = new CountDownLatch(1);

        step(new VlessClientApp(removed::countDown), EARLIER_SESSION_CONFIGS).action().run();

        assertThat(removed.await(1, TimeUnit.SECONDS)).as("removed without the lock").isFalse();
    }

    /** The startup's own removal: a configuration a crash under 1.24 left in the temp dir. */
    @Test
    void theStartupRemovesAConfigurationA124CrashLeftInTheTempDir(@TempDir Path lockDir)
            throws Exception {
        Path dataDir = Files.createDirectories(PlatformPaths.current().dataDir());
        String cache = JsonMapper.builder().build().writeValueAsString(
                dataDir.resolve("cache").resolve("sing-box-tun.db").toString());
        Path left = Files.writeString(Files.createTempFile(
                        Path.of(System.getProperty("java.io.tmpdir")), "singbox-", ".json"),
                "{\"experimental\":{\"cache_file\":{\"enabled\":true,\"path\":" + cache + "}}}");
        SingleInstance held = SingleInstance.acquire(lockDir).orElseThrow();
        try {
            step(new VlessClientApp(), EARLIER_SESSION_CONFIGS).action().run();

            Await.until("the configuration " + left + " is removed", () -> Files.notExists(left),
                    Duration.ofSeconds(10));
        } finally {
            held.close();
            Files.deleteIfExists(left);
        }
    }

    /** With no core installed, the lookup of the engine threw, and there was no tray. */
    @Test
    void theTrayIsMadeFromTheServicesBeforeAnyCoreIsInstalled() {
        UiTestServices.initialize();

        TrayIconService tray = VlessClientApp.newTray(null);

        assertThat(tray).isNotNull();
        assertThat(tray.isShowing()).as("not on the system tray until installed").isFalse();
    }

    private static List<String> names(List<AppSteps.Step> steps) {
        return steps.stream().map(AppSteps.Step::name).toList();
    }

    private static AppSteps.Step step(VlessClientApp app, String name) {
        return app.startupSteps().stream()
                .filter(step -> step.name().equals(name))
                .findFirst().orElseThrow();
    }
}
