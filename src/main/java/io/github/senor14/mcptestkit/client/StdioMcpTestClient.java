package io.github.senor14.mcptestkit.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.senor14.mcptestkit.McpTestClient;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP test client that speaks the stdio wire protocol (newline-delimited JSON-RPC 2.0)
 * directly, with no MCP SDK dependency.
 *
 * <p>Talking to the wire instead of an SDK is deliberate: conformance tests should observe
 * what the server actually sends, and the toolkit can test MCP servers written in any
 * language or SDK version.</p>
 */
public final class StdioMcpTestClient implements McpTestClient {

    /** Latest protocol revision this client requests during initialize. */
    public static final String REQUESTED_PROTOCOL_VERSION = "2026-07-28";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Process process;
    private final BufferedWriter serverStdin;
    private final Duration timeout;
    private final AtomicLong idSequence = new AtomicLong();
    private final BlockingQueue<JsonNode> incoming = new LinkedBlockingQueue<>();
    private final StringBuilder stderrBuffer = new StringBuilder();

    private boolean initialized;
    private String serverName = "";
    private String serverVersion = "";
    private String protocolVersion = "";

    private StdioMcpTestClient(Process process, Duration timeout) {
        this.process = process;
        this.timeout = timeout;
        this.serverStdin = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        startPump("mcp-testkit-stdout", process.inputReader(StandardCharsets.UTF_8), this::onServerLine);
        startPump("mcp-testkit-stderr", process.errorReader(StandardCharsets.UTF_8),
                line -> stderrBuffer.append(line).append('\n'));
    }

    /** Launches the server process and performs the MCP initialize handshake. */
    public static StdioMcpTestClient connect(String[] command, Map<String, String> env, Duration timeout) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().putAll(env);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to launch MCP server: " + String.join(" ", command), e);
        }
        StdioMcpTestClient client = new StdioMcpTestClient(process, timeout);
        client.initialize();
        return client;
    }

    private void initialize() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("protocolVersion", REQUESTED_PROTOCOL_VERSION);
        params.set("capabilities", MAPPER.createObjectNode());
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "mcp-java-testkit");
        clientInfo.put("version", "0.1.0");

        JsonNode result = request("initialize", params);
        protocolVersion = result.path("protocolVersion").asText("");
        serverName = result.path("serverInfo").path("name").asText("");
        serverVersion = result.path("serverInfo").path("version").asText("");
        sendNotification("notifications/initialized");
        initialized = true;
    }

    @Override
    public boolean initialized() {
        return initialized;
    }

    @Override
    public String serverName() {
        return serverName;
    }

    @Override
    public String serverVersion() {
        return serverVersion;
    }

    @Override
    public String protocolVersion() {
        return protocolVersion;
    }

    @Override
    public List<JsonNode> listTools() {
        List<JsonNode> tools = new ArrayList<>();
        String cursor = null;
        do {
            ObjectNode params = MAPPER.createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode result = request("tools/list", params);
            result.path("tools").forEach(tools::add);
            cursor = result.hasNonNull("nextCursor") ? result.get("nextCursor").asText() : null;
        } while (cursor != null);
        return tools;
    }

    @Override
    public List<String> toolNames() {
        return listTools().stream().map(tool -> tool.path("name").asText()).toList();
    }

    @Override
    public JsonNode callTool(String name, Map<String, Object> arguments) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", name);
        params.set("arguments", MAPPER.valueToTree(arguments));
        return request("tools/call", params);
    }

    @Override
    public void close() {
        try {
            serverStdin.close();
        } catch (IOException ignored) {
            // Server may have exited already; process teardown below is what matters.
        }
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private JsonNode request(String method, ObjectNode params) {
        long id = idSequence.incrementAndGet();
        ObjectNode message = MAPPER.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.set("params", params);
        send(message);

        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new IllegalStateException(timeoutMessage(method));
            }
            JsonNode received;
            try {
                received = incoming.poll(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for MCP response to " + method, e);
            }
            if (received == null) {
                throw new IllegalStateException(timeoutMessage(method));
            }
            if (received.path("id").asLong(-1) == id && !received.has("method")) {
                if (received.has("error")) {
                    throw new IllegalStateException(
                            "MCP server returned an error for " + method + ": " + received.get("error"));
                }
                return received.path("result");
            }
            // Anything else is a server notification or request; handled in onServerLine.
        }
    }

    private void onServerLine(String line) {
        if (line.isBlank()) {
            return;
        }
        JsonNode message;
        try {
            message = MAPPER.readTree(line);
        } catch (IOException e) {
            return; // Non-JSON noise on stdout; conformance checks for this can come later.
        }
        boolean isServerRequest = message.has("method") && message.has("id");
        if (isServerRequest) {
            rejectServerRequest(message);
            return;
        }
        boolean isResponse = message.has("id");
        if (isResponse) {
            incoming.offer(message);
        }
        // Notifications are ignored in v0.
    }

    /** Tests don't serve sampling/elicitation; refuse politely so the server never blocks on us. */
    private void rejectServerRequest(JsonNode requestMessage) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", requestMessage.get("id"));
        ObjectNode error = response.putObject("error");
        error.put("code", -32601);
        error.put("message", "mcp-java-testkit does not serve " + requestMessage.path("method").asText());
        send(response);
    }

    private void sendNotification(String method) {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        send(message);
    }

    private synchronized void send(JsonNode message) {
        try {
            serverStdin.write(MAPPER.writeValueAsString(message));
            serverStdin.write('\n');
            serverStdin.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write to MCP server stdin. Server stderr so far:\n"
                    + stderrBuffer, e);
        }
    }

    private void startPump(String threadName, BufferedReader reader, java.util.function.Consumer<String> onLine) {
        Thread pump = new Thread(() -> {
            try (reader) {
                String line;
                while ((line = reader.readLine()) != null) {
                    onLine.accept(line);
                }
            } catch (IOException ignored) {
                // Stream closed on shutdown.
            }
        }, threadName);
        pump.setDaemon(true);
        pump.start();
    }

    private String timeoutMessage(String method) {
        return "Timed out after " + timeout + " waiting for MCP response to '" + method
                + "'. Server alive: " + process.isAlive() + ". Server stderr:\n" + stderrBuffer;
    }
}
