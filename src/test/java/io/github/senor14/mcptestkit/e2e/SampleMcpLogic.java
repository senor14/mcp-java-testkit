package io.github.senor14.mcptestkit.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Transport-agnostic behavior of the sample MCP server used by end-to-end tests:
 * initialize, a two-page tool list (exercising cursor pagination), two tools (one with
 * an output schema), resources, resource templates, and one prompt.
 * Shared by the stdio ({@link SampleMcpServer}) and HTTP ({@link SampleHttpMcpServer}) variants.
 */
final class SampleMcpLogic {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SampleMcpLogic() {
    }

    /** Handles one JSON-RPC message; returns the response, or {@code null} for notifications. */
    static ObjectNode handle(JsonNode message) {
        if (!message.has("id")) {
            return null;
        }
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", message.get("id"));
        switch (message.path("method").asText()) {
            case "initialize" -> response.set("result", initializeResult(message));
            case "tools/list" -> response.set("result", toolsPage(message));
            case "tools/call" -> handleToolCall(message, response);
            case "resources/list" -> response.set("result", resourcesResult());
            case "resources/templates/list" -> response.set("result", resourceTemplatesResult());
            case "resources/read" -> handleResourceRead(message, response);
            case "prompts/list" -> response.set("result", promptsResult());
            case "prompts/get" -> handlePromptGet(message, response);
            default -> error(response, -32601, "Method not found: " + message.path("method").asText());
        }
        return response;
    }

    private static ObjectNode initializeResult(JsonNode request) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("protocolVersion", request.path("params").path("protocolVersion").asText());
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools");
        capabilities.putObject("resources");
        capabilities.putObject("prompts");
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "sample-mcp-server");
        serverInfo.put("version", "1.0.0");
        return result;
    }

    // ---------------------------------------------------------------- tools

    private static ObjectNode toolsPage(JsonNode request) {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        boolean secondPage = "page2".equals(request.path("params").path("cursor").asText());
        if (secondPage) {
            tools.add(tool("echo", "Echoes text back.", "text", "string"));
        } else {
            ObjectNode add = tool("add", "Adds two numbers.", "a", "number", "b", "number");
            ObjectNode outputSchema = add.putObject("outputSchema");
            outputSchema.put("type", "object");
            outputSchema.putObject("properties").putObject("sum").put("type", "number");
            outputSchema.putArray("required").add("sum");
            tools.add(add);
            result.put("nextCursor", "page2");
        }
        return result;
    }

    private static ObjectNode tool(String name, String description, String... propertyTypePairs) {
        ObjectNode tool = MAPPER.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        for (int i = 0; i < propertyTypePairs.length; i += 2) {
            properties.putObject(propertyTypePairs[i]).put("type", propertyTypePairs[i + 1]);
            required.add(propertyTypePairs[i]);
        }
        return tool;
    }

    private static void handleToolCall(JsonNode request, ObjectNode response) {
        JsonNode params = request.path("params");
        String name = params.path("name").asText();
        JsonNode arguments = params.path("arguments");
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode content = result.putArray("content");
        if ("add".equals(name)) {
            long sum = arguments.path("a").asLong() + arguments.path("b").asLong();
            content.addObject().put("type", "text").put("text", String.valueOf(sum));
            result.putObject("structuredContent").put("sum", sum);
            result.put("isError", false);
        } else if ("echo".equals(name)) {
            content.addObject().put("type", "text").put("text", arguments.path("text").asText());
            result.put("isError", false);
        } else {
            content.addObject().put("type", "text").put("text", "Unknown tool: " + name);
            result.put("isError", true);
        }
        response.set("result", result);
    }

    // ---------------------------------------------------------------- resources

    private static ObjectNode resourcesResult() {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode resources = result.putArray("resources");
        ObjectNode greeting = resources.addObject();
        greeting.put("uri", "sample://greeting");
        greeting.put("name", "greeting");
        greeting.put("description", "A friendly greeting.");
        greeting.put("mimeType", "text/plain");
        ObjectNode data = resources.addObject();
        data.put("uri", "sample://data");
        data.put("name", "data");
        data.put("description", "Sample JSON data.");
        data.put("mimeType", "application/json");
        return result;
    }

    private static ObjectNode resourceTemplatesResult() {
        ObjectNode result = MAPPER.createObjectNode();
        ObjectNode template = result.putArray("resourceTemplates").addObject();
        template.put("uriTemplate", "sample://items/{id}");
        template.put("name", "item");
        template.put("description", "An item by id.");
        return result;
    }

    private static void handleResourceRead(JsonNode request, ObjectNode response) {
        String uri = request.path("params").path("uri").asText();
        ObjectNode result = MAPPER.createObjectNode();
        ObjectNode content = MAPPER.createObjectNode();
        content.put("uri", uri);
        switch (uri) {
            case "sample://greeting" -> content.put("mimeType", "text/plain").put("text", "hello");
            case "sample://data" -> content.put("mimeType", "application/json").put("text", "{\"answer\": 42}");
            default -> {
                error(response, -32002, "Resource not found: " + uri);
                return;
            }
        }
        result.putArray("contents").add(content);
        response.set("result", result);
    }

    // ---------------------------------------------------------------- prompts

    private static ObjectNode promptsResult() {
        ObjectNode result = MAPPER.createObjectNode();
        ObjectNode prompt = result.putArray("prompts").addObject();
        prompt.put("name", "greet");
        prompt.put("description", "Builds a greeting prompt.");
        ObjectNode argument = prompt.putArray("arguments").addObject();
        argument.put("name", "who");
        argument.put("description", "Whom to greet.");
        argument.put("required", true);
        return result;
    }

    private static void handlePromptGet(JsonNode request, ObjectNode response) {
        JsonNode params = request.path("params");
        if (!"greet".equals(params.path("name").asText())) {
            error(response, -32602, "Unknown prompt: " + params.path("name").asText());
            return;
        }
        ObjectNode result = MAPPER.createObjectNode();
        result.put("description", "A greeting.");
        ObjectNode message = result.putArray("messages").addObject();
        message.put("role", "user");
        message.putObject("content")
                .put("type", "text")
                .put("text", "Greet " + params.path("arguments").path("who").asText("someone") + "!");
        response.set("result", result);
    }

    // ---------------------------------------------------------------- shared

    private static void error(ObjectNode response, int code, String message) {
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
    }

    /** A {@code notifications/tools/list_changed} notification, for servers to emit. */
    static ObjectNode listChangedNotification() {
        ObjectNode notification = MAPPER.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/tools/list_changed");
        return notification;
    }
}
