package com.vlessclient.app;

import com.vlessclient.service.ConnectionService;
import com.vlessclient.service.SingBoxEngine;
import com.vlessclient.service.mcp.AppControlService;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The run has one engine from the start, whether a core is installed or not.
 *
 * <p>The graph used to register an engine only when it found a core, and the
 * installer registered a new one once it had downloaded one. Everything bound
 * to the first had to be moved to the second by hand: the connect flow, the
 * MCP facade and its log bridge, the tray, the update check and the log page.
 * The tray was missed once, and its icon stopped following the tunnel; the
 * update check once, and no connect checked for updates for the rest of the
 * run; and with no engine to look up, the tray was not created at all.</p>
 */
class OneEnginePerRunTest {

    /** Leaves the graph the UI tests build, which a later test in this JVM may expect. */
    @AfterEach
    void restoreTheTestGraph() {
        UiTestServices.initialize();
    }

    @Test
    void theGraphHasAnEngineBeforeAnyCoreIsInstalled() {
        buildTheGraph();

        SingBoxEngine engine = ServiceLocator.find(SingBoxEngine.class).orElse(null);

        // The tests' install directory holds no core, and they skip the one
        // the jar carries, so this is a run that has not installed one yet.
        assertThat(engine).as("the run's engine, with or without a core").isNotNull();
        assertThat(ServiceLocator.get(ConnectionService.class).getEngine()).isSameAs(engine);
    }

    @Test
    void installingTheCoreKeepsTheEngineEveryPartOfTheAppHas() {
        buildTheGraph();
        SingBoxEngine engine = ServiceLocator.get(SingBoxEngine.class);
        ConnectionService connections = ServiceLocator.get(ConnectionService.class);
        AppControlService control = ServiceLocator.get(AppControlService.class);

        ServiceLocator.installCore(Path.of("target", "no-such-sing-box"));

        assertThat(ServiceLocator.get(SingBoxEngine.class)).isSameAs(engine);
        assertThat(engine.hasBinary()).isTrue();
        assertThat(connections.getEngine()).isSameAs(engine);
        assertThat(ServiceLocator.get(ConnectionService.class)).isSameAs(connections);
        assertThat(ServiceLocator.get(AppControlService.class)).isSameAs(control);
        assertThat(ServiceLocator.getSingBoxPath())
                .isEqualTo(Path.of("target", "no-such-sing-box").toString());
    }

    /** The real graph, as the app builds it, minus its background work. */
    private static void buildTheGraph() {
        ServiceLocator.shutdown();
        ServiceLocator.initialize(ServiceLocator.StartupMode.TEST);
    }
}
