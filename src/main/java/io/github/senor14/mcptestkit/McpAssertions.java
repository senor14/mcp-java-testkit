package io.github.senor14.mcptestkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.senor14.mcptestkit.internal.TokenEstimator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fluent assertions over a connected {@link McpTestClient}, covering the initialize
 * handshake, tools, resources, prompts, structured output, and error-path conformance.
 *
 * <pre>{@code
 * McpAssertions.assertThat(client)
 *     .initializesSuccessfully()
 *     .declaresToolsCapability()
 *     .toolSchemasAreValid()
 *     .readResourceSucceeds("app://config")
 *     .unknownMethodYieldsMethodNotFound();
 * }</pre>
 */
public final class McpAssertions {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> VALID_MESSAGE_ROLES = Set.of("user", "assistant");

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

    // ---------------------------------------------------------------- handshake

    /** Asserts the MCP initialize handshake completed. */
    public McpAssertions initializesSuccessfully() {
        if (!client.initialized()) {
            throw new AssertionError("MCP initialize handshake did not complete against server '"
                    + client.serverName() + "'");
        }
        return this;
    }

    /**
     * Asserts the server declared the {@code tools} capability during initialization —
     * clients are allowed to skip {@code tools/list} entirely for servers that don't.
     */
    public McpAssertions declaresToolsCapability() {
        return declaresCapability("tools");
    }

    /** Asserts the server declared the {@code resources} capability during initialization. */
    public McpAssertions declaresResourcesCapability() {
        return declaresCapability("resources");
    }

    /** Asserts the server declared the {@code prompts} capability during initialization. */
    public McpAssertions declaresPromptsCapability() {
        return declaresCapability("prompts");
    }

    /**
     * Asserts the negotiated protocol version is one of the given revisions — catches a
     * server silently downgrading the handshake to an unexpected revision.
     */
    public McpAssertions negotiatedProtocolVersionIsOneOf(String... revisions) {
        String negotiated = client.protocolVersion();
        for (String revision : revisions) {
            if (revision.equals(negotiated)) {
                return this;
            }
        }
        throw new AssertionError("Negotiated protocol version '" + negotiated
                + "' is not one of " + List.of(revisions));
    }

    // ---------------------------------------------------------------- tools

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

    /** Asserts no two tools share a name — duplicated names make tool selection ambiguous. */
    public McpAssertions toolNamesAreUnique() {
        List<String> names = client.toolNames();
        List<String> duplicates = names.stream()
                .filter(name -> Collections.frequency(names, name) > 1)
                .distinct()
                .toList();
        if (!duplicates.isEmpty()) {
            throw new AssertionError("Duplicate tool names: " + duplicates);
        }
        return this;
    }

