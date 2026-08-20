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

    /** Id of the server-initiated ping sent right after initialization. */
    static final String PING_ID = "server-ping-1";

    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode message = MAPPER.readTree(line);
            if (!message.has("method") && PING_ID.equals(message.path("id").asText())) {
                // The client answered our ping. Report what it said so the test can assert on it.
                ObjectNode observed = MAPPER.createObjectNode();
                observed.put("jsonrpc", "2.0");
                observed.put("method", "notifications/ping_observed");
                observed.putObject("params")
                        .put("answeredWithResult", message.path("result").isObject())
                        .put("errorCode", message.path("error").path("code").asInt(0));
                out.write(MAPPER.writeValueAsString(observed));
                out.write('\n');
                out.flush();
                continue;
            }
            ObjectNode response = SampleMcpLogic.handle(message);
            if (response == null) {
                // After the client confirms initialization, push a server-initiated notification
                // so notification capture is exercised end-to-end, then ping it: the spec requires
                // the receiver to answer with an empty result.
                if ("notifications/initialized".equals(message.path("method").asText())) {
                    out.write(MAPPER.writeValueAsString(SampleMcpLogic.listChangedNotification()));
                    out.write('\n');
                    ObjectNode ping = MAPPER.createObjectNode();
                    ping.put("jsonrpc", "2.0");
                    ping.put("id", PING_ID);
                    ping.put("method", "ping");
                    out.write(MAPPER.writeValueAsString(ping));
                    out.write('\n');
                    out.flush();
                }
                continue;
            }
            out.write(MAPPER.writeValueAsString(response));
            out.write('\n');
            out.flush();
        }
    }
}
