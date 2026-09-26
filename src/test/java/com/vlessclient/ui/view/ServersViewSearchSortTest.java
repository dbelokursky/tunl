package com.vlessclient.ui.view;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.Protocol;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.LatencyTester;
import com.vlessclient.service.TestConfigStores;
import com.vlessclient.testing.TestServers;
import com.vlessclient.testing.UiTest;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Search, sort, and what a selection does, for a list long enough that
 * scrolling is not an answer.
 */
@UiTest
public class ServersViewSearchSortTest extends ApplicationTest {

    @TempDir
    static Path tempDir;

    private ConfigStore store;
    private StubTester tester;

    /** Serves canned measurements so the latency sort has something to order. */
    private static final class StubTester extends LatencyTester {
        private final Map<String, Result> canned = new HashMap<>();

        @Override
        public Optional<Result> lastResult(String serverId) {
            return Optional.ofNullable(canned.get(serverId));
        }
    }

    @Override
    public void start(Stage stage) throws Exception {
        store = TestConfigStores.at(tempDir.resolve("data"));
        tester = new StubTester();
        ServiceLocator.register(ConfigStore.class, store);
        ServiceLocator.register(LatencyTester.class, tester);

        // TestFX rebuilds the stage per test method, and a store rooted at the
        // same directory reloads what the previous method saved there.
        store.getServers().clear();

        store.addServer(server("Netherlands 01", "185.107.56.12", 443, Protocol.VLESS));
        store.addServer(server("Germany 02", "45.86.230.9", 8443, Protocol.TROJAN));
        store.addServer(server("Japan 07", "103.75.117.4", 2087, Protocol.VMESS));
        store.addServer(server("Finland WG", "95.216.32.11", 51820, Protocol.WIREGUARD));

        List<ServerConfig> all = store.getServers();
        tester.canned.put(all.get(1).getId(), new LatencyTester.Result(212, true));
        tester.canned.put(all.get(2).getId(), new LatencyTester.Result(48, true));
        tester.canned.put(all.get(3).getId(), new LatencyTester.Result(-1, false));
        // all.get(0) stays unmeasured on purpose.

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/ServersView.fxml"));
        Parent root = loader.load();
        stage.setScene(new Scene(root, 980, 560));
        stage.show();
    }

    @BeforeEach
    void resetControls() {
        search("");
        chooseSort("CONFIGURED");
    }

    private static ServerConfig server(String name, String address, int port, Protocol proto) {
        return TestServers.server()
                .name(name)
                .address(address)
                .port(port)
                .protocol(proto)
                .build();
    }

    @SuppressWarnings("unchecked")
    private ListView<ServerConfig> list() {
        return lookup("#serverListView").query();
    }

    private List<String> visibleNames() {
        return list().getItems().stream().map(ServerConfig::getName).toList();
    }

    private void search(String text) {
        interact(() -> ((TextField) lookup("#searchField").query()).setText(text));
    }

    @SuppressWarnings("unchecked")
    private void chooseSort(String constantName) {
        interact(() -> {
            ComboBox<Object> combo = (ComboBox<Object>) lookup("#sortCombo").query();
            for (Object option : combo.getItems()) {
                if (option.toString().equals(constantName)) {
                    combo.setValue(option);
                    return;
                }
            }
            throw new AssertionError("no sort option named " + constantName);
        });
    }

    private String activeName() {
        return store.getServers().stream()
                .filter(ServerConfig::isActive)
                .map(ServerConfig::getName)
                .findFirst()
                .orElse(null);
    }

    /**
     * The latency tooltip of every visible cell that shows a measured server,
     * read after a layout pass; {@code items} receives what each of those
     * cells shows.
     */
    private Map<ListCell<?>, Tooltip> latencyTooltipsByCell(Map<ListCell<?>, ServerConfig> items) {
        Map<ListCell<?>, Tooltip> tooltips = new IdentityHashMap<>();
        interact(() -> {
            list().layout();
            for (Node node : list().lookupAll(".list-cell")) {
                if (node instanceof ListCell<?> cell && cell.isVisible() && !cell.isEmpty()
                        && cell.getItem() instanceof ServerConfig server
                        && cell.getGraphic() != null
                        && cell.getGraphic().lookup(".latency-chip") instanceof Label chip) {
                    tooltips.put(cell, chip.getTooltip());
                    items.put(cell, server);
                }
            }
        });
        return tooltips;
    }

    /**
     * The chip's unit was an English literal, so a Russian UI read "212 ms"
     * beside every server.
     */
    @Test
    void theLatencyChipUsesTheUnitOfTheCurrentLanguage() {
        interact(() -> com.vlessclient.app.I18n.setLocale(java.util.Locale.of("ru")));
        try {
            interact(() -> list().refresh());

            assertThat(latencyChipTexts())
                    .as("a measured chip reads in the language the app is in")
                    .anyMatch(text -> text.endsWith("\u043c\u0441"));
        } finally {
            interact(() -> com.vlessclient.app.I18n.setLocale(java.util.Locale.ENGLISH));
            interact(() -> list().refresh());
        }
    }

