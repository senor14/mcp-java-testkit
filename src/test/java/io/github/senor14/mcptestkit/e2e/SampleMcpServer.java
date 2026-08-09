package io.github.senor14.mcptestkit.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Minimal MCP server speaking stdio JSON-RPC, launched as a real child process by the
 * end-to-end tests. Behavior lives in {@link SampleMcpLogic}.
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
            ObjectNode response = SampleMcpLogic.handle(message);
            if (response == null) {
                continue;
            }
            out.write(MAPPER.writeValueAsString(response));
            out.write('\n');
            out.flush();
        }
    }
}
