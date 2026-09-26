package com.vlessclient.app;

import com.vlessclient.service.TrayIconService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

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
        assertThat(new VlessClientApp().shutdownSteps())
                .as("nothing may keep the app from quitting")
                .noneMatch(AppSteps.Step::required);
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
}
