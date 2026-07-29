package com.vlessclient.service.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class McpAuditTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private ObjectNode toolCall(String name) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", name);
        params.set("arguments", MAPPER.createObjectNode());
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("id", 1);
        msg.put("method", "tools/call");
        msg.set("params", params);
        return msg;
    }

    @Test
    void fileAuditLog_appendsLine() throws Exception {
        FileAuditLog audit = new FileAuditLog(tempDir);
        audit.record("connect", "{\"mode\":\"tun\"}", false, "");
        audit.record("delete_server", "{\"id\":\"x\"}", true, "not found");

        Path file = tempDir.resolve("logs").resolve("mcp-audit.log");
        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("connect").contains("OK");
        assertThat(lines.get(1)).contains("delete_server").contains("ERROR").contains("not found");
    }

    @Test
    void server_auditsOnlyMutatingTools() {
        List<String> recorded = new ArrayList<>();
        McpAuditLog capture = (tool, args, error, message) -> recorded.add(tool + ":" + error);

        McpServer server = new McpServer("vless-client", "0.1.0", MAPPER, () -> true);
        server.setAuditLog(capture);
        FakeAppControlService control = new FakeAppControlService();
        server.addTool(new com.vlessclient.service.mcp.tools.GetStatusTool(control));
        server.addTool(new com.vlessclient.service.mcp.tools.DisconnectTool(control));

        server.handle(toolCall("get_status"));   // read: not audited
        server.handle(toolCall("disconnect"));    // mutating: audited

        assertThat(recorded).containsExactly("disconnect:false");
    }
}
