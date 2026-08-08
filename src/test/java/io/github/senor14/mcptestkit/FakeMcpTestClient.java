package io.github.senor14.mcptestkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** In-memory {@link McpTestClient} for testing the toolkit itself without a server process. */
final class FakeMcpTestClient implements McpTestClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final boolean initialized;
    private final List<JsonNode> tools = new ArrayList<>();
    private JsonNode callResult = MAPPER.createObjectNode().put("isError", false);

    FakeMcpTestClient(boolean initialized) {
        this.initialized = initialized;
    }

    FakeMcpTestClient withTool(String json) {
        try {
            tools.add(MAPPER.readTree(json));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    FakeMcpTestClient withCallResult(String json) {
        try {
            callResult = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
    public void close() {
    }
}
