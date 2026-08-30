package com.vlessclient.service.mcp.tools;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

class LambdaToolTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void inputSchemaIsDetachedFromCallers() {
        ObjectNode source = mapper.createObjectNode();
        source.putObject("properties").putObject("name").put("type", "string");
        LambdaTool tool = new LambdaTool("example", "Example tool", false, source,
                arguments -> "ok");

        source.withObject("properties").withObject("name").put("type", "number");
        ObjectNode returned = tool.inputSchema();
        returned.withObject("properties").withObject("name").put("type", "boolean");

        assertThat(tool.inputSchema().path("properties").path("name").path("type").asString())
                .isEqualTo("string");
    }
}
