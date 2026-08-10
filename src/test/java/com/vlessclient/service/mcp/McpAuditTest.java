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

    /** Builds a tools/call message carrying the given arguments node. */
    private ObjectNode toolCall(String name, ObjectNode arguments) {
        ObjectNode msg = toolCall(name);
        ((ObjectNode) msg.get("params")).set("arguments", arguments);
        return msg;
    }

    @Test
    void server_redactsShareLinkFromAuditedArguments() {
        String shareLink = "vless://b1e4c0de-1234-4321-abcd-9f3caa1b2c3d@gateway.example:443"
                + "?security=reality#Amsterdam";

        List<String> recordedArgs = new ArrayList<>();
        McpAuditLog capture = (tool, args, error, message) -> recordedArgs.add(args);

        McpServer server = new McpServer("vless-client", "0.1.0", MAPPER, () -> true);
        server.setAuditLog(capture);
        server.addTool(new com.vlessclient.service.mcp.tools.LambdaTool(
                "add_server", "adds a server", true, MAPPER.createObjectNode(),
                args -> "ok"));

        ObjectNode arguments = MAPPER.createObjectNode();
        arguments.put("shareLink", shareLink);
        arguments.put("name", "Amsterdam");
        server.handle(toolCall("add_server", arguments));

        assertThat(recordedArgs).hasSize(1);
        // The uuid is exactly what ConfigStore seals into the keychain; it must
        // not survive into a plaintext rotating log.
        assertThat(recordedArgs.get(0))
                .doesNotContain("b1e4c0de-1234-4321-abcd-9f3caa1b2c3d")
                .doesNotContain(shareLink)
                .contains("<redacted>")
                .contains("Amsterdam");   // non-secret arguments stay readable
    }

    @Test
    void server_redactsUrlsHidingInUnlistedArguments() {
        // A credential arriving under a key the denylist never heard of: the
        // value-shape pass is what catches this one.
        List<String> recordedArgs = new ArrayList<>();
        McpAuditLog capture = (tool, args, error, message) -> recordedArgs.add(args);

        McpServer server = new McpServer("vless-client", "0.1.0", MAPPER, () -> true);
        server.setAuditLog(capture);
        server.addTool(new com.vlessclient.service.mcp.tools.LambdaTool(
                "set_setting", "sets a setting", true, MAPPER.createObjectNode(),
                args -> "ok"));

        ObjectNode arguments = MAPPER.createObjectNode();
        arguments.put("key", "subscription_endpoint");
        arguments.put("value", "https://provider.example/sub?token=9f3caa1b2c3d4e5f");
        server.handle(toolCall("set_setting", arguments));

        assertThat(recordedArgs.get(0))
                .doesNotContain("9f3caa1b2c3d4e5f")
                .contains("https://provider.example/…");
    }

    @Test
    void auditLog_redactsUrlsQuotedBackInAnErrorMessage() {
        Logger auditLogger = (Logger) LoggerFactory.getLogger("mcp.audit");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
        try {
            new FileAuditLog().record("add_server", "{}", true,
                    "Missing UUID in vless://secret-uuid@gateway.example:443");

            String line = appender.list.get(0).getFormattedMessage();
            assertThat(line)
                    .doesNotContain("secret-uuid")
                    .contains("vless://gateway.example:443/…");
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
