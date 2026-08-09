package io.github.senor14.mcptestkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** In-memory {@link McpTestClient} for testing the toolkit itself without a server process. */
final class FakeMcpTestClient implements McpTestClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final boolean initialized;
    private final List<JsonNode> tools = new ArrayList<>();
    private final List<JsonNode> resources = new ArrayList<>();
    private final List<JsonNode> prompts = new ArrayList<>();
    private final List<JsonNode> notifications = new ArrayList<>();
    private final Map<String, JsonNode> exchangeResponses = new HashMap<>();
    private JsonNode callResult = MAPPER.createObjectNode().put("isError", false);
    private JsonNode readResourceResult = parse("{\"contents\": [{\"uri\": \"fake://x\", \"text\": \"ok\"}]}");
    private JsonNode getPromptResult = parse(
            "{\"messages\": [{\"role\": \"user\", \"content\": {\"type\": \"text\", \"text\": \"hi\"}}]}");
    private JsonNode capabilities = MAPPER.createObjectNode();

    FakeMcpTestClient(boolean initialized) {
        this.initialized = initialized;
    }

    FakeMcpTestClient withTool(String json) {
        tools.add(parse(json));
        return this;
    }

    FakeMcpTestClient withResource(String json) {
        resources.add(parse(json));
        return this;
    }

    FakeMcpTestClient withPrompt(String json) {
        prompts.add(parse(json));
        return this;
    }

    FakeMcpTestClient withCallResult(String json) {
        callResult = parse(json);
        return this;
    }

    FakeMcpTestClient withReadResourceResult(String json) {
        readResourceResult = parse(json);
        return this;
    }

    FakeMcpTestClient withGetPromptResult(String json) {
        getPromptResult = parse(json);
        return this;
    }

    FakeMcpTestClient withCapabilities(String json) {
        capabilities = parse(json);
        return this;
    }

    FakeMcpTestClient withExchangeResponse(String method, String json) {
        exchangeResponses.put(method, parse(json));
        return this;
    }

    FakeMcpTestClient withNotification(String json) {
        notifications.add(parse(json));
        return this;
    }

    @Override
    public boolean initialized() {
        return initialized;
    }

    @Override
    public String serverName() {
        return "fake-server";
    }

    @Override
    public String serverVersion() {
        return "0.0.0";
    }

    @Override
    public String protocolVersion() {
        return "2026-07-28";
    }

    @Override
    public JsonNode serverCapabilities() {
        return capabilities;
    }

    @Override
    public List<JsonNode> listTools() {
        return List.copyOf(tools);
    }

    @Override
    public List<String> toolNames() {
        return tools.stream().map(t -> t.path("name").asText()).toList();
    }

    @Override
    public JsonNode callTool(String name, Map<String, Object> arguments) {
        return callResult;
    }

    @Override
    public List<JsonNode> listResources() {
        return List.copyOf(resources);
    }

    @Override
    public List<JsonNode> listResourceTemplates() {
        return List.of();
    }

    @Override
    public JsonNode readResource(String uri) {
        return readResourceResult;
    }

    @Override
    public List<JsonNode> listPrompts() {
        return List.copyOf(prompts);
    }

    @Override
    public JsonNode getPrompt(String name, Map<String, Object> arguments) {
        return getPromptResult;
    }

    @Override
    public JsonNode exchange(String method, JsonNode params) {
        JsonNode canned = exchangeResponses.get(method);
        if (canned != null) {
            return canned;
        }
        return parse("{\"jsonrpc\": \"2.0\", \"id\": 0, \"error\": {\"code\": -32601, \"message\": \"nope\"}}");
    }

    @Override
    public List<JsonNode> notifications() {
        return List.copyOf(notifications);
    }

    @Override
    public void close() {
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
