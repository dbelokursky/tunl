package com.vlessclient.service.mcp.tools;

import com.vlessclient.service.mcp.AppControlService;
import com.vlessclient.service.mcp.McpTool;
import com.vlessclient.service.mcp.McpToolException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code connect} — connects the tunnel, optionally selecting a server and/or
 * proxy mode. TUN mode requires {@code confirm:true} because it triggers a macOS
 * admin password prompt. Mutating.
 */
public class ConnectTool implements McpTool {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final AppControlService control;

    public ConnectTool(AppControlService control) {
        this.control = control;
    }

    @Override
    public String name() {
        return "connect";
    }

    @Override
    public String description() {
        return "Connect the VPN. Optional 'serverId' (defaults to the active server), "
                + "'mode' (system_proxy|tun), and 'confirm' (required true for TUN mode).";
    }

    @Override
    public boolean mutating() {
        return true;
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = MAPPER.createObjectNode();
        props.set("serverId", MAPPER.createObjectNode().put("type", "string"));
        ObjectNode modeProp = MAPPER.createObjectNode();
        modeProp.put("type", "string");
        modeProp.putArray("enum").add("system_proxy").add("tun");
        props.set("mode", modeProp);
        props.set("confirm", MAPPER.createObjectNode().put("type", "boolean"));
        schema.set("properties", props);
        return schema;
    }

    @Override
    public Object call(ObjectNode arguments) throws McpToolException {
        String serverId = text(arguments, "serverId");
        String mode = text(arguments, "mode");
        JsonNode confirmNode = arguments.get("confirm");
        boolean confirm = confirmNode != null && confirmNode.asBoolean(false);
        return control.connect(serverId, mode, confirm);
    }

    private String text(ObjectNode args, String field) {
        JsonNode node = args.get(field);
        return node != null && node.isString() ? node.asString() : null;
    }
}