    /**
     * Asserts every tool name matches the given regex — useful for enforcing a naming
     * convention (e.g. {@code "[a-z0-9_]+"} for snake_case) across the server.
     */
    public McpAssertions toolNamesMatch(String regex) {
        List<String> offending = client.toolNames().stream()
                .filter(name -> !name.matches(regex))
                .toList();
        if (!offending.isEmpty()) {
            throw new AssertionError("Tool names not matching '" + regex + "': " + offending);
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
            if (tool.path("description").asText("").isBlank()) {
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
            collectSchemaProblems(name, "inputSchema", schema, problems);
        }
        if (!problems.isEmpty()) {
            throw new AssertionError("Invalid tool schemas:\n  " + String.join("\n  ", problems));
        }
        return this;
    }

    /**
     * Structurally validates the {@code outputSchema} of every tool that declares one
     * (outputSchema itself is optional per the spec).
     */
    public McpAssertions toolOutputSchemasAreValid() {
        List<String> problems = new ArrayList<>();
        for (JsonNode tool : client.listTools()) {
            JsonNode schema = tool.path("outputSchema");
            if (schema.isMissingNode() || schema.isNull()) {
                continue;
            }
            collectSchemaProblems(tool.path("name").asText("<unnamed>"), "outputSchema", schema, problems);
        }
        if (!problems.isEmpty()) {
            throw new AssertionError("Invalid tool output schemas:\n  " + String.join("\n  ", problems));
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

    /**
     * Asserts each individual tool definition stays within an estimated token budget,
     * pinpointing the offender — complements {@link #toolListWithinTokenBudget(int)}.
     */
    public McpAssertions eachToolWithinTokenBudget(int maxTokensPerTool) {
        List<String> offending = new ArrayList<>();
        for (JsonNode tool : client.listTools()) {
            int estimate = TokenEstimator.estimate(toJson(tool));
            if (estimate > maxTokensPerTool) {
                offending.add(tool.path("name").asText("<unnamed>") + " (~" + estimate + " tokens)");
            }
        }
        if (!offending.isEmpty()) {
            throw new AssertionError("Tools over the per-tool budget of " + maxTokensPerTool
                    + " tokens: " + offending);
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

    /**
     * Calls the tool and asserts its result carries {@code structuredContent} conforming
     * to the tool's declared {@code outputSchema}: required properties present, and
     * declared property types matching. The tool must declare an outputSchema.
     */
    public McpAssertions callToolConformsToOutputSchema(String name, Map<String, Object> arguments) {
        JsonNode tool = client.listTools().stream()
                .filter(candidate -> name.equals(candidate.path("name").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Tool '" + name + "' not found. Available tools: " + client.toolNames()));
        JsonNode schema = tool.path("outputSchema");
        if (schema.isMissingNode() || schema.isNull()) {
            throw new AssertionError("Tool '" + name + "' declares no outputSchema — nothing to conform to");
        }

        JsonNode result = client.callTool(name, arguments);
        if (result.path("isError").asBoolean(false)) {
            throw new AssertionError("Tool '" + name + "' returned an error: " + result.path("content"));
        }
        JsonNode structured = result.path("structuredContent");
        if (!structured.isObject()) {
            throw new AssertionError("Tool '" + name + "' declares an outputSchema but returned no "
                    + "structuredContent object. Result: " + result);
        }

        List<String> problems = new ArrayList<>();
        for (JsonNode required : schema.path("required")) {
            if (!structured.has(required.asText())) {
                problems.add("missing required property '" + required.asText() + "'");
            }
        }
        schema.path("properties").properties().forEach(entry -> {
            JsonNode value = structured.get(entry.getKey());
            String declaredType = entry.getValue().path("type").asText("");
            if (value != null && !declaredType.isEmpty() && !jsonTypeMatches(declaredType, value)) {
                problems.add("property '" + entry.getKey() + "' is not of declared type '"
                        + declaredType + "': " + value);
            }
        });
        if (!problems.isEmpty()) {
            throw new AssertionError("structuredContent of tool '" + name
                    + "' does not conform to its outputSchema:\n  " + String.join("\n  ", problems));
        }
        return this;
    }

    // ---------------------------------------------------------------- resources

    /** Asserts the server exposes at least one resource. */
    public McpAssertions hasResources() {
        if (client.listResources().isEmpty()) {
            throw new AssertionError("Server '" + client.serverName() + "' exposes no resources");
        }
        return this;
    }

    /** Asserts no two resources share a URI. */
    public McpAssertions resourceUrisAreUnique() {
        List<String> uris = client.listResources().stream()
                .map(resource -> resource.path("uri").asText())
                .toList();
        List<String> duplicates = uris.stream()
                .filter(uri -> Collections.frequency(uris, uri) > 1)
                .distinct()
                .toList();
        if (!duplicates.isEmpty()) {
            throw new AssertionError("Duplicate resource URIs: " + duplicates);
        }
        return this;
    }

    /** Asserts every resource carries a URI and a non-blank name, as the spec requires. */
    public McpAssertions resourcesHaveNames() {
        List<String> offending = new ArrayList<>();
        for (JsonNode resource : client.listResources()) {
            if (resource.path("uri").asText("").isBlank() || resource.path("name").asText("").isBlank()) {
                offending.add(resource.toString());
            }
        }
        if (!offending.isEmpty()) {
            throw new AssertionError("Resources missing uri or name: " + offending);
        }
        return this;
    }

    /**
     * Reads the resource and asserts the result carries at least one content item, each
     * with a {@code uri} and either {@code text} or {@code blob}.
     */
    public McpAssertions readResourceSucceeds(String uri) {
        JsonNode result = client.readResource(uri);
        JsonNode contents = result.path("contents");
        if (!contents.isArray() || contents.isEmpty()) {
            throw new AssertionError("resources/read for '" + uri + "' returned no contents: " + result);
        }
        for (JsonNode content : contents) {
            if (content.path("uri").asText("").isBlank()
                    || (!content.has("text") && !content.has("blob"))) {
                throw new AssertionError("Malformed resource content for '" + uri
                        + "' (needs uri and text|blob): " + content);
            }
        }
        return this;
    }

    // ---------------------------------------------------------------- prompts

    /** Asserts the server exposes at least one prompt. */
    public McpAssertions hasPrompts() {
        if (client.listPrompts().isEmpty()) {
            throw new AssertionError("Server '" + client.serverName() + "' exposes no prompts");
        }
        return this;
    }

    /** Asserts no two prompts share a name. */
    public McpAssertions promptNamesAreUnique() {
        List<String> names = client.listPrompts().stream()
                .map(prompt -> prompt.path("name").asText())
                .toList();
        List<String> duplicates = names.stream()
                .filter(name -> Collections.frequency(names, name) > 1)
                .distinct()
                .toList();
        if (!duplicates.isEmpty()) {
            throw new AssertionError("Duplicate prompt names: " + duplicates);
        }
        return this;
    }

    /** Asserts every prompt argument declaration carries a non-blank {@code name}. */
    public McpAssertions promptArgumentsAreWellFormed() {
        List<String> problems = new ArrayList<>();
        for (JsonNode prompt : client.listPrompts()) {
            String promptName = prompt.path("name").asText("<unnamed>");
            JsonNode arguments = prompt.path("arguments");
            if (arguments.isMissingNode() || arguments.isNull()) {
                continue;
            }
            if (!arguments.isArray()) {
                problems.add(promptName + ": arguments is not an array");
                continue;
            }
            for (JsonNode argument : arguments) {
                if (argument.path("name").asText("").isBlank()) {
                    problems.add(promptName + ": argument without a name: " + argument);
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new AssertionError("Malformed prompt arguments:\n  " + String.join("\n  ", problems));
        }
        return this;
    }

    /**
     * Gets the prompt and asserts the result carries at least one message, each with a
     * valid role ({@code user} or {@code assistant}) and a content object.
     */
    public McpAssertions getPromptSucceeds(String name, Map<String, Object> arguments) {
        JsonNode result = client.getPrompt(name, arguments);
        JsonNode messages = result.path("messages");
        if (!messages.isArray() || messages.isEmpty()) {
            throw new AssertionError("prompts/get for '" + name + "' returned no messages: " + result);
        }
        for (JsonNode message : messages) {
            if (!VALID_MESSAGE_ROLES.contains(message.path("role").asText())
                    || !message.path("content").isObject()) {
                throw new AssertionError("Malformed prompt message for '" + name
                        + "' (needs role user|assistant and content object): " + message);
            }
        }
        return this;
    }

    // ---------------------------------------------------------------- error paths

    /** Asserts an unknown method is rejected with JSON-RPC error code -32601 (method not found). */
    public McpAssertions unknownMethodYieldsMethodNotFound() {
        JsonNode response = client.exchange("mcp_testkit/no_such_method", MAPPER.createObjectNode());
        int code = response.path("error").path("code").asInt(0);
        if (code != -32601) {
            throw new AssertionError("Expected error code -32601 for an unknown method, got: " + response);
        }
        return this;
    }

    /**
     * Asserts calling a non-existent tool is handled gracefully: either a JSON-RPC error
     * response or a tool result flagged {@code isError: true} — never a silent success.
     */
    public McpAssertions unknownToolHandledGracefully() {
        JsonNode params = MAPPER.createObjectNode()
                .put("name", "__mcp_testkit_no_such_tool__")
                .set("arguments", MAPPER.createObjectNode());
        JsonNode response = client.exchange("tools/call", params);
        boolean jsonRpcError = response.has("error");
        boolean flaggedError = response.path("result").path("isError").asBoolean(false);
        if (!jsonRpcError && !flaggedError) {
            throw new AssertionError("Calling an unknown tool neither returned a JSON-RPC error nor "
                    + "an isError result: " + response);
        }
        return this;
    }

    // ---------------------------------------------------------------- internals

    private McpAssertions declaresCapability(String capability) {
        if (!client.serverCapabilities().has(capability)) {
            throw new AssertionError("Server '" + client.serverName() + "' did not declare the '"
                    + capability + "' capability during initialization. Declared: "
                    + client.serverCapabilities());
        }
        return this;
    }

    private static void collectSchemaProblems(String owner, String schemaKind, JsonNode schema,
                                              List<String> problems) {
        if (!schema.isObject()) {
            problems.add(owner + ": " + schemaKind + " is not a JSON object");
            return;
        }
        String type = schema.path("type").asText("");
        if (!type.isEmpty() && !"object".equals(type)) {
            problems.add(owner + ": " + schemaKind + ".type is '" + type + "', expected 'object'");
        }
        JsonNode properties = schema.path("properties");
        if (!properties.isMissingNode() && !properties.isObject()) {
            problems.add(owner + ": " + schemaKind + ".properties is not a JSON object");
        }
        JsonNode required = schema.path("required");
        if (required.isArray()) {
            for (JsonNode requiredProperty : required) {
                if (!properties.has(requiredProperty.asText())) {
                    problems.add(owner + ": required property '" + requiredProperty.asText()
                            + "' is not declared in " + schemaKind + ".properties");
                }
            }
        }
    }

    private static boolean jsonTypeMatches(String declaredType, JsonNode value) {
        return switch (declaredType) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> true; // Unknown declared type: don't fail the assertion on it.
        };
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
