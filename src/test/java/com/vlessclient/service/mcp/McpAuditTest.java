package com.vlessclient.service.mcp;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class McpAuditTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    void fileAuditLog_emitsThroughMcpAuditLogger() {
        Logger auditLogger = (Logger) LoggerFactory.getLogger("mcp.audit");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
        try {
            FileAuditLog audit = new FileAuditLog();
            audit.record("connect", "{\"mode\":\"tun\"}", false, "");
            audit.record("delete_server", "{\"id\":\"x\"}", true, "not found");

            List<String> lines = new ArrayList<>();
            appender.list.forEach(e -> lines.add(e.getFormattedMessage()));
            assertThat(lines).hasSize(2);
            assertThat(lines.get(0)).contains("connect").contains("OK");
            assertThat(lines.get(1)).contains("delete_server").contains("ERROR")
                    .contains("not found");
            // timestamp, tool, OK/ERROR, args, message — tab-separated.
            assertThat(lines.get(1).split("\t")).hasSize(5);
        } finally {
            auditLogger.detachAppender(appender);
        }
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
