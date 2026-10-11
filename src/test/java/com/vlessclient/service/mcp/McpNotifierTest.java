package com.vlessclient.service.mcp;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class McpNotifierTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final McpNotifier notifier = new McpNotifier(MAPPER);

    private JsonNode next(McpNotifier.Subscriber subscriber) throws Exception {
        String frame = subscriber.poll(0);
        return frame == null ? null : MAPPER.readTree(frame);
    }

    /**
     * Every core line went out as "info", whatever the core said it was: an
     * agent could not tell an error from chatter, nor ask for less.
     */
    @Test
    void aLineGoesOutAtTheLevelTheCoreGaveIt() throws Exception {
        McpNotifier.Subscriber subscriber = notifier.subscribe();

        notifier.broadcastLog("+0300 2026-10-11 09:00:00 ERROR dns: exchange failed");
        notifier.broadcastLog("+0300 2026-10-11 09:00:01 WARN router: slow");
        notifier.broadcastLog("+0300 2026-10-11 09:00:02 DEBUG router: match");
        notifier.broadcastLog("goroutine 1 [running]:");

        assertThat(next(subscriber).path("params").path("level").asString()).isEqualTo("error");
        assertThat(next(subscriber).path("params").path("level").asString())
                .isEqualTo("warning");
        assertThat(next(subscriber).path("params").path("level").asString()).isEqualTo("debug");
        assertThat(next(subscriber).path("params").path("level").asString()).isEqualTo("info");
    }

    @Test
    void theLevelAnAgentSetDropsTheLinesBelowIt() throws Exception {
        McpNotifier.Subscriber subscriber = notifier.subscribe();

        notifier.setMinimumLevel("warning");
        notifier.broadcastLog("+0300 2026-10-11 09:00:00 INFO router: started");
        notifier.broadcastLog("+0300 2026-10-11 09:00:01 WARN router: slow");
        notifier.broadcastLog("+0300 2026-10-11 09:00:02 ERROR dns: failed");

        assertThat(next(subscriber).path("params").path("data").asString()).contains("slow");
        assertThat(next(subscriber).path("params").path("data").asString()).contains("failed");
        assertThat(next(subscriber)).isNull();
    }

    @Test
    void everyLevelOfTheProtocolIsKnown() {
        assertThat(McpNotifier.LEVELS).containsExactly("debug", "info", "notice", "warning",
                "error", "critical", "alert", "emergency");
    }
}
