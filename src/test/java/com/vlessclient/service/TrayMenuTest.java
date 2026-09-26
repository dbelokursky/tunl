package com.vlessclient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.I18n;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.outbound.OutboundTags;
import com.vlessclient.testing.TestServers;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import javafx.beans.property.SimpleStringProperty;
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
        TrayIconService tray = new TrayIconService(SingBoxEngine.withoutCore(), null, null,
                null, null, null);
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

        TrayIconService.ServerMenu menu = TrayIconService.serverMenu(servers, null);

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

        TrayIconService.ServerMenu menu = TrayIconService.serverMenu(servers, null);

        assertThat(menu.items()).extracting(TrayIconService.MenuServer::label)
                .containsExactly("Tokyo", "Frankfurt");
        assertThat(menu.more()).isZero();
        assertThat(TrayIconService.serverMenu(new ArrayList<>(servers), null))
                .as("the same list makes an equal menu, so nothing is rebuilt")
                .isEqualTo(menu);
    }

    /**
     * In the Fastest mode the core moves traffic on its own, and the tick
     * stayed on the server the user had picked, which the tunnel might not be
     * using at all. The tick now means "selected", and the server the core
     * picked is named as the one in use now, kept in the menu like the
     * selected one wherever it is in the list.
     */
    @Test
    void theCoresOwnPickIsMarkedAndKeptWhereverItIs() {
        List<ServerConfig> servers = IntStream.range(0, 300)
                .mapToObj(i -> server("id-" + i, "Server " + i, i == 3))
                .toList();

        TrayIconService.ServerMenu menu =
                TrayIconService.serverMenu(servers, OutboundTags.server("id-280"));

        assertThat(menu.items()).hasSize(TrayIconService.MAX_MENU_SERVERS);
        assertThat(menu.items()).filteredOn(TrayIconService.MenuServer::active)
                .singleElement()
                .extracting(TrayIconService.MenuServer::id)
                .isEqualTo("id-3");
        assertThat(menu.items()).filteredOn(TrayIconService.MenuServer::now)
                .singleElement()
                .extracting(TrayIconService.MenuServer::id)
                .isEqualTo("id-280");
        assertThat(menu.more()).isEqualTo(300 - TrayIconService.MAX_MENU_SERVERS);
    }

    @Test
    void anItemSaysWhetherItIsSelectedAndWhetherTrafficGoesThroughItNow() {
        assertThat(TrayIconService.itemLabel(
                new TrayIconService.MenuServer("a", "Tokyo", true, false)))
                .isEqualTo("✓ Tokyo");
        assertThat(TrayIconService.itemLabel(
                new TrayIconService.MenuServer("b", "Frankfurt", false, true)))
                .isEqualTo("    " + I18n.get("tray.servers.now", "Frankfurt"));
    }

    @Test
    void aNewPickByTheCoreReachesTheMenu() {
        SimpleStringProperty pick = new SimpleStringProperty();
        List<Runnable> awtQueue = new ArrayList<>();
        TrayIconService tray = new TrayIconService(SingBoxEngine.withoutCore(), null, null,
                null, pick, null);
        tray.setAwtInvoker(awtQueue::add);
        tray.followCorePick();

        pick.set("srv-b");

        assertThat(tray.corePick()).isEqualTo("srv-b");
        assertThat(awtQueue).as("the menu is built again for it").hasSize(1);
    }

    private static ServerConfig server(String id, String name, boolean active) {
        return TestServers.server()
                .id(id)
                .name(name)
                .address(name.toLowerCase(java.util.Locale.ROOT).replace(' ', '-') + ".example")
                .active(active)
                .build();
    }
}
