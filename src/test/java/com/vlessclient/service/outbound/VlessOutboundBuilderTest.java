package com.vlessclient.service.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.model.ServerConfig;
import com.vlessclient.testing.TestServers;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The flow is checked without the spaces a link or an edit can leave around
 * it ({@link CoreSettings#refusal}), and it was written with them: a link
 * with {@code flow=xtls-rprx-vision%20} was stored as Vision and refused by
 * the core at every connect ("unsupported flow").
 */
class VlessOutboundBuilderTest {

    private final VlessOutboundBuilder builder =
            new VlessOutboundBuilder(JsonMapper.builder().build());

    @Test
    void theFlowIsWrittenWithoutTheSpacesAroundIt() {
        for (String flow : new String[] {
            "xtls-rprx-vision ", " xtls-rprx-vision", "xtls-rprx-vision\n"}) {
            ObjectNode outbound = builder.build(vless(flow), "srv-1");

            assertThat(outbound.get("flow").asString()).as("[%s]", flow)
                    .isEqualTo("xtls-rprx-vision");
        }
    }

    @Test
    void aFlowOfSpacesIsNoFlow() {
        assertThat(builder.build(vless("  "), "srv-1").has("flow")).isFalse();
    }

    private static ServerConfig vless(String flow) {
        return TestServers.vless("vision").flow(flow).build();
    }
}
