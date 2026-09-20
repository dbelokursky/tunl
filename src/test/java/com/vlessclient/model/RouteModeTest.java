package com.vlessclient.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** What goes through the tunnel is kept in routing.json, and a file without it routes everything. */
class RouteModeTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void theModeIsKeptAndReadBack() {
        RoutingConfig routing = new RoutingConfig();
        routing.setMode(RouteMode.BLOCKED_IN_RUSSIA);

        String json = mapper.writeValueAsString(routing);

        assertThat(json).contains("\"mode\":\"blocked-ru\"");
        assertThat(mapper.readValue(json, RoutingConfig.class).getMode())
                .isEqualTo(RouteMode.BLOCKED_IN_RUSSIA);
    }

    @Test
    void aFileWithoutAModeOrWithAnUnknownOneRoutesEverything() {
        assertThat(mapper.readValue("{}", RoutingConfig.class).getMode()).isEqualTo(RouteMode.ALL);
        assertThat(mapper.readValue("{\"mode\":\"from-a-later-build\"}", RoutingConfig.class)
                .getMode()).isEqualTo(RouteMode.ALL);
    }
}
