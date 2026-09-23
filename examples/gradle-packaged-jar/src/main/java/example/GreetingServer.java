package example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/** Minimal stdio MCP server with one no-argument greeting tool. */
public final class GreetingServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode request = MAPPER.readTree(line);
            // Notifications (including notifications/initialized) have no response.
            if (!request.has("id")) {
                continue;
            }
            ObjectNode response = MAPPER.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", request.get("id"));
            switch (request.path("method").asText()) {
                case "initialize" -> response.set("result", MAPPER.readTree("""
                        {"protocolVersion":"2025-11-25","capabilities":{"tools":{}},
                         "serverInfo":{"name":"gradle-packaged-jar","version":"1.0.0"}}
                        """));
                case "ping" -> response.putObject("result");
                case "tools/list" -> response.set("result", MAPPER.readTree("""
                        {"tools":[{"name":"greet","description":"Returns a friendly greeting.",
                         "inputSchema":{"type":"object","properties":{}}}]}
                        """));
                case "tools/call" -> {
                    if ("greet".equals(request.path("params").path("name").asText())) {
                        response.set("result", MAPPER.readTree("""
                                {"content":[{"type":"text","text":"Hello from a packaged JAR!"}],
                                 "isError":false}
                                """));
                    } else {
                        response.putObject("error").put("code", -32602).put("message", "Unknown tool");
                    }
                }
                default -> response.putObject("error").put("code", -32601).put("message", "Method not found");
            }
            // Stdout carries only newline-delimited JSON-RPC messages.
            out.write(MAPPER.writeValueAsString(response));
            out.write('\n');
            out.flush();
        }
    }
}