    /** The text of every latency chip on screen. */
    private java.util.List<String> latencyChipTexts() {
        java.util.List<String> texts = new java.util.ArrayList<>();
        interact(() -> {
            list().layout();
            for (Node node : list().lookupAll(".latency-chip")) {
                if (node instanceof Label chip && chip.getText() != null) {
                    texts.add(chip.getText());
                }
            }
        });
        return texts;
    }

    @Test
    void searchMatchesNameAddressPortAndProtocol() {
        search("germany");
        assertThat(visibleNames()).containsExactly("Germany 02");

        search("103.75");
        assertThat(visibleNames()).containsExactly("Japan 07");

        search("51820");
        assertThat(visibleNames()).containsExactly("Finland WG");

        search("trojan");
        assertThat(visibleNames()).containsExactly("Germany 02");
    }

    @Test
    void searchIsCaseInsensitiveAndClearsBack() {
        search("  NETHER  ");
        assertThat(visibleNames()).containsExactly("Netherlands 01");

        search("");
        assertThat(visibleNames()).hasSize(4);
    }

    @Test
    void aSearchThatMatchesNothingEmptiesTheListRatherThanIgnoringIt() {
        search("zzz-no-such-server");
        assertThat(visibleNames()).isEmpty();
        // The "no servers configured" panel is about having none at all, so it
        // must stay out of the way of a search that simply found none.
        assertThat(list().isVisible()).isTrue();
    }

    @Test
    void sortsByName() {
        chooseSort("NAME");
        assertThat(visibleNames())
                .containsExactly("Finland WG", "Germany 02", "Japan 07", "Netherlands 01");
    }

    /**
     * Unmeasured and unreachable both belong at the bottom: neither is a
     * number, and floating either to the top of a "fastest first" list would
     * claim a speed that was never observed.
     */
    @Test
    void sortsByLatencyWithUnmeasuredAndUnreachableLast() {
        chooseSort("LATENCY");
        assertThat(visibleNames()).containsExactly(
                "Japan 07",         // 48 ms
                "Germany 02",       // 212 ms
                "Finland WG",       // unreachable
                "Netherlands 01");  // never measured
    }

    /**
     * A row's latency tooltip belongs to its cell and gets new text when the
     * cell shows another server. A new Tooltip per fill cost a popup control
     * on every sort, search and scroll.
     */
    @Test
    void reorderingRetextsTheLatencyTooltipsRatherThanReplacingThem() {
        Map<ListCell<?>, ServerConfig> itemsBefore = new IdentityHashMap<>();
        Map<ListCell<?>, Tooltip> tooltipsBefore = latencyTooltipsByCell(itemsBefore);

        chooseSort("LATENCY");
        Map<ListCell<?>, ServerConfig> itemsAfter = new IdentityHashMap<>();
        Map<ListCell<?>, Tooltip> tooltipsAfter = latencyTooltipsByCell(itemsAfter);

        List<ListCell<?>> refilled = tooltipsBefore.keySet().stream()
                .filter(tooltipsAfter::containsKey)
                .filter(cell -> itemsBefore.get(cell) != itemsAfter.get(cell))
                .toList();
        assertThat(refilled)
                .as("cells that showed one measured server and now show another")
                .isNotEmpty();
        for (ListCell<?> cell : refilled) {
            ServerConfig shown = itemsAfter.get(cell);
            Tooltip tooltip = tooltipsAfter.get(cell);
            assertThat(tooltip)
                    .as("the latency tooltip of the row now showing " + shown.getName())
                    .isSameAs(tooltipsBefore.get(cell));
            assertThat(tooltip.getText())
                    .as("the text of that tooltip")
                    .isEqualTo(I18n.get(tester.canned.get(shown.getId()).throughProxy()
                            ? "dashboard.latency.via.proxy" : "dashboard.latency.via.tcp"));
        }
    }

    @Test
    void sortAndSearchComposeRatherThanReplacingEachOther() {
        chooseSort("LATENCY");
        // "Germany 02" is the one row with no '1' anywhere — not in its name,
        // address, port or protocol — so this drops exactly it.
        search("1");
        assertThat(visibleNames()).containsExactly(
                "Japan 07",         // 48 ms
                "Finland WG",       // unreachable, so ranked by name against…
                "Netherlands 01");  // …never measured
    }

    /**
     * The regression this view was rebuilt around. Activation used to be bound
     * to the selection model, and a filtered list moves the selection on its
     * own as the user types — so narrowing the search silently switched which
     * server the app would connect through.
     */
    @Test
    void narrowingTheSearchDoesNotChangeTheActiveServer() {
        interact(() -> {
            ServerConfig germany = store.getServers().stream()
                    .filter(candidate -> "Germany 02".equals(candidate.getName()))
                    .findFirst().orElseThrow();
            store.setActiveServer(germany.getId());
        });
        assertThat(activeName()).isEqualTo("Germany 02");

        // Each of these rebuilds the visible list, and the middle one filters
        // the active server out of view entirely.
        search("finland");
        search("japan");
        search("");

        assertThat(activeName()).isEqualTo("Germany 02");
    }

    @Test
    void reorderingTheListDoesNotChangeTheActiveServer() {
        interact(() -> store.setActiveServer(store.getServers().get(0).getId()));

        chooseSort("LATENCY");
        chooseSort("NAME");

        assertThat(activeName()).isEqualTo("Netherlands 01");
    }
}
