package io.github.senor14.mcptestkit.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.senor14.mcptestkit.McpTestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP protocol flow shared by all transports: the initialize handshake, paginated
 * {@code tools/list}, and {@code tools/call}. Subclasses supply the wire transport by
 * implementing {@link #request(String, ObjectNode)} and {@link #sendNotification(String)}.
 */
public abstract class AbstractMcpTestClient implements McpTestClient {

    /** Latest protocol revision this client requests during initialize. */
    public static final String REQUESTED_PROTOCOL_VERSION = "2026-07-28";

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    protected final AtomicLong idSequence = new AtomicLong();

    private boolean initialized;
    private String serverName = "";
    private String serverVersion = "";
    private String protocolVersion = "";
    private JsonNode serverCapabilities = MAPPER.createObjectNode();

    /** Sends a JSON-RPC request over the transport and returns the {@code result} node. */
    protected abstract JsonNode request(String method, ObjectNode params);

    /** Sends a JSON-RPC notification over the transport. */
    protected abstract void sendNotification(String method);

    protected final void initialize() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("protocolVersion", REQUESTED_PROTOCOL_VERSION);
        params.set("capabilities", MAPPER.createObjectNode());
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "mcp-java-testkit");
        clientInfo.put("version", "0.2.0");

        JsonNode result = request("initialize", params);
        protocolVersion = result.path("protocolVersion").asText("");
        serverName = result.path("serverInfo").path("name").asText("");
        serverVersion = result.path("serverInfo").path("version").asText("");
        if (result.path("capabilities").isObject()) {
            serverCapabilities = result.get("capabilities");
        }
        sendNotification("notifications/initialized");
        initialized = true;
    }

    @Override
    public final boolean initialized() {
        return initialized;
    }

    @Override
    public final String serverName() {
        return serverName;
    }

    @Override
    public final String serverVersion() {
        return serverVersion;
    }

    @Override
    public final String protocolVersion() {
        return protocolVersion;
    }

    @Override
    public final JsonNode serverCapabilities() {
        return serverCapabilities;
    }

    @Override
    public final List<JsonNode> listTools() {
        List<JsonNode> tools = new ArrayList<>();
        String cursor = null;
        do {
            ObjectNode params = MAPPER.createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode result = request("tools/list", params);
            result.path("tools").forEach(tools::add);
            cursor = result.hasNonNull("nextCursor") ? result.get("nextCursor").asText() : null;
        } while (cursor != null);
        return tools;
    }

    @Override
    public final List<String> toolNames() {
        return listTools().stream().map(tool -> tool.path("name").asText()).toList();
    }

    @Override
    public final JsonNode callTool(String name, Map<String, Object> arguments) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", name);
        params.set("arguments", MAPPER.valueToTree(arguments));
        return request("tools/call", params);
    }

    /** Builds a JSON-RPC request envelope with a fresh id. */
    protected final ObjectNode requestEnvelope(long id, String method, ObjectNode params) {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.set("params", params);
        return message;
    }

    /** Builds a JSON-RPC notification envelope. */
    protected final ObjectNode notificationEnvelope(String method) {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        return message;
    }

    /** Unwraps a JSON-RPC response, throwing on {@code error} responses. */
    protected final JsonNode unwrapResponse(JsonNode response, String method) {
        if (response.has("error")) {
            throw new IllegalStateException(
                    "MCP server returned an error for " + method + ": " + response.get("error"));
        }
        return response.path("result");
    }
}
