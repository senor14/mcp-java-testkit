package io.github.senor14.mcptestkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.senor14.mcptestkit.internal.TokenEstimator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fluent assertions over a connected {@link McpTestClient}.
 *
 * <pre>{@code
 * McpAssertions.assertThat(client)
 *     .initializesSuccessfully()
 *     .hasTools()
 *     .toolsHaveDescriptions()
 *     .toolSchemasAreValid()
 *     .toolListWithinTokenBudget(2_000);
 * }</pre>
 */
public final class McpAssertions {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final McpTestClient client;

    private McpAssertions(McpTestClient client) {
        this.client = client;
    }

    public static McpAssertions assertThat(McpTestClient client) {
        if (client == null) {
            throw new AssertionError("McpTestClient is null — is the server annotated with @McpServerTest running?");
        }
        return new McpAssertions(client);
    }

    /** Asserts the MCP initialize handshake completed. */
    public McpAssertions initializesSuccessfully() {
        if (!client.initialized()) {
            throw new AssertionError("MCP initialize handshake did not complete against server '"
                    + client.serverName() + "'");
        }
        return this;
    }

    /** Asserts the server exposes at least one tool. */
    public McpAssertions hasTools() {
        if (client.listTools().isEmpty()) {
            throw new AssertionError("Server '" + client.serverName() + "' exposes no tools");
        }
        return this;
    }

    /** Asserts a tool with the given name exists. */
    public McpAssertions toolExists(String name) {
        if (!client.toolNames().contains(name)) {
            throw new AssertionError("Tool '" + name + "' not found. Available tools: " + client.toolNames());
        }
        return this;
    }

    /**
     * Asserts every tool carries a non-blank description — agents choose tools by
     * description, so a missing one is a usability defect, not a style issue.
     */
    public McpAssertions toolsHaveDescriptions() {
        List<String> missing = new ArrayList<>();
        for (JsonNode tool : client.listTools()) {
            String description = tool.path("description").asText("");
            if (description.isBlank()) {
                missing.add(tool.path("name").asText("<unnamed>"));
            }
        }
        if (!missing.isEmpty()) {
            throw new AssertionError("Tools with missing/blank descriptions: " + missing);
        }
        return this;
    }

    /**
     * Structurally validates each tool's {@code inputSchema}: it must be a JSON object
     * of {@code type: object}, {@code properties} (when present) must be an object, and
     * every {@code required} entry must name a declared property.
     */
    public McpAssertions toolSchemasAreValid() {
        List<String> problems = new ArrayList<>();
        for (JsonNode tool : client.listTools()) {
            String name = tool.path("name").asText("<unnamed>");
            JsonNode schema = tool.path("inputSchema");
            if (schema.isMissingNode() || schema.isNull()) {
                problems.add(name + ": inputSchema is missing");
                continue;
            }
            if (!schema.isObject()) {
                problems.add(name + ": inputSchema is not a JSON object");
                continue;
            }
            String type = schema.path("type").asText("");
            if (!type.isEmpty() && !"object".equals(type)) {
                problems.add(name + ": inputSchema.type is '" + type + "', expected 'object'");
            }
            JsonNode properties = schema.path("properties");
            if (!properties.isMissingNode() && !properties.isObject()) {
                problems.add(name + ": inputSchema.properties is not a JSON object");
            }
            JsonNode required = schema.path("required");
            if (required.isArray()) {
                for (JsonNode req : required) {
                    if (!properties.has(req.asText())) {
                        problems.add(name + ": required property '" + req.asText()
                                + "' is not declared in properties");
                    }
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new AssertionError("Invalid tool schemas:\n  " + String.join("\n  ", problems));
        }
        return this;
    }

    /**
     * Asserts the serialized tool list stays within an estimated token budget.
     * Tool lists are injected into every agent conversation, so unchecked growth
     * directly taxes every user of the server.
     */
    public McpAssertions toolListWithinTokenBudget(int maxTokens) {
        String json = toJson(client.listTools());
        int estimate = TokenEstimator.estimate(json);
        if (estimate > maxTokens) {
            throw new AssertionError("Tool list is ~" + estimate + " tokens (budget: " + maxTokens
                    + "). Trim tool descriptions or split the server.");
        }
        return this;
    }

    /** Asserts calling the tool returns a non-error result. */
    public McpAssertions callToolSucceeds(String name, Map<String, Object> arguments) {
        JsonNode result = client.callTool(name, arguments);
        if (result.path("isError").asBoolean(false)) {
            throw new AssertionError("Tool '" + name + "' returned an error: " + result.path("content"));
        }
        return this;
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
