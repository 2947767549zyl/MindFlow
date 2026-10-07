package com.mindflow.harness.mcp.protocol;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class McpInitializeRequest {
    public static final String PROTOCOL_VERSION = "2025-03-26";

    private McpInitializeRequest() {
    }

    public static ObjectNode toJson() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode capabilities = root.putObject("capabilities");
        capabilities.putObject("tools");
        ObjectNode clientInfo = root.putObject("clientInfo");
        clientInfo.put("name", "mindflow");
        clientInfo.put("version", "16.0.0");
        return root;
    }
}
