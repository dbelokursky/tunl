package com.vlessclient.app;

import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.TrafficHistoryStore;
import java.nio.file.Path;
import java.time.Clock;

/**
 * A JVM for {@code SigtermSavesStateTest} to stop with SIGTERM: a traffic
 * history with bytes counted and not yet flushed, and the app's own shutdown
 * hook. It says "ready" and then waits to be stopped.
 */
public final class ShutdownHookProbe {

    private ShutdownHookProbe() {
    }

    public static void main(String[] args) throws InterruptedException {
        TrafficHistoryStore history = new TrafficHistoryStore(Path.of(args[0]),
                Clock.systemDefaultZone());
        ServiceLocator.register(TrafficHistoryStore.class, history);
        ServerConfig server = new ServerConfig();
        server.setId("sigterm-probe");
        server.setName("Probe");
        history.record(server, 4_000, 60_000);
        VlessClientApp.installShutdownHook();
        System.out.println("ready");
        System.out.flush();
        Thread.sleep(60_000);
    }
}
