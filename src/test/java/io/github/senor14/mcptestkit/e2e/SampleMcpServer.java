package io.github.senor14.mcptestkit.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Minimal MCP server speaking stdio JSON-RPC, launched as a real child process by the
 * end-to-end tests. Serves two tools across two {@code tools/list} pages so pagination
 * is exercised.
 */
public final class SampleMcpServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode message = MAPPER.readTree(line);
            if (!message.has("id")) {
                continue; // notifications need no reply
            }
            ObjectNode response = MAPPER.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", message.get("id"));
            switch (message.path("method").asText()) {
                case "initialize" -> response.set("result", initializeResult(message));
                case "tools/list" -> response.set("result", toolsPage(message));
                case "tools/call" -> response.set("result", callResult(message));
                default -> {
                    ObjectNode error = response.putObject("error");
                    error.put("code", -32601);
                    error.put("message", "Method not found: " + message.path("method").asText());
                }
            }
            out.write(MAPPER.writeValueAsString(response));
            out.write('\n');
            out.flush();
        }
    }

    private static ObjectNode initializeResult(JsonNode request) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("protocolVersion", request.path("params").path("protocolVersion").asText());
        result.putObject("capabilities").putObject("tools");
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "sample-mcp-server");
        serverInfo.put("version", "1.0.0");
        return result;
    }

    private static ObjectNode toolsPage(JsonNode request) {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        boolean secondPage = "page2".equals(request.path("params").path("cursor").asText());
        if (secondPage) {
            tools.add(tool("echo", "Echoes text back.", "text", "string"));
        } else {
            tools.add(tool("add", "Adds two numbers.", "a", "number", "b", "number"));
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

    private static ObjectNode callResult(JsonNode request) {
        JsonNode params = request.path("params");
        String name = params.path("name").asText();
        JsonNode arguments = params.path("arguments");
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode content = result.putArray("content");
        if ("add".equals(name)) {
            long sum = arguments.path("a").asLong() + arguments.path("b").asLong();
            content.addObject().put("type", "text").put("text", String.valueOf(sum));
            result.put("isError", false);
        } else if ("echo".equals(name)) {
            content.addObject().put("type", "text").put("text", arguments.path("text").asText());
            result.put("isError", false);
        } else {
            content.addObject().put("type", "text").put("text", "Unknown tool: " + name);
            result.put("isError", true);
        }
        return result;
    }
}
