package io.github.senor14.mcptestkit.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.senor14.mcptestkit.McpTestClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP protocol flow shared by all transports: the initialize handshake, the paginated
 * list operations, calls, and raw exchanges. Subclasses supply the wire transport by
 * implementing {@link #performRequest(long, String, ObjectNode)} and
 * {@link #sendNotification(String)}.
 */
public abstract class AbstractMcpTestClient implements McpTestClient {

    /**
     * Protocol revision this client requests during initialize — the newest revision whose
     * wire protocol it implements. Servers that do not support it answer with a revision they
     * do support; assert on the outcome with
     * {@link io.github.senor14.mcptestkit.McpAssertions#negotiatedProtocolVersionIsOneOf}.
     *
     * <p>The 2026-07-28 revision removes the initialize handshake entirely in favour of
     * {@code server/discover} and {@code _meta}-carried versions, so it needs a separate
     * client and is not implemented yet.</p>
     */
    public static final String REQUESTED_PROTOCOL_VERSION = "2025-11-25";

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    protected final AtomicLong idSequence = new AtomicLong();

    /** Server-initiated notifications, appended by transports as they observe them. */
    protected final List<JsonNode> receivedNotifications = Collections.synchronizedList(new ArrayList<>());

    private boolean initialized;
    private String serverName = "";
    private String serverVersion = "";
    private String protocolVersion = "";
    private JsonNode serverCapabilities = MAPPER.createObjectNode();

    /**
     * Sends a JSON-RPC request over the transport and returns the <em>full response
     * envelope</em> (containing {@code result} or {@code error}).
     */
    protected abstract JsonNode performRequest(long id, String method, ObjectNode params);

    /** Sends a JSON-RPC notification over the transport. */
    protected abstract void sendNotification(String method);

    protected final void initialize() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("protocolVersion", REQUESTED_PROTOCOL_VERSION);
        params.set("capabilities", MAPPER.createObjectNode());
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "mcp-java-testkit");
        clientInfo.put("version", "0.5.1");

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
        return paginatedList("tools/list", "tools");
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

    @Override
    public final List<JsonNode> listResources() {
        return paginatedList("resources/list", "resources");
    }

    @Override
    public final List<JsonNode> listResourceTemplates() {
        return paginatedList("resources/templates/list", "resourceTemplates");
    }

    @Override
    public final JsonNode readResource(String uri) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("uri", uri);
        return request("resources/read", params);
    }

    @Override
    public final List<JsonNode> listPrompts() {
        return paginatedList("prompts/list", "prompts");
    }

    @Override
    public final JsonNode getPrompt(String name, Map<String, Object> arguments) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", name);
        params.set("arguments", MAPPER.valueToTree(arguments));
        return request("prompts/get", params);
    }

    @Override
    public final JsonNode exchange(String method, JsonNode params) {
        ObjectNode paramsNode = params != null && params.isObject()
                ? (ObjectNode) params
                : MAPPER.createObjectNode();
        return performRequest(idSequence.incrementAndGet(), method, paramsNode);
    }

    @Override
    public final List<JsonNode> notifications() {
        synchronized (receivedNotifications) {
            return List.copyOf(receivedNotifications);
        }
    }

    private JsonNode request(String method, ObjectNode params) {
        long id = idSequence.incrementAndGet();
        JsonNode response = performRequest(id, method, params);
        if (response.has("error")) {
            throw new IllegalStateException(
                    "MCP server returned an error for " + method + ": " + response.get("error"));
        }
        return response.path("result");
    }

    private List<JsonNode> paginatedList(String method, String field) {
        List<JsonNode> items = new ArrayList<>();
        String cursor = null;
        do {
            ObjectNode params = MAPPER.createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode result = request(method, params);
            result.path(field).forEach(items::add);
            cursor = result.hasNonNull("nextCursor") ? result.get("nextCursor").asText() : null;
        } while (cursor != null);
        return items;
    }

    /** Builds a JSON-RPC request envelope. */
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

    /** Records a server-initiated notification observed by a transport. */
    protected final void recordNotification(JsonNode message) {
        receivedNotifications.add(message);
    }
}
