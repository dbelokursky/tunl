package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.ServerConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * What the tray does when a subscription brings hundreds of servers.
 *
 * <p>Every change of the server list queued a refresh on the AWT thread, and
 * every refresh removed the servers submenu and built it again with a native
 * item per server. A batch of N changes built N menus of N items: for a
 * refresh of 300 servers about 7 seconds on macOS, where the AWT thread is the
 * main thread the JavaFX window runs on too, and a click on the tray waited
 * behind it. It happened on every hourly refresh, changed or not.</p>
 */
class TrayMenuTest {

    /** Headless: the constructor creates no AWT object, and install() is never called. */
    private static TrayIconService tray(List<Runnable> awtQueue) {
        TrayIconService tray = new TrayIconService(() -> null, null, null, null, null);
        tray.setAwtInvoker(awtQueue::add);
        return tray;
    }

    @Test
    void aBurstOfChangesQueuesOneRefresh() {
        List<Runnable> awtQueue = new ArrayList<>();
        TrayIconService tray = tray(awtQueue);

        for (int i = 0; i < 300; i++) {
            tray.requestRefresh();
        }
        assertThat(awtQueue).as("refreshes queued for 300 changes").hasSize(1);

        awtQueue.getFirst().run();
        tray.requestRefresh();
        assertThat(awtQueue).as("a change after the refresh ran queues another").hasSize(2);
    }

    @Test
    void theMenuKeepsThePickedServerAndCountsTheRest() {
        List<ServerConfig> servers = IntStream.range(0, 300)
                .mapToObj(i -> server("id-" + i, "Server " + i, i == 250))
                .toList();

        TrayIconService.ServerMenu menu = TrayIconService.serverMenu(servers);

        assertThat(menu.items()).hasSize(TrayIconService.MAX_MENU_SERVERS);
        assertThat(menu.items()).filteredOn(TrayIconService.MenuServer::active)
                .singleElement()
                .extracting(TrayIconService.MenuServer::id)
                .isEqualTo("id-250");
        assertThat(menu.more()).isEqualTo(300 - TrayIconService.MAX_MENU_SERVERS);
    }

    @Test
    void aShortListIsShownWhole() {
        List<ServerConfig> servers = List.of(
                server("a", "Tokyo", true), server("b", "Frankfurt", false));

        TrayIconService.ServerMenu menu = TrayIconService.serverMenu(servers);

        assertThat(menu.items()).extracting(TrayIconService.MenuServer::label)
                .containsExactly("Tokyo", "Frankfurt");
        assertThat(menu.more()).isZero();
        assertThat(TrayIconService.serverMenu(new ArrayList<>(servers)))
                .as("the same list makes an equal menu, so nothing is rebuilt")
                .isEqualTo(menu);
    }

    private static ServerConfig server(String id, String name, boolean active) {
        ServerConfig server = new ServerConfig();
        server.setId(id);
        server.setName(name);
        server.setAddress(name.toLowerCase(java.util.Locale.ROOT).replace(' ', '-') + ".example");
        server.setActive(active);
        return server;
    }
}
